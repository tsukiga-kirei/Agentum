package com.agentum.workbench.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentum.workflow.application.WorkflowPromptDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    static final String FALLBACK_INTENT = "fallback_intent";
    static final String DEFAULT_FALLBACK_INTENT_CODE = "other";
    static final String DEFAULT_INTENT_OUTPUT_VARIABLE = "cluster_result";
    static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.65d;
    static final int DEFAULT_INTENT_ITERATIONS = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ClusterIntentRoutingSupport() {
    }

    static String normalizeExecutionMode(Object value) {
        String mode = rawString(value);
        return switch (mode) {
            case "sequential" -> MODE_RELAY;
            case "parallel", "" -> MODE_COLLABORATIVE;
            case MODE_COLLABORATIVE, MODE_RELAY, MODE_INTENT -> mode;
            default -> MODE_COLLABORATIVE;
        };
    }

    static List<IntentRoute> intentRoutes(List<Map<String, Object>> agentConfigs) {
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
        config.put("maxAgentIterationsPerTurn", positiveInteger(nodeConfig.get("intentMaxAgentIterationsPerTurn"), DEFAULT_INTENT_ITERATIONS));
        config.put("output", "intent_router_result");
        config.put("outputVariable", "intent_router_result");
        config.put("skills", List.of());
        config.put("skillIds", List.of());
        config.put("mcpServices", List.of());
        config.put("mcpIds", List.of());
        return config;
    }

    static IntentDecision decide(Map<String, Object> nodeConfig, List<IntentRoute> routes, Map<String, Object> classifierOutput) {
        String rawAnswer = firstNonBlank(
            rawString(classifierOutput.get("final_answer")),
            rawString(classifierOutput.get("agent_response")),
            rawString(classifierOutput.get("summary"))
        );
        Map<String, Object> payload = parseJsonPayload(rawAnswer);
        List<String> requestedCodes = requestedIntentCodes(payload);
        double confidence = confidence(payload);
        String reason = firstNonBlank(rawString(payload.get("reason")), "模型未返回命中原因");
        Map<String, Object> slots = mapValue(payload.get("slots"));

        String selectionMode = selectionMode(nodeConfig);
        double threshold = confidenceThreshold(nodeConfig);
        String fallbackMode = fallbackMode(nodeConfig);
        String fallbackIntentCode = fallbackIntentCode(nodeConfig);

        Map<String, IntentRoute> routeByCode = new LinkedHashMap<>();
        for (IntentRoute route : routes) {
            routeByCode.put(route.code(), route);
        }

        Set<String> selectedCodes = new LinkedHashSet<>();
        boolean usedFallback = false;
        if (confidence >= threshold) {
            for (String requestedCode : requestedCodes) {
                if (routeByCode.containsKey(requestedCode)) {
                    selectedCodes.add(requestedCode);
                }
                if (SELECTION_SINGLE.equals(selectionMode) && !selectedCodes.isEmpty()) {
                    break;
                }
            }
        }

        if (selectedCodes.isEmpty() && FALLBACK_INTENT.equals(fallbackMode) && routeByCode.containsKey(fallbackIntentCode)) {
            selectedCodes.add(fallbackIntentCode);
            usedFallback = true;
        }

        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        for (String selectedCode : selectedCodes) {
            IntentRoute route = routeByCode.get(selectedCode);
            if (route != null) {
                selectedIndexes.add(route.agentIndex());
            }
        }

        return new IntentDecision(
            List.copyOf(requestedCodes),
            List.copyOf(selectedCodes),
            Set.copyOf(selectedIndexes),
            confidence,
            threshold,
            reason,
            slots,
            rawAnswer,
            usedFallback
        );
    }

    static String selectionMode(Map<String, Object> nodeConfig) {
        String value = rawString(nodeConfig.get("intentSelectionMode"));
        return SELECTION_MULTIPLE.equals(value) ? SELECTION_MULTIPLE : SELECTION_SINGLE;
    }

    static String fallbackMode(Map<String, Object> nodeConfig) {
        String value = rawString(nodeConfig.get("intentFallbackMode"));
        return FALLBACK_INTENT.equals(value) ? FALLBACK_INTENT : FALLBACK_FAIL;
    }

    static String fallbackIntentCode(Map<String, Object> nodeConfig) {
        return firstNonBlank(rawString(nodeConfig.get("fallbackIntentCode")), DEFAULT_FALLBACK_INTENT_CODE);
    }

    static double confidenceThreshold(Map<String, Object> nodeConfig) {
        Object value = nodeConfig.get("intentConfidenceThreshold");
        if (value instanceof Number number) {
            return clampConfidence(number.doubleValue());
        }
        try {
            return value == null ? DEFAULT_CONFIDENCE_THRESHOLD : clampConfidence(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException exception) {
            return DEFAULT_CONFIDENCE_THRESHOLD;
        }
    }

    private static String classifierSystemPrompt(Map<String, Object> nodeConfig) {
        return firstNonBlank(
            rawString(nodeConfig.get("intentSystemPrompt")),
            WorkflowPromptDefaults.DEFAULT_INTENT_SYSTEM_PROMPT
        );
    }

    private static String classifierUserPrompt(Map<String, Object> nodeConfig, List<IntentRoute> routes) {
        String configuredPrompt = firstNonBlank(
            rawString(nodeConfig.get("intentUserPrompt")),
            WorkflowPromptDefaults.DEFAULT_INTENT_USER_PROMPT
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
        prompt.append("\n如果没有任何候选意图匹配，请返回空数组或 fallback 配置中的意图代码，不要自行创造新代码。");
        return prompt.toString();
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

    private static double confidence(Map<String, Object> payload) {
        Object value = payload.get("confidence");
        if (value instanceof Number number) {
            return clampConfidence(number.doubleValue());
        }
        try {
            return value == null ? 0d : clampConfidence(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException exception) {
            return 0d;
        }
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

    private static double clampConfidence(double value) {
        if (!Double.isFinite(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
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
        Set<Integer> selectedAgentIndexes,
        double confidence,
        double threshold,
        String reason,
        Map<String, Object> slots,
        String rawAnswer,
        boolean usedFallback
    ) {
    }
}
