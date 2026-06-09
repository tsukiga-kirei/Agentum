package com.agentum.agent.application;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.mcp.application.McpRuntimeRequest;
import com.agentum.mcp.application.McpRuntimeService;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.workbench.application.RunExecutionCancellationRegistry;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantModelAssignmentEntity;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.\\-\\u4e00-\\u9fa5]+)\\s*}}");
    private static final Pattern FINAL_ANSWER_FALLBACK_PATTERN = Pattern.compile("\"answer\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final int DEFAULT_MAX_AGENT_ITERATIONS = 4;

    private final TenantModelAssignmentRepository tenantModelAssignmentRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;
    private final McpRuntimeService mcpRuntimeService;
    private final SkillRuntimeService skillRuntimeService;
    private final FieldEncryptionService fieldEncryptionService;
    private final ModelCallLogRepository modelCallLogRepository;
    private final ModelChatClient modelChatClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RunExecutionCancellationRegistry cancellationRegistry;

    public AgentRuntimeService(
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        ModelProviderRepository modelProviderRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository,
        McpRuntimeService mcpRuntimeService,
        SkillRuntimeService skillRuntimeService,
        FieldEncryptionService fieldEncryptionService,
        ModelCallLogRepository modelCallLogRepository,
        ModelChatClient modelChatClient,
        ObjectMapper objectMapper,
        Clock clock,
        RunExecutionCancellationRegistry cancellationRegistry
    ) {
        this.tenantModelAssignmentRepository = tenantModelAssignmentRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
        this.mcpRuntimeService = mcpRuntimeService;
        this.skillRuntimeService = skillRuntimeService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.modelCallLogRepository = modelCallLogRepository;
        this.modelChatClient = modelChatClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.cancellationRegistry = cancellationRegistry;
    }

    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        return executeAgentLoop(request, AgentRuntimeEventSink.noop(), false);
    }

    public AgentRuntimeResult executeStreaming(AgentRuntimeRequest request, ModelChatClient.StreamingCallback clientCallback) {
        AgentRuntimeResult result = executeAgentLoop(request, new AgentRuntimeEventSink() {
            @Override
            public void onToken(String deltaContent, String accumulatedContent) {
                clientCallback.onChunk(deltaContent);
            }

            @Override
            public void onCompleted(String finalAnswer) {
                clientCallback.onComplete(new ModelChatClient.ChatResult(finalAnswer, Map.of("content", finalAnswer), Map.of(), 0L));
            }

            @Override
            public void onFailed(String code, String message) {
                clientCallback.onError(code, message);
            }
        }, true);
        return result;
    }

    public AgentRuntimeResult executeStreaming(AgentRuntimeRequest request, AgentRuntimeEventSink eventSink) {
        return executeAgentLoop(request, eventSink == null ? AgentRuntimeEventSink.noop() : eventSink, true);
    }

    private AgentRuntimeResult executeAgentLoop(AgentRuntimeRequest request, AgentRuntimeEventSink eventSink, boolean streamFinalAnswer) {
        Map<String, Object> config = expandAgentConfig(request);
        TenantModelAssignmentEntity assignment = resolveTenantModelAssignment(request.run().getTenantId());
        ModelProviderEntity provider = modelProviderRepository.findById(assignment.getProviderId())
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROVIDER_NOT_FOUND", "租户分配的模型供应商不存在"));
        if (!"active".equals(provider.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROVIDER_NOT_ACTIVE", "租户分配的模型供应商未启用");
        }

        String modelName = firstNonBlank(
            stringValue(config.get("modelName")),
            stringValue(config.get("model")),
            assignment.getDefaultModel(),
            provider.getDefaultModel()
        );
        if (modelName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_NAME_REQUIRED", "租户模型分配未配置默认模型");
        }

        List<McpRuntimeService.McpToolBinding> mcpTools = mcpRuntimeService.resolveMcpTools(new McpRuntimeRequest(
            request.run(),
            request.nodeRun(),
            config,
            request.variables(),
            request.operatorUserId()
        ));
        List<SkillRuntimeService.SkillToolBinding> skillTools = skillRuntimeService.resolveSkillTools(request.run().getTenantId(), config);
        List<ModelChatClient.ToolDefinition> toolDefinitions = buildToolDefinitions(mcpTools, skillTools);
        Map<String, McpRuntimeService.McpToolBinding> mcpToolByName = mcpTools.stream()
            .collect(java.util.stream.Collectors.toMap(McpRuntimeService.McpToolBinding::functionName, tool -> tool, (left, right) -> left, LinkedHashMap::new));
        Map<String, SkillRuntimeService.SkillToolBinding> skillToolByName = skillTools.stream()
            .collect(java.util.stream.Collectors.toMap(SkillRuntimeService.SkillToolBinding::functionName, tool -> tool, (left, right) -> left, LinkedHashMap::new));

        String systemPrompt = resolvePromptContent(request.run().getTenantId(), config, "systemPromptTemplateId", "systemPrompt",
            "你是 Agentum 平台中的业务智能体，请严格基于输入变量完成当前节点任务。");
        String userPrompt = resolvePromptContent(request.run().getTenantId(), config, "userPromptTemplateId", "userPrompt",
            firstNonBlank(stringValue(config.get("prompt")), "请基于上游变量和可用工具完成当前步骤，并输出结构化业务结论。"));
        String renderedSystemPrompt = buildAgentSystemPrompt(
            renderTemplate(systemPrompt, request.variables(), request.toolOutputs()),
            mcpTools,
            skillTools
        );
        String renderedUserPrompt = renderTemplate(userPrompt, request.variables(), request.toolOutputs()) +
            "\n\n<runtime_context>\n" +
            "上游变量 JSON：\n" + toJson(request.variables()) + "\n\n" +
            "已存在工具结果 JSON：\n" + toJson(request.toolOutputs()) + "\n" +
            "</runtime_context>";

        List<ModelChatClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new ModelChatClient.ChatMessage("system", renderedSystemPrompt));
        messages.add(new ModelChatClient.ChatMessage("user", renderedUserPrompt));

        Map<String, Object> options = modelOptions(provider, config);
        options.putIfAbsent("parallelToolCalls", false);
        ensureMaxTokensConfigured(options);
        int maxIterations = intValue(config.get("maxAgentIterations"), DEFAULT_MAX_AGENT_ITERATIONS);
        List<String> modelCallLogIds = new ArrayList<>();
        List<Map<String, Object>> toolCallSummaries = new ArrayList<>();
        String finalAnswer = "";
        eventSink.onPhase("preparing", "正在装配变量、Skill 和 MCP 工具。");

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                assertRunNotCancelled(request.run().getId());
                eventSink.onPhase("model_calling", iteration == 0 ? "正在让智能体规划下一步。" : "正在基于工具观察结果继续推理。");
                LoggedChatResult loggedResult = streamFinalAnswer
                    ? callModelStreamWithLog(request, provider, modelName, messages, options, toolDefinitions, eventSink)
                    : callModelWithLog(request, provider, modelName, messages, options, toolDefinitions);
                modelCallLogIds.add(loggedResult.callLogId());
                ModelChatClient.ChatResult result = loggedResult.result();

                String resolvedAnswer = resolveFinalAnswerContent(result, loggedResult.streamedDisplayText());
                if (!resolvedAnswer.isBlank()) {
                    finalAnswer = resolvedAnswer;
                    if (!streamFinalAnswer) {
                        emitFinalAnswer(eventSink, finalAnswer);
                    }
                    break;
                }

                List<ModelChatClient.ToolCall> executableToolCalls = result.toolCalls().stream()
                    .filter(toolCall -> !"final_answer".equals(toolCall.name()))
                    .toList();
                if (executableToolCalls.isEmpty()) {
                    finalAnswer = result.content();
                    if (!streamFinalAnswer) {
                        emitFinalAnswer(eventSink, finalAnswer);
                    }
                    break;
                }

                messages.add(ModelChatClient.ChatMessage.assistantToolCalls(result.content(), result.toolCalls()));
                eventSink.onPhase("tool_calling", "智能体已选择工具，正在执行并回写观察结果。");
                for (ModelChatClient.ToolCall toolCall : executableToolCalls) {
                    assertRunNotCancelled(request.run().getId());
                    ToolExecution toolExecution = executeToolCall(request, toolCall, mcpToolByName, skillToolByName, eventSink);
                    toolCallSummaries.add(toolExecution.summary());
                    messages.add(ModelChatClient.ChatMessage.toolResult(toolCall.id(), toolExecution.observation()));
                }
            }

            if (finalAnswer.isBlank()) {
                assertRunNotCancelled(request.run().getId());
                eventSink.onPhase("model_calling", "工具循环达到上限，正在汇总最终答案。");
                finalAnswer = synthesizeFinalAnswer(request, provider, modelName, messages, options, streamFinalAnswer, eventSink, modelCallLogIds);
            }
            eventSink.onPhase("validating", "正在校验最终输出格式。");
            Map<String, Object> outputs = buildOutputs(config, modelName, modelCallLogIds, toolCallSummaries, finalAnswer);
            eventSink.onPhase("completed", "智能体已完成最终回答。");
            eventSink.onCompleted(finalAnswer);
            return new AgentRuntimeResult(outputs);
        } catch (ApiException exception) {
            eventSink.onFailed(exception.getCode(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                "智能体 Agent loop 执行失败 tenantId={} runId={} nodeRunId={} model={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                modelName,
                RequestIds.current(),
                exception
            );
            eventSink.onFailed("AGENT_LOOP_FAILED", "智能体执行失败，请稍后重试");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AGENT_LOOP_FAILED", "智能体执行失败，请稍后重试");
        }
    }

    private List<ModelChatClient.ToolDefinition> buildToolDefinitions(
        List<McpRuntimeService.McpToolBinding> mcpTools,
        List<SkillRuntimeService.SkillToolBinding> skillTools
    ) {
        List<ModelChatClient.ToolDefinition> tools = new ArrayList<>();
        for (SkillRuntimeService.SkillToolBinding skillTool : skillTools) {
            tools.add(new ModelChatClient.ToolDefinition(
                skillTool.functionName(),
                "读取 Skill「" + skillTool.displayName() + "」的说明或附加资源。仅在需要理解该能力的使用方法时调用。",
                SkillRuntimeService.skillToolParameters()
            ));
        }
        for (McpRuntimeService.McpToolBinding mcpTool : mcpTools) {
            tools.add(new ModelChatClient.ToolDefinition(
                mcpTool.functionName(),
                "调用 MCP 能力「" + mcpTool.displayName() + "」。" + mcpTool.description(),
                mcpTool.parameters()
            ));
        }
        tools.add(new ModelChatClient.ToolDefinition(
            "final_answer",
            "提交完整最终答案。你必须把最终回复以 Markdown 写入 answer 字段，并把该工具作为最后一次工具调用。answer 请控制在 3000 字以内，避免超长被截断。",
            Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of(
                    "type", "string",
                    "description", "完整最终答案，使用 Markdown 格式"
                )),
                "required", List.of("answer")
            )
        ));
        return tools;
    }

    private ToolExecution executeToolCall(
        AgentRuntimeRequest request,
        ModelChatClient.ToolCall toolCall,
        Map<String, McpRuntimeService.McpToolBinding> mcpToolByName,
        Map<String, SkillRuntimeService.SkillToolBinding> skillToolByName,
        AgentRuntimeEventSink eventSink
    ) {
        Map<String, Object> arguments = parseJsonObject(toolCall.argumentsJson());
        if (mcpToolByName.containsKey(toolCall.name())) {
            McpRuntimeService.McpToolBinding binding = mcpToolByName.get(toolCall.name());
            eventSink.onToolCall(binding.displayName(), "mcp", "started", "", 0L);
            McpRuntimeService.ExecutedMcpTool result = mcpRuntimeService.executeResolvedTool(new McpRuntimeRequest(
                request.run(),
                request.nodeRun(),
                request.nodeConfig(),
                request.variables(),
                request.operatorUserId()
            ), binding, arguments);
            String observation = toJson(result.responsePayload());
            eventSink.onToolCall(binding.displayName(), "mcp", "completed", summarizeText(observation), result.latencyMs());
            return new ToolExecution(observation, Map.of(
                "toolName", binding.displayName(),
                "toolType", "mcp",
                "status", "completed",
                "summary", summarizeText(observation),
                "callLogId", result.callLogId().toString()
            ));
        }
        if (skillToolByName.containsKey(toolCall.name())) {
            SkillRuntimeService.SkillToolBinding binding = skillToolByName.get(toolCall.name());
            eventSink.onToolCall(binding.displayName(), "skill", "started", "", 0L);
            SkillRuntimeService.SkillReadResult result = skillRuntimeService.readSkill(binding, arguments);
            String observation = toJson(result.toMap());
            eventSink.onToolCall(binding.displayName(), "skill", "completed", "已读取 " + result.filePath(), 0L);
            return new ToolExecution(observation, Map.of(
                "toolName", binding.displayName(),
                "toolType", "skill",
                "status", "completed",
                "summary", "已读取 " + result.filePath()
            ));
        }
        String observation = "未知工具：" + toolCall.name();
        eventSink.onToolCall(toolCall.name(), "skill", "failed", observation, 0L);
        return new ToolExecution(observation, Map.of(
            "toolName", toolCall.name(),
            "toolType", "unknown",
            "status", "failed",
            "summary", observation
        ));
    }

    private LoggedChatResult callModelWithLog(
        AgentRuntimeRequest request,
        ModelProviderEntity provider,
        String modelName,
        List<ModelChatClient.ChatMessage> messages,
        Map<String, Object> options,
        List<ModelChatClient.ToolDefinition> tools
    ) {
        Map<String, Object> promptSnapshot = promptSnapshot(messages, request, tools);
        ModelCallLogEntity callLog = ModelCallLogEntity.started(
            request.run(),
            request.nodeRun(),
            provider.getId(),
            provider.getProviderType(),
            modelName,
            promptSnapshot,
            clock.instant()
        );
        modelCallLogRepository.save(callLog);
        try {
            ModelChatClient.ChatResult result = modelChatClient.chat(new ModelChatClient.ChatRequest(
                provider.getId(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                decryptApiKey(provider),
                modelName,
                messages,
                options,
                tools
            ));
            callLog.succeed(result.responseSnapshot(), result.tokenUsage(), result.latencyMs(), clock.instant());
            modelCallLogRepository.save(callLog);
            return new LoggedChatResult(result, callLogId(callLog), "");
        } catch (ApiException exception) {
            callLog.fail(exception.getCode(), exception.getMessage(), 0L, clock.instant());
            modelCallLogRepository.save(callLog);
            throw exception;
        }
    }

    /**
     * 运行态 SSE 场景下走模型流式接口，并把 content / final_answer 增量推送给前端。
     */
    private LoggedChatResult callModelStreamWithLog(
        AgentRuntimeRequest request,
        ModelProviderEntity provider,
        String modelName,
        List<ModelChatClient.ChatMessage> messages,
        Map<String, Object> options,
        List<ModelChatClient.ToolDefinition> tools,
        AgentRuntimeEventSink eventSink
    ) {
        Map<String, Object> promptSnapshot = promptSnapshot(messages, request, tools);
        ModelCallLogEntity callLog = ModelCallLogEntity.started(
            request.run(),
            request.nodeRun(),
            provider.getId(),
            provider.getProviderType(),
            modelName,
            promptSnapshot,
            clock.instant()
        );
        modelCallLogRepository.save(callLog);
        java.util.concurrent.CompletableFuture<ModelChatClient.ChatResult> future = new java.util.concurrent.CompletableFuture<>();
        StringBuilder displayText = new StringBuilder();
        modelChatClient.chatStream(new ModelChatClient.ChatRequest(
            provider.getId(),
            provider.getProviderType(),
            provider.getBaseUrl(),
            decryptApiKey(provider),
            modelName,
            messages,
            options,
            tools
        ), new ModelChatClient.StreamingCallback() {
            @Override
            public void onChunk(String deltaContent) {
                if (deltaContent == null || deltaContent.isEmpty()) {
                    return;
                }
                displayText.append(deltaContent);
                eventSink.onToken(deltaContent, displayText.toString());
            }

            @Override
            public void onFinalAnswerDelta(String deltaContent, String accumulatedAnswer) {
                displayText.setLength(0);
                displayText.append(accumulatedAnswer);
                eventSink.onToken(deltaContent, accumulatedAnswer);
            }

            @Override
            public void onComplete(ModelChatClient.ChatResult result) {
                callLog.succeed(result.responseSnapshot(), result.tokenUsage(), result.latencyMs(), clock.instant());
                modelCallLogRepository.save(callLog);
                future.complete(result);
            }

            @Override
            public void onError(String code, String message) {
                callLog.fail(code, message, 0L, clock.instant());
                modelCallLogRepository.save(callLog);
                future.completeExceptionally(new ApiException(HttpStatus.BAD_GATEWAY, code, message));
            }
        });
        try {
            ModelChatClient.ChatResult result = future.get();
            String streamedDisplay = displayText.toString();
            String resolvedAnswer = resolveFinalAnswerContent(result, streamedDisplay);
            if (streamedDisplay.isBlank() && !resolvedAnswer.isBlank()) {
                emitFinalAnswer(eventSink, resolvedAnswer);
            }
            return new LoggedChatResult(result, callLogId(callLog), streamedDisplay);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "模型流式调用失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STREAM_INTERRUPTED", "流式调用被中断");
        }
    }

    private String synthesizeFinalAnswer(
        AgentRuntimeRequest request,
        ModelProviderEntity provider,
        String modelName,
        List<ModelChatClient.ChatMessage> messages,
        Map<String, Object> options,
        boolean streamFinalAnswer,
        AgentRuntimeEventSink eventSink,
        List<String> modelCallLogIds
    ) {
        List<ModelChatClient.ChatMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new ModelChatClient.ChatMessage("user", "请基于以上推理和工具观察结果，生成完整最终答案。输出使用 Markdown，直接回答业务问题，不要暴露内部工具参数。"));
        if (!streamFinalAnswer) {
            LoggedChatResult result = callModelWithLog(request, provider, modelName, finalMessages, options, List.of());
            modelCallLogIds.add(result.callLogId());
            return result.result().content();
        }

        Map<String, Object> promptSnapshot = promptSnapshot(finalMessages, request, List.of());
        ModelCallLogEntity callLog = ModelCallLogEntity.started(
            request.run(),
            request.nodeRun(),
            provider.getId(),
            provider.getProviderType(),
            modelName,
            promptSnapshot,
            clock.instant()
        );
        modelCallLogRepository.save(callLog);
        java.util.concurrent.CompletableFuture<ModelChatClient.ChatResult> future = new java.util.concurrent.CompletableFuture<>();
        StringBuilder accumulated = new StringBuilder();
        modelChatClient.chatStream(new ModelChatClient.ChatRequest(
            provider.getId(),
            provider.getProviderType(),
            provider.getBaseUrl(),
            decryptApiKey(provider),
            modelName,
            finalMessages,
            options,
            List.of()
        ), new ModelChatClient.StreamingCallback() {
            @Override
            public void onChunk(String deltaContent) {
                accumulated.append(deltaContent);
                eventSink.onToken(deltaContent, accumulated.toString());
            }

            @Override
            public void onComplete(ModelChatClient.ChatResult result) {
                callLog.succeed(result.responseSnapshot(), result.tokenUsage(), result.latencyMs(), clock.instant());
                modelCallLogRepository.save(callLog);
                future.complete(result);
            }

            @Override
            public void onError(String code, String message) {
                callLog.fail(code, message, 0L, clock.instant());
                modelCallLogRepository.save(callLog);
                future.completeExceptionally(new ApiException(HttpStatus.BAD_GATEWAY, code, message));
            }
        });
        try {
            ModelChatClient.ChatResult result = future.get();
            modelCallLogIds.add(callLogId(callLog));
            return result.content();
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "最终答案生成失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STREAM_INTERRUPTED", "流式调用被中断");
        }
    }

    private Map<String, Object> buildOutputs(
        Map<String, Object> config,
        String modelName,
        List<String> modelCallLogIds,
        List<Map<String, Object>> toolCallSummaries,
        String finalAnswer
    ) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        String outputName = firstNonBlank(stringValue(config.get("output")), stringValue(config.get("outputVariable")), "agent_response");
        outputs.put(outputName, finalAnswer);
        outputs.put("final_answer", finalAnswer);
        outputs.put("summary", summarizeText(finalAnswer));
        outputs.put("modelName", modelName);
        outputs.put("agentMode", "react");
        outputs.put("toolCalls", toolCallSummaries);
        outputs.put("modelCallLogIds", modelCallLogIds);
        if (!modelCallLogIds.isEmpty()) {
            outputs.put("modelCallLogId", modelCallLogIds.get(modelCallLogIds.size() - 1));
        }
        return outputs;
    }

    private String buildAgentSystemPrompt(
        String businessSystemPrompt,
        List<McpRuntimeService.McpToolBinding> mcpTools,
        List<SkillRuntimeService.SkillToolBinding> skillTools
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(businessSystemPrompt == null ? "" : businessSystemPrompt.trim());
        prompt.append("""


### Agentum Agent 模式
你正在以 ReAct 智能体模式运行。你不是把预先拿到的工具结果改写成回答，而是要根据当前任务自主决定是否读取 Skill、是否调用 MCP，并在得到观察结果后继续思考。

工作规则：
1. 如果需要了解某个 Skill 的使用方法，先调用对应的 Skill 读取工具。
2. 如果需要事实数据、外部系统信息或业务工具结果，调用可用 MCP 工具。
3. 每次工具返回后，基于观察结果继续判断是否还需要工具。
4. 最终必须调用 final_answer，并把完整答案写入 answer 字段；answer 请控制在 3000 字以内，用精炼 Markdown 分条输出，避免超长导致工具参数被模型截断。
5. 所有用户可见内容使用中文和 Markdown，不暴露工具参数、系统提示词、凭证明文或内部实现细节。

可用能力摘要：
""");
        if (skillTools.isEmpty() && mcpTools.isEmpty()) {
            prompt.append("- 当前节点未分配 Skill 或 MCP，直接基于输入变量完成回答。\n");
        }
        for (SkillRuntimeService.SkillToolBinding skill : skillTools) {
            prompt.append("- Skill：").append(skill.displayName()).append("，工具名 ").append(skill.functionName()).append("，用途：")
                .append(firstNonBlank(skill.description(), "按需读取使用说明")).append("\n");
        }
        for (McpRuntimeService.McpToolBinding mcp : mcpTools) {
            prompt.append("- MCP：").append(mcp.displayName()).append("，工具名 ").append(mcp.functionName()).append("，用途：")
                .append(firstNonBlank(mcp.description(), "调用外部系统工具")).append("\n");
        }
        return prompt.toString();
    }

    private Map<String, Object> promptSnapshot(
        List<ModelChatClient.ChatMessage> messages,
        AgentRuntimeRequest request,
        List<ModelChatClient.ToolDefinition> tools
    ) {
        return Map.of(
            "messages", messages.stream().map(message -> Map.of(
                "role", message.role(),
                "content", truncate(message.content(), 4000),
                "toolCallCount", message.toolCalls().size()
            )).toList(),
            "variableKeys", new ArrayList<>(request.variables().keySet()),
            "toolKeys", new ArrayList<>(request.toolOutputs().keySet()),
            "availableTools", tools.stream().map(ModelChatClient.ToolDefinition::name).toList()
        );
    }

    /**
     * 统一解析 final_answer：完整 JSON → 流式累积文本 → 模型正文 → 截断 JSON 片段。
     * 流式场景下 answer 可能已在 SSE 推送中展示，但 ChatResult.content 为空且 tool 参数 JSON 被截断时须回读 streamedDisplayText。
     */
    String resolveFinalAnswerContent(ModelChatClient.ChatResult result, String streamedDisplayText) {
        if (result == null) {
            return "";
        }
        Optional<String> parsedFromTool = extractCompleteFinalAnswer(result.toolCalls());
        if (parsedFromTool.isPresent() && !parsedFromTool.get().isBlank()) {
            return parsedFromTool.get();
        }
        String streamed = firstNonBlank(streamedDisplayText);
        if (!streamed.isBlank()) {
            return streamed;
        }
        String content = firstNonBlank(result.content());
        if (!content.isBlank()) {
            return content;
        }
        for (ModelChatClient.ToolCall toolCall : result.toolCalls()) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            String partial = extractPartialAnswerFromTruncatedJson(toolCall.argumentsJson());
            if (!partial.isBlank()) {
                return partial;
            }
        }
        return "";
    }

    private Optional<String> extractFinalAnswer(List<ModelChatClient.ToolCall> toolCalls) {
        for (ModelChatClient.ToolCall toolCall : toolCalls) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            Optional<String> complete = extractCompleteFinalAnswer(List.of(toolCall));
            if (complete.isPresent()) {
                return complete;
            }
            String partial = extractPartialAnswerFromTruncatedJson(toolCall.argumentsJson());
            if (!partial.isBlank()) {
                log.info(
                    "final_answer 参数 JSON 不完整，已从截断内容中提取 answer requestId={}",
                    RequestIds.current()
                );
                return Optional.of(partial);
            }
            partial = extractPartialAnswerFromTruncatedJson(stringValue(parseFinalAnswerArguments(toolCall.argumentsJson()).get("raw")));
            if (!partial.isBlank()) {
                return Optional.of(partial);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 仅当 JSON 可完整解析或正则匹配到闭合字符串时返回 answer，避免截断 JSON 的短片段覆盖流式正文。 */
    private Optional<String> extractCompleteFinalAnswer(List<ModelChatClient.ToolCall> toolCalls) {
        for (ModelChatClient.ToolCall toolCall : toolCalls) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            String rawJson = toolCall.argumentsJson();
            Map<String, Object> args = looksLikeTruncatedFinalAnswerJson(rawJson)
                ? Map.of("raw", rawJson)
                : parseJsonObject(rawJson);
            String answer = stringValue(args.get("answer"));
            if (!answer.isBlank()) {
                return Optional.of(answer);
            }
            Matcher matcher = FINAL_ANSWER_FALLBACK_PATTERN.matcher(rawJson == null ? "" : rawJson);
            if (matcher.find()) {
                return Optional.of(unescapeJsonString(matcher.group(1)));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 从被截断的 final_answer JSON 中提取 answer 字符串值（即使没有闭合引号）。
     * 部分模型在超长输出时会在 tool_calls.arguments 处截断，导致 Jackson 解析 EOF。
     */
    static String extractPartialAnswerFromTruncatedJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "";
        }
        int answerKey = indexOfAnswerKey(rawJson);
        if (answerKey < 0) {
            return "";
        }
        int colon = rawJson.indexOf(':', answerKey);
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < rawJson.length() && Character.isWhitespace(rawJson.charAt(start))) {
            start++;
        }
        if (start >= rawJson.length()) {
            return "";
        }
        char quote = rawJson.charAt(start);
        if (quote != '"' && quote != '\'') {
            return "";
        }
        start++;
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = start; index < rawJson.length(); index++) {
            char current = rawJson.charAt(index);
            if (escaped) {
                appendEscapedChar(builder, current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                break;
            }
            builder.append(current);
        }
        return builder.toString().trim();
    }

    private static int indexOfAnswerKey(String rawJson) {
        int answerKey = rawJson.indexOf("\"answer\"");
        if (answerKey >= 0) {
            return answerKey;
        }
        return rawJson.indexOf("'answer'");
    }

    private static void appendEscapedChar(StringBuilder builder, char current) {
        switch (current) {
            case 'n' -> builder.append('\n');
            case 't' -> builder.append('\t');
            case 'r' -> builder.append('\r');
            default -> builder.append(current);
        }
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void emitFinalAnswer(AgentRuntimeEventSink eventSink, String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return;
        }
        eventSink.onToken(finalAnswer, finalAnswer);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        } catch (Exception exception) {
            log.debug("工具参数 JSON 解析失败，按原始文本保留 requestId={}", RequestIds.current(), exception);
        }
        return Map.of("raw", rawJson);
    }

    /** final_answer 专用：已知截断时不走 Jackson，避免无意义的 EOF 堆栈。 */
    private Map<String, Object> parseFinalAnswerArguments(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        if (looksLikeTruncatedFinalAnswerJson(rawJson)) {
            return Map.of("raw", rawJson);
        }
        return parseJsonObject(rawJson);
    }

    /**
     * GLM 等模型在 tool_calls.arguments 上存在约 3.4KB 硬上限，超长 answer 会在字符串中间被截断。
     */
    static boolean looksLikeTruncatedFinalAnswerJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        String trimmed = rawJson.trim();
        if (!trimmed.startsWith("{") || indexOfAnswerKey(trimmed) < 0) {
            return false;
        }
        return !trimmed.endsWith("}") && !trimmed.endsWith("\"}");
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private record LoggedChatResult(ModelChatClient.ChatResult result, String callLogId, String streamedDisplayText) {
    }

    private record ToolExecution(String observation, Map<String, Object> summary) {
    }

    public interface AgentRuntimeEventSink {
        default void onPhase(String phase, String message) {
        }

        default void onToken(String deltaContent, String accumulatedContent) {
        }

        default void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
        }

        default void onCompleted(String finalAnswer) {
        }

        default void onFailed(String code, String message) {
        }

        static AgentRuntimeEventSink noop() {
            return new AgentRuntimeEventSink() {
            };
        }
    }

    private TenantModelAssignmentEntity resolveTenantModelAssignment(UUID tenantId) {
        return tenantModelAssignmentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .filter(assignment -> "enabled".equals(assignment.getStatus()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "TENANT_MODEL_ASSIGNMENT_REQUIRED", "当前租户尚未分配可用模型"));
    }

    private Map<String, Object> expandAgentConfig(AgentRuntimeRequest request) {
        Map<String, Object> result = new HashMap<>();
        String agentAssetId = stringValue(request.nodeConfig().get("agentAssetId"));
        if (!agentAssetId.isBlank() && !"custom".equals(agentAssetId)) {
            parseUuid(agentAssetId).flatMap(id -> tenantAssetCapabilityRepository.findByIdAndTenantId(id, request.run().getTenantId()))
                .filter(asset -> "published".equals(asset.getStatus()) && "agent_template".equals(asset.getAssetType()))
                .map(TenantAssetCapabilityEntity::getConfig)
                .ifPresent(result::putAll);
        }
        result.putAll(request.nodeConfig());
        return result;
    }

    private String resolvePromptContent(UUID tenantId, Map<String, Object> config, String templateKey, String inlineKey, String fallback) {
        String templateId = stringValue(config.get(templateKey));
        if (!templateId.isBlank() && !"none".equals(templateId)) {
            Optional<UUID> uuid = parseUuid(templateId);
            if (uuid.isPresent()) {
                UUID id = uuid.get();
                Optional<SystemCapabilityEntity> systemPrompt = systemCapabilityRepository.findById(id)
                    .filter(capability -> "active".equals(capability.getStatus()) && "prompt_template".equals(capability.getCapabilityType()));
                if (systemPrompt.isPresent()) {
                    return firstNonBlank(stringValue(systemPrompt.get().getConfig().get("promptContent")), fallback);
                }
                Optional<TenantAssetCapabilityEntity> tenantPrompt = tenantAssetCapabilityRepository.findByIdAndTenantId(id, tenantId)
                    .filter(asset -> "published".equals(asset.getStatus()) && "prompt_template".equals(asset.getAssetType()));
                if (tenantPrompt.isPresent()) {
                    return firstNonBlank(stringValue(tenantPrompt.get().getConfig().get("promptContent")), fallback);
                }
            }
        }
        return firstNonBlank(stringValue(config.get(inlineKey)), fallback);
    }

    private Map<String, Object> modelOptions(ModelProviderEntity provider, Map<String, Object> config) {
        Map<String, Object> options = new HashMap<>(provider.getSettings() == null ? Map.of() : provider.getSettings());
        options.remove("encryptedApiKey");
        for (String key : List.of("temperature", "maxTokens", "maxCompletionTokens", "chatCompletionEndpoint", "apiVersion", "api-version", "chat_template_kwargs", "extraRequestBody", "top_p")) {
            if (config.containsKey(key) && config.get(key) != null && !stringValue(config.get(key)).isBlank()) {
                options.put(key, config.get(key));
            }
        }
        return options;
    }

    /**
     * maxTokens 必须由系统管理或节点配置显式提供，后端不再写死默认值，避免 silently 截断长输出。
     */
    private void ensureMaxTokensConfigured(Map<String, Object> options) {
        boolean hasMaxTokens = optionalPositiveInt(options.get("maxTokens")) != null;
        boolean hasMaxCompletionTokens = optionalPositiveInt(options.get("maxCompletionTokens")) != null;
        if (!hasMaxTokens && !hasMaxCompletionTokens) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_MAX_TOKENS_REQUIRED",
                "未配置最大输出 Token。请在系统管理 > 模型供应商中设置，或在智能体节点中单独指定 maxTokens。"
            );
        }
    }

    private static Integer optionalPositiveInt(Object value) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String decryptApiKey(ModelProviderEntity provider) {
        String encryptedApiKey = provider.getEncryptedApiKey();
        if (encryptedApiKey == null || encryptedApiKey.isBlank()) {
            return null;
        }
        return fieldEncryptionService.decrypt(encryptedApiKey);
    }

    private String renderTemplate(String template, Map<String, Object> variables, Map<String, Object> toolOutputs) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template == null ? "" : template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables.containsKey(key) ? variables.get(key) : toolOutputs.get(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String callLogId(ModelCallLogEntity callLog) {
        return callLog.getId().toString();
    }

    private static String summarizeText(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "智能体已完成模型调用。";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void assertRunNotCancelled(UUID runId) {
        if (cancellationRegistry.isCancelled(runId)) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_CANCELLED", "任务已中断");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
