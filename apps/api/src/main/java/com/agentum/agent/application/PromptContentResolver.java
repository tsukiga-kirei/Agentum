package com.agentum.agent.application;

import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 统一解析智能体 system/user 提示词：仅使用流程设计中的提示词模板引用或内联配置，不做平台兜底填充。
 * 运行态执行与任务快照展示共用同一套解析规则，避免抽屉与模型实际输入不一致。
 */
@Service
public class PromptContentResolver {

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantAssetCapabilityRepository tenantAssetCapabilityRepository;

    public PromptContentResolver(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantAssetCapabilityRepository = tenantAssetCapabilityRepository;
    }

    public String resolveSystemPrompt(UUID tenantId, Map<String, Object> config) {
        String templateId = firstNonBlank(
            stringValue(config.get("systemPromptTemplateId")),
            stringValue(config.get("promptTemplateId"))
        );
        if (!templateId.isBlank() && !"none".equals(templateId)) {
            return resolveFromTemplateId(tenantId, templateId);
        }
        return stringValue(config.get("systemPrompt"));
    }

    public String resolveUserPrompt(UUID tenantId, Map<String, Object> config) {
        String templateId = stringValue(config.get("userPromptTemplateId"));
        if (!templateId.isBlank() && !"none".equals(templateId)) {
            return resolveFromTemplateId(tenantId, templateId);
        }
        return stringValue(config.get("userPrompt"));
    }

    /**
     * 将节点配置中的提示词解析为运行时可读正文，并写入快照供前端只读展示。
     */
    public Map<String, Object> enrichConfigSnapshot(UUID tenantId, String nodeType, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> enriched = new LinkedHashMap<>(config);
        if ("agent".equals(nodeType)) {
            enrichAgentPrompts(tenantId, enriched);
        } else if ("parallel_group".equals(nodeType)) {
            enrichClusterPrompts(tenantId, enriched);
        }
        return enriched;
    }

    private void enrichAgentPrompts(UUID tenantId, Map<String, Object> config) {
        String systemPrompt = resolveSystemPrompt(tenantId, config);
        String userPrompt = resolveUserPrompt(tenantId, config);
        config.put("resolvedSystemPrompt", systemPrompt);
        config.put("resolvedUserPrompt", userPrompt);
        config.put("systemPrompt", systemPrompt);
        config.put("userPrompt", userPrompt);
    }

    @SuppressWarnings("unchecked")
    private void enrichClusterPrompts(UUID tenantId, Map<String, Object> config) {
        Object rawAgents = config.get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agents.isEmpty()) {
            return;
        }
        List<Map<String, Object>> enrichedAgents = new ArrayList<>();
        for (Object rawAgent : agents) {
            if (!(rawAgent instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> agent = new LinkedHashMap<>((Map<String, Object>) rawMap);
            enrichAgentPrompts(tenantId, agent);
            enrichedAgents.add(agent);
        }
        config.put("clusterAgents", enrichedAgents);
    }

    private String resolveFromTemplateId(UUID tenantId, String templateId) {
        Optional<UUID> uuid = parseUuid(templateId);
        if (uuid.isEmpty()) {
            return "";
        }
        UUID id = uuid.get();
        Optional<SystemCapabilityEntity> systemPrompt = systemCapabilityRepository.findById(id)
            .filter(capability -> "active".equals(capability.getStatus()) && "prompt_template".equals(capability.getCapabilityType()));
        if (systemPrompt.isPresent()) {
            return stringValue(systemPrompt.get().getConfig().get("promptContent"));
        }
        Optional<TenantAssetCapabilityEntity> tenantPrompt = tenantAssetCapabilityRepository.findByIdAndTenantId(id, tenantId)
            .filter(asset -> "published".equals(asset.getStatus()) && "prompt_template".equals(asset.getAssetType()));
        if (tenantPrompt.isPresent()) {
            return stringValue(tenantPrompt.get().getConfig().get("promptContent"));
        }
        return "";
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
}
