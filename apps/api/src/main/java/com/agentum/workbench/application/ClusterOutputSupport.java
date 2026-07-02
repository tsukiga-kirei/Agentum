package com.agentum.workbench.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体集群最终输出的模板渲染工具。
 *
 * <p>意图分派下部分子智能体可能不会执行，因此合并模板中的子智能体占位必须按空值处理，
 * 让用户可以预先设计固定版式，而不是把未命中视为运行失败。</p>
 */
final class ClusterOutputSupport {

    static final String DEFAULT_OUTPUT_VARIABLE = "cluster_result";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private ClusterOutputSupport() {
    }

    static String outputVariable(Map<String, Object> nodeConfig) {
        String value = stringValue(nodeConfig.get("clusterOutputVariable"));
        if (value.isBlank() || !value.matches("[a-z][a-z0-9_]*")) {
            return DEFAULT_OUTPUT_VARIABLE;
        }
        return value;
    }

    static String finalAnswer(Map<String, Object> nodeConfig, Map<String, Object> variables, List<Map<String, Object>> summaries) {
        String template = firstNonBlank(stringValue(nodeConfig.get("mergeRule")), defaultMergeRule(summaries));
        Map<String, Object> context = new LinkedHashMap<>();
        if (summaries != null) {
            for (Map<String, Object> summary : summaries) {
                String outputVariable = stringValue(summary.get("outputVariable"));
                if (!outputVariable.isBlank()) {
                    context.put(outputVariable, firstNonBlank(
                        stringValue(summary.get("final_answer")),
                        stringValue(summary.get("summary")),
                        ""
                    ));
                }
            }
        }
        String rendered = renderTemplate(template, context).trim();
        if (!rendered.isBlank()) {
            return rendered;
        }
        return renderTemplate(defaultMergeRule(summaries), context).trim();
    }

    private static String defaultMergeRule(List<Map<String, Object>> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "智能体集群未生成子智能体结论。";
        }
        StringBuilder result = new StringBuilder("## 智能体集群结论\n");
        for (Map<String, Object> summary : summaries) {
            String name = firstNonBlank(stringValue(summary.get("name")), "子智能体");
            String outputVariable = stringValue(summary.get("outputVariable"));
            result.append("\n### ")
                .append(name)
                .append("\n")
                .append(outputVariable.isBlank() ? "" : "{{" + outputVariable + "}}")
                .append("\n");
        }
        return result.toString();
    }

    private static String renderTemplate(String template, Map<String, Object> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template == null ? "" : template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
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
        return value == null ? "" : String.valueOf(value).trim();
    }
}
