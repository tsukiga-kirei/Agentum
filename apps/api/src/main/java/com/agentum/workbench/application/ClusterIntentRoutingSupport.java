package com.agentum.workbench.application;

import com.agentum.shared.api.ApiException;
import com.agentum.workflow.application.WorkflowPromptDefaults;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * 智能体集群意图分派的本地支撑逻辑。
 *
 * <p>模型只负责把自然语言归类为设计时白名单里的 intentCode；真正执行哪个子智能体由平台配置映射，
 * 禁止模型直接返回 agentId 或动态指定未绑定能力，避免意图识别成为绕过治理边界的后门。</p>
 */
final class ClusterIntentRoutingSupport {

    static final String MODE_COLLABORATIVE = "collaborative";
    static final String MODE_RELAY = "relay";
    static final String MODE_INTENT = "intent";
    static final String SELECTION_SINGLE = "single";
    static final String SELECTION_MULTIPLE = "multiple";
    static final String FALLBACK_FAIL = "fail";
    static final String FALLBACK_AGENT = "agent";
    static final String FALLBACK_FIXED_REPLY = "fixed_reply";
    static final String DEFAULT_INTENT_OUTPUT_VARIABLE = "cluster_result";
    static final int DEFAULT_INTENT_ITERATIONS = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ClusterIntentRoutingSupport() {
    }

    static String normalizeExecutionMode(Object value) {
        String mode = rawString(value);
        return switch (mode) {
            case MODE_COLLABORATIVE, MODE_RELAY, MODE_INTENT -> mode;
            case "" -> MODE_COLLABORATIVE;
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "WORKFLOW_CLUSTER_EXECUTION_MODE_INVALID",
                "智能体集群执行方式不合法，仅支持协同处理、接力处理或意图分派"
            );
        };
    }

    static List<IntentRoute> intentRoutes(Map<String, Object> nodeConfig, List<Map<String, Object>> agentConfigs) {
        List<IntentRoute> configuredRoutes = configuredIntentRoutes(nodeConfig, agentConfigs);
        if (!configuredRoutes.isEmpty()) {
            return configuredRoutes;
        }
        List<IntentRoute> routes = new ArrayList<>();
        for (int index = 0; index < agentConfigs.size(); index++) {
            Map<String, Object> agent = agentConfigs.get(index);
            String code = rawString(agent.get("intentCode"));
            if (code.isBlank()) {
                continue;
            }
            String name = firstNonBlank(rawString(agent.get("intentName")), rawString(agent.get("name")), "子智能体 " + (index + 1));
            String description = firstNonBlank(rawString(agent.get("intentDescription")), name);
            routes.add(new IntentRoute(code, name, description, index));
        }
        return routes;
    }

    static List<IntentRoute> intentRoutes(List<Map<String, Object>> agentConfigs) {
        return intentRoutes(Map.of(), agentConfigs);
    }

    /**
     * 为意图分类器补充可读的上游变量快照，避免仅依赖 {{变量名}} 替换时因竞态或空值覆盖导致模型看不到关键输入。
     */
    static String enrichClassifierUserPrompt(String userPrompt, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return userPrompt == null ? "" : userPrompt.trim();
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (!WorkflowRuntimeVariableMerge.isBusinessVariableKey(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (WorkflowRuntimeVariableMerge.isBlankValue(value)) {
                continue;
            }
            lines.add("- " + key + ": " + WorkflowRuntimeVariableMerge.summarizeVariableValue(value));
        }
        StringBuilder prompt = new StringBuilder(userPrompt == null ? "" : userPrompt.trim());
        if (!lines.isEmpty()) {
            prompt.append("\n\n上游变量快照（供意图判断）：\n");
            prompt.append(String.join("\n", lines));
        }
        return prompt.toString();
    }

    static Map<String, Object> classifierConfig(
        Map<String, Object> nodeConfig,
        List<Map<String, Object>> agentConfigs,
        List<IntentRoute> routes
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> firstAgent = agentConfigs.isEmpty() ? Map.of() : agentConfigs.getFirst();
        config.put("agentSource", "custom");
        config.put("agentAssetId", "custom");
        config.put("systemPrompt", classifierSystemPrompt(nodeConfig));
        config.put("userPrompt", classifierUserPrompt(nodeConfig, routes));
        config.put("modelProviderId", firstNonBlank(rawString(nodeConfig.get("intentModelProviderId")), rawString(firstAgent.get("modelProviderId"))));
        config.put("modelName", firstNonBlank(rawString(nodeConfig.get("intentModelName")), rawString(firstAgent.get("modelName"))));
        config.put("enableThinking", booleanValue(nodeConfig.get("intentEnableThinking")));
        config.put("maxAgentIterationsPerTurn", DEFAULT_INTENT_ITERATIONS);
        config.put("output", "intent_router_result");
        config.put("outputVariable", "intent_router_result");
        config.put("skills", List.of());
        config.put("skillIds", List.of());
        config.put("mcpServices", List.of());
        config.put("mcpIds", List.of());
        return config;
    }

    static IntentDecision decide(
        Map<String, Object> nodeConfig,
        List<IntentRoute> routes,
        List<Map<String, Object>> agentConfigs,
        Map<String, Object> classifierOutput
    ) {
        String rawAnswer = firstNonBlank(
            rawString(classifierOutput.get("final_answer")),
            rawString(classifierOutput.get("agent_response")),
            rawString(classifierOutput.get("summary"))
        );
        Map<String, Object> payload = parseJsonPayload(rawAnswer);
        List<String> requestedCodes = requestedIntentCodes(payload);
        String reason = firstNonBlank(rawString(payload.get("reason")), "模型未返回命中原因");
        Map<String, Object> slots = mapValue(payload.get("slots"));

        String selectionMode = selectionMode(nodeConfig);
        String fallbackMode = fallbackMode(nodeConfig);

        Map<String, IntentRoute> routeByCode = new LinkedHashMap<>();
        for (IntentRoute route : routes) {
            routeByCode.put(route.code(), route);
        }

        Set<String> selectedCodes = new LinkedHashSet<>();
        boolean usedFallback = false;
        for (String requestedCode : requestedCodes) {
            if (routeByCode.containsKey(requestedCode)) {
                selectedCodes.add(requestedCode);
            }
            if (SELECTION_SINGLE.equals(selectionMode) && !selectedCodes.isEmpty()) {
                break;
            }
        }

        List<Integer> selectedIndexes = new ArrayList<>();
        for (IntentRoute route : routes) {
            if (selectedCodes.contains(route.code()) && !selectedIndexes.contains(route.agentIndex())) {
                selectedIndexes.add(route.agentIndex());
            }
        }
        String fixedReply = "";
        if (selectedIndexes.isEmpty()) {
            if (FALLBACK_AGENT.equals(fallbackMode)) {
                int fallbackAgentIndex = agentIndexById(agentConfigs, rawString(nodeConfig.get("fallbackAgentId")));
                if (fallbackAgentIndex >= 0) {
                    selectedIndexes.add(fallbackAgentIndex);
                    usedFallback = true;
                }
            } else if (FALLBACK_FIXED_REPLY.equals(fallbackMode)) {
                fixedReply = firstNonBlank(rawString(nodeConfig.get("fallbackReply")), "暂时无法判断该需求应该交给哪个智能体处理，请补充更明确的信息。");
                usedFallback = true;
            }
        }

        return new IntentDecision(
            List.copyOf(requestedCodes),
            List.copyOf(selectedCodes),
            List.copyOf(selectedIndexes),
            reason,
            slots,
            rawAnswer,
            usedFallback,
            fallbackMode,
            fixedReply
        );
    }

    static String selectionMode(Map<String, Object> nodeConfig) {
        String value = rawString(nodeConfig.get("intentSelectionMode"));
        return SELECTION_SINGLE.equals(value) ? SELECTION_SINGLE : SELECTION_MULTIPLE;
    }

    static String fallbackMode(Map<String, Object> nodeConfig) {
        String value = rawString(nodeConfig.get("intentFallbackMode"));
        return switch (value) {
            case FALLBACK_AGENT -> FALLBACK_AGENT;
            case FALLBACK_FIXED_REPLY -> FALLBACK_FIXED_REPLY;
            default -> FALLBACK_FAIL;
        };
    }

    private static String classifierSystemPrompt(Map<String, Object> nodeConfig) {
        return firstNonBlank(
            rawString(nodeConfig.get("intentSystemPrompt")),
            WorkflowPromptDefaults.DEFAULT_INTENT_SYSTEM_PROMPT
        );
    }

    private static String classifierUserPrompt(Map<String, Object> nodeConfig, List<IntentRoute> routes) {
        String configuredPrompt = firstNonBlank(
            rawString(nodeConfig.get("intentInputTemplate")),
            rawString(nodeConfig.get("intentUserPrompt")),
            SELECTION_SINGLE.equals(selectionMode(nodeConfig))
                ? WorkflowPromptDefaults.DEFAULT_INTENT_SINGLE_USER_PROMPT
                : WorkflowPromptDefaults.DEFAULT_INTENT_MULTIPLE_USER_PROMPT
        );
        StringBuilder prompt = new StringBuilder(configuredPrompt.trim());
        prompt.append("\n\n候选意图如下，只能从这些 intentCode 中选择：\n");
        for (IntentRoute route : routes) {
            prompt.append("- ")
                .append(route.code())
                .append("：")
                .append(route.name())
                .append("。")
                .append(route.description())
                .append("\n");
        }
        if (SELECTION_SINGLE.equals(selectionMode(nodeConfig))) {
            prompt.append("\n本节点是单意图模式：只能返回一个最匹配的 intentCode。");
        } else {
            prompt.append("\n本节点是多意图模式：如果多个候选意图都明确匹配，可以按匹配程度返回多个 intentCode；平台会按上方顺序执行并拼接结果。");
        }
        prompt.append("\n如果没有任何候选意图匹配，请返回空数组，不要自行创造新代码。");
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<IntentRoute> configuredIntentRoutes(Map<String, Object> nodeConfig, List<Map<String, Object>> agentConfigs) {
        Object rawRoutes = nodeConfig.get("intentRoutes");
        if (!(rawRoutes instanceof List<?> list)) {
            return List.of();
        }
        List<IntentRoute> routes = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (Object rawRoute : list) {
            if (!(rawRoute instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> route = new LinkedHashMap<>((Map<String, Object>) rawMap);
            String code = rawString(route.get("intentCode"));
            if (code.isBlank() || !seenCodes.add(code)) {
                continue;
            }
            int agentIndex = agentIndexById(agentConfigs, rawString(route.get("agentId")));
            if (agentIndex < 0) {
                agentIndex = integerValue(route.get("agentIndex"), -1);
            }
            if (agentIndex < 0 || agentIndex >= agentConfigs.size()) {
                continue;
            }
            Map<String, Object> agent = agentConfigs.get(agentIndex);
            String agentName = rawString(agent.get("name"));
            String name = firstNonBlank(rawString(route.get("intentName")), agentName, "意图 " + (routes.size() + 1));
            String description = firstNonBlank(rawString(route.get("intentDescription")), rawString(route.get("description")), name);
            routes.add(new IntentRoute(code, name, description, agentIndex));
        }
        return routes;
    }

    private static int agentIndexById(List<Map<String, Object>> agentConfigs, String agentId) {
        if (agentId.isBlank()) {
            return -1;
        }
        for (int index = 0; index < agentConfigs.size(); index++) {
            if (agentId.equals(rawString(agentConfigs.get(index).get("id")))) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> requestedIntentCodes(Map<String, Object> payload) {
        Object rawCodes = payload.get("intentCodes");
        if (rawCodes == null) {
            rawCodes = payload.get("intentCode");
        }
        if (rawCodes == null) {
            rawCodes = payload.get("intent");
        }
        List<String> result = new ArrayList<>();
        if (rawCodes instanceof List<?> list) {
            for (Object item : list) {
                String code = rawString(item);
                if (!code.isBlank()) {
                    result.add(code);
                }
            }
        } else {
            String code = rawString(rawCodes);
            if (!code.isBlank()) {
                result.add(code);
            }
        }
        return result;
    }

    private static Map<String, Object> parseJsonPayload(String rawAnswer) {
        String json = extractJsonObject(rawAnswer);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String extractJsonObject(String value) {
        String text = value == null ? "" : value.trim();
        text = text.replaceFirst("^(\\uFEFF|\\.\\.\\.|…)+", "").trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private static int positiveInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return value == null ? fallback : Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static String rawString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    record IntentRoute(String code, String name, String description, int agentIndex) {
    }

    record IntentDecision(
        List<String> requestedCodes,
        List<String> selectedCodes,
        List<Integer> selectedAgentIndexes,
        String reason,
        Map<String, Object> slots,
        String rawAnswer,
        boolean usedFallback,
        String fallbackMode,
        String fixedReply
    ) {
    }
}
