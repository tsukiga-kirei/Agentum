package com.agentum.workflow.application;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存前补齐空白的自定义提示词，兼容旧草稿；新建节点创建时已写入 {@link WorkflowPromptDefaults} 默认值。
 */
public final class WorkflowNodeConfigNormalizer {

    private WorkflowNodeConfigNormalizer() {
    }

    public static List<WorkflowDraftApi.WorkflowNodeDraft> normalizeNodes(List<WorkflowDraftApi.WorkflowNodeDraft> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream().map(WorkflowNodeConfigNormalizer::normalizeNode).toList();
    }

    public static WorkflowDraftApi.WorkflowNodeDraft normalizeNode(WorkflowDraftApi.WorkflowNodeDraft node) {
        return new WorkflowDraftApi.WorkflowNodeDraft(
            node.nodeId(),
            node.nodeType(),
            node.name(),
            node.positionX(),
            node.positionY(),
            node.inputVariables(),
            node.outputVariables(),
            normalizeNodeConfig(node.nodeType(), node.config())
        );
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeNodeConfig(String nodeType, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(config);
        if ("agent".equals(nodeType)) {
            normalizeAgentConfig(normalized, WorkflowPromptDefaults.DEFAULT_USER_PROMPT);
            return normalized;
        }
        if ("parallel_group".equals(nodeType)) {
            Object rawAgents = normalized.get("clusterAgents");
            if (rawAgents instanceof List<?> agents) {
                List<Map<String, Object>> nextAgents = new ArrayList<>();
                for (Object rawAgent : agents) {
                    if (rawAgent instanceof Map<?, ?> rawMap) {
                        Map<String, Object> agent = new LinkedHashMap<>((Map<String, Object>) rawMap);
                        normalizeAgentConfig(agent, WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT);
                        nextAgents.add(agent);
                    }
                }
                normalized.put("clusterAgents", nextAgents);
            }
        }
        return normalized;
    }

    private static void normalizeAgentConfig(Map<String, Object> config, String defaultUserPrompt) {
        if (isCustomTemplate(config.get("systemPromptTemplateId"), config.get("promptTemplateId"))
            && rawString(config.get("systemPrompt")).isBlank()) {
            config.put("systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT);
        }
        if (isCustomTemplate(config.get("userPromptTemplateId"))
            && rawString(config.get("userPrompt")).isBlank()) {
            config.put("userPrompt", defaultUserPrompt);
        }
    }

    private static boolean isCustomTemplate(Object primaryTemplateId, Object... fallbackTemplateIds) {
        if (!isUnsetTemplate(primaryTemplateId)) {
            return false;
        }
        for (Object fallbackTemplateId : fallbackTemplateIds) {
            if (!isUnsetTemplate(fallbackTemplateId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnsetTemplate(Object value) {
        String templateId = rawString(value);
        return templateId.isBlank() || "none".equals(templateId);
    }

    private static String rawString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
