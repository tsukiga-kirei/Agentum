package com.agentum.workflow.application;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存前补齐空白的自定义提示词；新建节点创建时已写入 {@link WorkflowPromptDefaults} 默认值。
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
        Map<String, Object> normalizedConfig = normalizeNodeConfig(node.nodeType(), node.config());
        if ("agent".equals(node.nodeType())) {
            normalizedConfig = syncAgentOutputVariable(normalizedConfig, node.outputVariables());
        }
        return new WorkflowDraftApi.WorkflowNodeDraft(
            node.nodeId(),
            node.nodeType(),
            node.name(),
            node.positionX(),
            node.positionY(),
            node.inputVariables(),
            node.outputVariables(),
            normalizedConfig
        );
    }

    /**
     * 单智能体节点的输出标识保存在 outputVariables 中，运行态 Agent 却从 config.output 读取；
     * 保存时同步一份，避免模板 {{agent}} 与运行时 agent_response 对不上。
     */
    private static Map<String, Object> syncAgentOutputVariable(Map<String, Object> config, List<String> outputVariables) {
        if (outputVariables == null || outputVariables.isEmpty()) {
            return config;
        }
        String outputName = rawString(outputVariables.get(0));
        if (outputName.isBlank()) {
            return config;
        }
        Map<String, Object> synced = new LinkedHashMap<>(config == null ? Map.of() : config);
        synced.put("output", outputName);
        synced.put("outputVariable", outputName);
        return synced;
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
            normalized.put("executionMode", normalizeClusterExecutionMode(normalized.get("executionMode")));
            normalized.putIfAbsent("clusterOutputVariable", "cluster_result");
            if ("intent".equals(normalized.get("executionMode"))) {
                normalized.putIfAbsent("intentSelectionMode", "multiple");
                normalized.putIfAbsent("intentFallbackMode", "fail");
                normalized.putIfAbsent("intentRoutes", List.of());
                normalized.putIfAbsent("intentInputTemplate", "");
            }
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
                if (rawString(normalized.get("mergeRule")).isBlank()) {
                    normalized.put("mergeRule", defaultClusterMergeRule(nextAgents));
                }
            }
        }
        if ("delivery".equals(nodeType)) {
            normalizeDeliveryConfig(normalized);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static void normalizeDeliveryConfig(Map<String, Object> config) {
        String deliveryMode = rawString(config.get("deliveryMode"));
        if (deliveryMode.isBlank()) {
            deliveryMode = "direct";
            config.put("deliveryMode", deliveryMode);
        }
        String configMode = "multiple".equals(rawString(config.get("deliveryConfigMode"))) ? "multiple" : "single";
        config.put("deliveryConfigMode", configMode);
        if (!"multiple".equals(configMode)) {
            config.put("deliveryItems", List.of());
            return;
        }
        config.put("deliveryExecutionPolicy", "conditional".equals(rawString(config.get("deliveryExecutionPolicy"))) ? "conditional" : "all");
        Object rawItems = config.get("deliveryItems");
        if (!(rawItems instanceof List<?> items)) {
            config.put("deliveryItems", List.of());
            return;
        }
        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Object rawItem = items.get(index);
            if (rawItem instanceof Map<?, ?> rawMap) {
                Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) rawMap);
                item.putIfAbsent("id", "delivery_item_" + (index + 1));
                if (rawString(item.get("name")).isBlank()) {
                    item.put("name", "交付项 " + (index + 1));
                }
                item.putIfAbsent("enabled", true);
                item.put("triggerRule", normalizeDeliveryTriggerRule(item.get("triggerRule")));
                Object rawConfig = item.get("config");
                Map<String, Object> itemConfig = rawConfig instanceof Map<?, ?> itemConfigMap
                    ? new LinkedHashMap<>((Map<String, Object>) itemConfigMap)
                    : new LinkedHashMap<>();
                if (rawString(itemConfig.get("deliveryMode")).isBlank()) {
                    itemConfig.put("deliveryMode", inferDeliveryMode(itemConfig, deliveryMode));
                }
                item.put("config", itemConfig);
                normalizedItems.add(item);
            }
        }
        config.put("deliveryItems", normalizedItems);
    }

    private static String inferDeliveryMode(Map<String, Object> config, String parentDeliveryMode) {
        String deliveryType = rawString(config.get("deliveryType"));
        String capabilityId = rawString(config.get("deliveryCapabilityId"));
        if ("direct".equalsIgnoreCase(deliveryType) || "none".equalsIgnoreCase(capabilityId) || "custom".equalsIgnoreCase(capabilityId)) {
            return "direct";
        }
        if (!capabilityId.isBlank()) {
            return "capability";
        }
        return rawString(parentDeliveryMode).isBlank() ? "direct" : parentDeliveryMode;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeDeliveryTriggerRule(Object value) {
        Map<String, Object> source = value instanceof Map<?, ?> rawMap
            ? new LinkedHashMap<>((Map<String, Object>) rawMap)
            : new LinkedHashMap<>();
        String type = rawString(source.get("type"));
        if (!"cluster_agent_matched".equals(type)) {
            type = "always";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("clusterNodeId", rawString(source.get("clusterNodeId")));
        result.put("agentId", rawString(source.get("agentId")));
        result.put("variableName", rawString(source.get("variableName")));
        return result;
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

    private static String normalizeClusterExecutionMode(Object value) {
        String mode = rawString(value);
        return switch (mode) {
            case "collaborative", "relay", "intent" -> mode;
            case "" -> "collaborative";
            default -> mode;
        };
    }

    private static String rawString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String defaultClusterMergeRule(List<Map<String, Object>> agents) {
        if (agents == null || agents.isEmpty()) {
            return "## 智能体集群结论";
        }
        StringBuilder builder = new StringBuilder("## 智能体集群结论\n");
        for (int index = 0; index < agents.size(); index++) {
            Map<String, Object> agent = agents.get(index);
            String name = firstNonBlank(rawString(agent.get("name")), "子智能体 " + (index + 1));
            String output = firstNonBlank(rawString(agent.get("output")), "agent_" + (index + 1) + "_output");
            builder.append("\n### ")
                .append(name)
                .append("\n{{")
                .append(output)
                .append("}}\n");
        }
        return builder.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
