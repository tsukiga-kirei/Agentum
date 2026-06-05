package com.agentum.agent.application;

import com.agentum.agent.domain.ModelCallLogEntity;
import com.agentum.agent.infrastructure.ModelCallLogRepository;
import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
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

    private final TenantModelAssignmentRepository tenantModelAssignmentRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;
    private final FieldEncryptionService fieldEncryptionService;
    private final ModelCallLogRepository modelCallLogRepository;
    private final ModelChatClient modelChatClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentRuntimeService(
        TenantModelAssignmentRepository tenantModelAssignmentRepository,
        ModelProviderRepository modelProviderRepository,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository,
        FieldEncryptionService fieldEncryptionService,
        ModelCallLogRepository modelCallLogRepository,
        ModelChatClient modelChatClient,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.tenantModelAssignmentRepository = tenantModelAssignmentRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
        this.fieldEncryptionService = fieldEncryptionService;
        this.modelCallLogRepository = modelCallLogRepository;
        this.modelChatClient = modelChatClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
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

        String systemPrompt = resolvePromptContent(request.run().getTenantId(), config, "systemPromptTemplateId", "systemPrompt",
            "你是 Agentum 平台中的业务智能体，请严格基于输入变量完成当前节点任务。");
        String userPrompt = resolvePromptContent(request.run().getTenantId(), config, "userPromptTemplateId", "userPrompt",
            firstNonBlank(stringValue(config.get("prompt")), "请基于上游变量和工具结果完成当前步骤，并输出结构化业务结论。"));
        String renderedSystemPrompt = renderTemplate(systemPrompt, request.variables(), request.toolOutputs());
        String renderedUserPrompt = renderTemplate(userPrompt, request.variables(), request.toolOutputs()) +
            "\n\n上游变量：\n" + toJson(request.variables()) +
            "\n\nMCP 工具结果：\n" + toJson(request.toolOutputs());

        List<ModelChatClient.ChatMessage> messages = List.of(
            new ModelChatClient.ChatMessage("system", renderedSystemPrompt),
            new ModelChatClient.ChatMessage("user", renderedUserPrompt)
        );
        Map<String, Object> promptSnapshot = Map.of(
            "messages", messages.stream().map(message -> Map.of("role", message.role(), "content", truncate(message.content(), 4000))).toList(),
            "variableKeys", new ArrayList<>(request.variables().keySet()),
            "toolKeys", new ArrayList<>(request.toolOutputs().keySet())
        );
        Map<String, Object> options = modelOptions(provider, config);
        Instant now = clock.instant();
        ModelCallLogEntity callLog = ModelCallLogEntity.started(
            request.run(),
            request.nodeRun(),
            provider.getId(),
            provider.getProviderType(),
            modelName,
            promptSnapshot,
            now
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
                options
            ));
            callLog.succeed(result.responseSnapshot(), result.tokenUsage(), result.latencyMs(), clock.instant());
            modelCallLogRepository.save(callLog);
            Map<String, Object> outputs = new LinkedHashMap<>();
            String outputName = firstNonBlank(stringValue(config.get("output")), stringValue(config.get("outputVariable")), "agent_response");
            outputs.put(outputName, result.content());
            outputs.put("modelCallLogId", callLogId(callLog));
            outputs.put("modelName", modelName);
            outputs.put("summary", summarizeText(result.content()));
            return new AgentRuntimeResult(outputs);
        } catch (ApiException exception) {
            callLog.fail(exception.getCode(), exception.getMessage(), 0L, clock.instant());
            modelCallLogRepository.save(callLog);
            throw exception;
        } catch (RuntimeException exception) {
            callLog.fail("MODEL_CALL_FAILED", "模型调用失败", 0L, clock.instant());
            modelCallLogRepository.save(callLog);
            log.warn(
                "智能体模型执行失败 tenantId={} runId={} nodeRunId={} providerId={} model={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                provider.getId(),
                modelName,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "模型调用失败，请稍后重试");
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
        for (String key : List.of("temperature", "maxTokens", "maxCompletionTokens", "chatCompletionEndpoint", "apiVersion", "api-version")) {
            if (config.containsKey(key)) {
                options.put(key, config.get(key));
            }
        }
        return options;
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
