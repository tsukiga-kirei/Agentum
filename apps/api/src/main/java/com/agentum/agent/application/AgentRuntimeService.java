package com.agentum.agent.application;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.mcp.application.McpRuntimeRequest;
import com.agentum.mcp.application.McpRuntimeService;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import com.agentum.system.domain.ModelProviderEntity;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.\\-\\u4e00-\\u9fa5]+)\\s*}}");
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
    private final RunCancellationGuard cancellationGuard;
    private final PromptContentResolver promptContentResolver;

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
        RunCancellationGuard cancellationGuard,
        PromptContentResolver promptContentResolver
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
        this.cancellationGuard = cancellationGuard;
        this.promptContentResolver = promptContentResolver;
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
            .collect(Collectors.toMap(McpRuntimeService.McpToolBinding::functionName, tool -> tool, (left, right) -> left, LinkedHashMap::new));
        Map<String, SkillRuntimeService.SkillToolBinding> skillToolByName = skillTools.stream()
            .collect(Collectors.toMap(SkillRuntimeService.SkillToolBinding::functionName, tool -> tool, (left, right) -> left, LinkedHashMap::new));

        String systemPrompt = promptContentResolver.resolveSystemPrompt(request.run().getTenantId(), config);
        String userPrompt = promptContentResolver.resolveUserPrompt(request.run().getTenantId(), config);
        // 运行态只使用流程设计中的 system/user 提示词，并通过 {{变量名}} 替换上游变量；
        // Skill/MCP 通过 tools 声明暴露给模型，不再向提示词追加平台规则或整包 JSON 上下文。
        String renderedSystemPrompt = renderTemplate(systemPrompt, request.variables(), request.toolOutputs());
        String renderedUserPrompt = renderTemplate(userPrompt, request.variables(), request.toolOutputs());

        List<ModelChatClient.ChatMessage> messages = buildConversationMessages(
            config,
            renderedSystemPrompt,
            renderedUserPrompt
        );

        Map<String, Object> options = modelOptions(provider, config);
        options.putIfAbsent("parallelToolCalls", false);
        ensureMaxTokensConfigured(options);
        int maxIterations = intValue(config.get("maxAgentIterations"), DEFAULT_MAX_AGENT_ITERATIONS);
        List<String> modelCallLogIds = new ArrayList<>();
        List<Map<String, Object>> toolCallSummaries = new ArrayList<>();
        String finalAnswer = "";
        String finalModelContent = "";
        String finalAnswerSource = "";
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
                if (!firstNonBlank(result.content()).isBlank()) {
                    finalModelContent = result.content();
                }

                String resolvedAnswer = resolveFinalAnswerContent(result, loggedResult.streamedDisplayText());
                if (!resolvedAnswer.isBlank()) {
                    finalAnswer = resolvedAnswer;
                    finalAnswerSource = isFinalAnswerToolResult(result, loggedResult.streamedDisplayText()) ? "final_answer_tool" : "model_content";
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
                    finalAnswerSource = "model_content";
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
                finalAnswerSource = "synthesized";
                if (finalModelContent.isBlank()) {
                    finalModelContent = finalAnswer;
                }
            }
            eventSink.onPhase("validating", "正在校验最终输出格式。");
            Map<String, Object> outputs = buildOutputs(
                config,
                modelName,
                modelCallLogIds,
                toolCallSummaries,
                finalAnswer,
                finalAnswerSource,
                finalModelContent,
                renderedUserPrompt
            );
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
            "提交完整最终答案。将完整回复写入 answer 字段，并作为最后一次工具调用。",
            Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of(
                    "type", "string",
                    "description", "完整最终答案"
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
            try {
                McpRuntimeService.ExecutedMcpTool result = mcpRuntimeService.executeResolvedTool(new McpRuntimeRequest(
                    request.run(),
                    request.nodeRun(),
                    request.nodeConfig(),
                    request.variables(),
                    request.operatorUserId()
                ), binding, arguments);
                String observation = toJson(result.responsePayload());
                eventSink.onToolCall(binding.displayName(), "mcp", "completed", observation, result.latencyMs());
                return new ToolExecution(observation, Map.of(
                    "toolName", binding.displayName(),
                    "toolType", "mcp",
                    "status", "completed",
                    "summary", summarizeText(observation),
                    "detail", observation,
                    "callLogId", result.callLogId().toString()
                ));
            } catch (ApiException exception) {
                // MCP 协议可能以 HTTP 200 + isError 返回业务失败，必须明确推送失败事件，避免界面继续显示绿色完成态。
                eventSink.onToolCall(binding.displayName(), "mcp", "failed", exception.getMessage(), 0L);
                if (!isRecoverableMcpFailure(exception)) {
                    throw exception;
                }
                // 外部工具暂时不可用时把失败作为 observation 回写模型，允许模型换用其他工具或向用户解释缺失数据。
                String observation = toJson(Map.of(
                    "isError", true,
                    "errorCode", exception.getCode(),
                    "text", exception.getMessage()
                ));
                return new ToolExecution(observation, Map.of(
                    "toolName", binding.displayName(),
                    "toolType", "mcp",
                    "status", "failed",
                    "summary", exception.getMessage(),
                    "detail", observation
                ));
            }
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

    private boolean isRecoverableMcpFailure(ApiException exception) {
        return Set.of("MCP_TOOL_EXECUTION_FAILED", "MCP_CALL_FAILED").contains(exception.getCode());
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
            ModelChatClient.ChatResult result = modelChatClient.chat(buildModelChatRequest(
                request,
                provider,
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
        CompletableFuture<ModelChatClient.ChatResult> future = new CompletableFuture<>();
        StringBuilder displayText = new StringBuilder();
        StringBuilder streamedFinalAnswer = new StringBuilder();
        modelChatClient.chatStream(buildModelChatRequest(
            request,
            provider,
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
                eventSink.onModelContent(deltaContent, displayText.toString());
            }

            @Override
            public void onFinalAnswerDelta(String deltaContent, String accumulatedAnswer) {
                streamedFinalAnswer.setLength(0);
                streamedFinalAnswer.append(accumulatedAnswer == null ? "" : accumulatedAnswer);
                eventSink.onFinalAnswerContent(deltaContent, accumulatedAnswer);
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
                future.completeExceptionally(modelStreamException(code, message));
            }
        });
        try {
            ModelChatClient.ChatResult result = future.get();
            String streamedDisplay = streamedFinalAnswer.toString();
            String resolvedAnswer = resolveFinalAnswerContent(result, streamedDisplay);
            if (streamedDisplay.isBlank() && !resolvedAnswer.isBlank()) {
                emitFinalAnswer(eventSink, resolvedAnswer);
            }
            return new LoggedChatResult(result, callLogId(callLog), streamedDisplay);
        } catch (ExecutionException exception) {
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
        finalMessages.add(new ModelChatClient.ChatMessage("user", "请基于以上推理和工具观察结果，生成完整最终答案。"));
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
        CompletableFuture<ModelChatClient.ChatResult> future = new CompletableFuture<>();
        StringBuilder accumulated = new StringBuilder();
        modelChatClient.chatStream(buildModelChatRequest(
            request,
            provider,
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
                future.completeExceptionally(modelStreamException(code, message));
            }
        });
        try {
            ModelChatClient.ChatResult result = future.get();
            modelCallLogIds.add(callLogId(callLog));
            return result.content();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "最终答案生成失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STREAM_INTERRUPTED", "流式调用被中断");
        }
    }

    private List<ModelChatClient.ChatMessage> buildConversationMessages(
        Map<String, Object> config,
        String renderedSystemPrompt,
        String renderedUserPrompt
    ) {
        List<ModelChatClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new ModelChatClient.ChatMessage("system", renderedSystemPrompt));
        List<Map<String, Object>> history = readConversationHistory(config);
        if (history.isEmpty()) {
            messages.add(new ModelChatClient.ChatMessage("user", renderedUserPrompt));
            return messages;
        }
        for (Map<String, Object> turn : history) {
            String role = stringValue(turn.get("role"));
            String content = stringValue(turn.get("content"));
            if (("user".equals(role) || "assistant".equals(role)) && !content.isBlank()) {
                messages.add(new ModelChatClient.ChatMessage(role, content));
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readConversationHistory(Map<String, Object> config) {
        Object rawHistory = config == null ? null : config.get("conversationHistory");
        if (!(rawHistory instanceof List<?> history)) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : history) {
            if (item instanceof Map<?, ?> rawMap) {
                normalized.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
            }
        }
        return normalized;
    }

    private List<Map<String, Object>> buildChatMessages(
        Map<String, Object> config,
        String renderedUserPrompt,
        String finalAnswer,
        String finalAnswerSource,
        String modelContent,
        List<Map<String, Object>> toolCallSummaries
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> history = readConversationHistory(config);
        if (history.isEmpty()) {
            messages.add(Map.of("role", "user", "content", renderedUserPrompt));
        } else {
            messages.addAll(history);
        }
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", finalAnswer);
        List<Map<String, Object>> processSteps = buildChatMessageProcessSteps(modelContent, finalAnswerSource, toolCallSummaries);
        if (!processSteps.isEmpty()) {
            assistantMessage.put("processSteps", processSteps);
        }
        messages.add(assistantMessage);
        return messages;
    }

    private List<Map<String, Object>> buildChatMessageProcessSteps(
        String modelContent,
        String finalAnswerSource,
        List<Map<String, Object>> toolCallSummaries
    ) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> tool : toolCallSummaries == null ? List.<Map<String, Object>>of() : toolCallSummaries) {
            String toolType = stringValue(tool.get("toolType"));
            if (!"mcp".equals(toolType) && !"skill".equals(toolType)) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("kind", "tool");
            step.put("title", "skill".equals(toolType)
                ? "读取 Skill：" + stringValue(tool.get("toolName"))
                : "调用 MCP：" + stringValue(tool.get("toolName")));
            step.put("summary", stringValue(tool.get("summary")));
            step.put("status", "failed".equals(stringValue(tool.get("status"))) ? "error" : "done");
            step.put("detail", firstNonBlank(stringValue(tool.get("detail")), stringValue(tool.get("summary"))));
            step.put("toolType", toolType);
            steps.add(step);
        }
        boolean shouldKeepModelContentStep = !steps.isEmpty() || "final_answer_tool".equals(finalAnswerSource);
        if (shouldKeepModelContentStep && !firstNonBlank(modelContent).isBlank()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("kind", "model_output");
            step.put("title", "生成最终答案");
            step.put("summary", "可展开查看");
            step.put("status", "done");
            step.put("detail", modelContent);
            step.put("toolType", "model");
            steps.add(step);
        }
        return steps;
    }

    private Map<String, Object> buildOutputs(
        Map<String, Object> config,
        String modelName,
        List<String> modelCallLogIds,
        List<Map<String, Object>> toolCallSummaries,
        String finalAnswer,
        String finalAnswerSource,
        String modelContent,
        String renderedUserPrompt
    ) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        String outputName = firstNonBlank(stringValue(config.get("output")), stringValue(config.get("outputVariable")), "agent_response");
        outputs.put(outputName, finalAnswer);
        outputs.put("final_answer", finalAnswer);
        outputs.put("final_answer_source", firstNonBlank(finalAnswerSource, "model_content"));
        // 前端按 final_answer_source 判断 content/context 是过程步骤还是最终正文回退；
        // 字段本身需要稳定保留，避免刷新后丢失来源判断依据。
        if (!firstNonBlank(modelContent).isBlank()) {
            outputs.put("model_content", modelContent);
        }
        outputs.put("summary", summarizeText(finalAnswer));
        outputs.put("modelName", modelName);
        outputs.put("agentMode", "react");
        outputs.put("toolCalls", toolCallSummaries);
        outputs.put("modelCallLogIds", modelCallLogIds);
        outputs.put("chatMessages", buildChatMessages(config, renderedUserPrompt, finalAnswer, finalAnswerSource, modelContent, toolCallSummaries));
        if (!modelCallLogIds.isEmpty()) {
            outputs.put("modelCallLogId", modelCallLogIds.get(modelCallLogIds.size() - 1));
        }
        return outputs;
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
        return FinalAnswerContentResolver.resolve(result, streamedDisplayText, objectMapper);
    }

    private boolean isFinalAnswerToolResult(ModelChatClient.ChatResult result, String streamedDisplayText) {
        if (!firstNonBlank(streamedDisplayText).isBlank()) {
            return true;
        }
        if (result == null) {
            return false;
        }
        for (ModelChatClient.ToolCall toolCall : result.toolCalls()) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            Map<String, Object> arguments = parseJsonObject(toolCall.argumentsJson());
            if (!stringValue(arguments.get("answer")).isBlank()) {
                return true;
            }
            if (!FinalAnswerContentResolver.extractPartialAnswerFromTruncatedJson(toolCall.argumentsJson()).isBlank()) {
                return true;
            }
        }
        return false;
    }

    static String extractPartialAnswerFromTruncatedJson(String rawJson) {
        return FinalAnswerContentResolver.extractPartialAnswerFromTruncatedJson(rawJson);
    }

    static boolean looksLikeTruncatedFinalAnswerJson(String rawJson) {
        return FinalAnswerContentResolver.looksLikeTruncatedFinalAnswerJson(rawJson);
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

        default void onModelContent(String deltaContent, String accumulatedContent) {
            onToken(deltaContent, accumulatedContent);
        }

        default void onFinalAnswerContent(String deltaContent, String accumulatedContent) {
            onToken(deltaContent, accumulatedContent);
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
        // 取消信号与执行截止时间均存放在 Redis：用户中断抛 RUN_CANCELLED，超时抛 WORKBENCH_NODE_EXECUTION_TIMEOUT。
        cancellationGuard.assertExecutable(runId);
    }

    /**
     * 为运行态模型调用注入中断探测，供流式客户端在 readLine 阻塞期间主动断开 HTTP 连接。
     */
    private ModelChatClient.ChatRequest buildModelChatRequest(
        AgentRuntimeRequest request,
        ModelProviderEntity provider,
        String modelName,
        List<ModelChatClient.ChatMessage> messages,
        Map<String, Object> options,
        List<ModelChatClient.ToolDefinition> tools
    ) {
        UUID runId = request.run().getId();
        return new ModelChatClient.ChatRequest(
            provider.getId(),
            provider.getProviderType(),
            provider.getBaseUrl(),
            decryptApiKey(provider),
            modelName,
            messages,
            options,
            tools,
            () -> cancellationGuard.isCancelled(runId)
        );
    }

    private static ApiException modelStreamException(String code, String message) {
        HttpStatus status = "RUN_CANCELLED".equals(code) ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
        return new ApiException(status, code, message);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String normalizeForCompare(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
