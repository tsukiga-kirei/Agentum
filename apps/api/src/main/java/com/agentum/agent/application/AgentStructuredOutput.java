package com.agentum.agent.application;

import com.agentum.delivery.template.StructuredContent;
import com.agentum.delivery.template.TemplateModels;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 额外对象变量采用 opt-in；原有 answer 和文本业务变量始终保持字符串。 */
public final class AgentStructuredOutput {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AgentStructuredOutput() {}
    public static Map<String, Object> config(Map<String, Object> config) {
        if (config == null) return Map.of();
        return config.get("structuredOutput") instanceof Map<?, ?> map ? JSON.convertValue(map, new TypeReference<>() {}) : Map.of();
    }
    public static boolean enabled(Map<String, Object> config) { return Boolean.TRUE.equals(config(config).get("enabled")); }
    public static String variable(Map<String, Object> config) { return Objects.toString(config(config).get("variable"), ""); }
    public static List<TemplateModels.Field> fields(Map<String, Object> config) { return JSON.convertValue(config(config).getOrDefault("fields", List.of()), new TypeReference<>() {}); }
    public static void validateConfig(Map<String, Object> config) {
        if (!enabled(config)) return;
        if (!variable(config).matches("[a-z][a-z0-9_]{0,79}") || Set.of("final_answer", "agent_response", "summary", "chatMessages", "candidate_answer", "structuredData", "structuredVariable").contains(variable(config))
            || variable(config).equals(config.get("output")) || variable(config).equals(config.get("outputVariable"))) throw StructuredContent.error("结构化结果变量必须唯一，且不能与文本输出或系统字段重名");
        StructuredContent.validateFields(fields(config));
        if (com.agentum.workflow.application.WorkflowRuntimeSystemVariables.descriptions().containsKey(variable(config))) throw StructuredContent.error("结构化结果变量不能与系统运行变量重名");
    }
    public static Map<String, Object> schema(Map<String, Object> config) { validateConfig(config); return StructuredContent.schema(fields(config)); }
    public static Map<String, Object> validate(Map<String, Object> config, Object raw) {
        Map<String, Object> data = raw instanceof String s ? StructuredContent.parse(s) : JSON.convertValue(raw, new TypeReference<>() {});
        if (data == null) throw StructuredContent.error("请在最终结果中提交 data 对象");
        StructuredContent.validate(data, fields(config));
        return data;
    }
    @SuppressWarnings("unchecked")
    public static void attach(Map<String, Object> config, Map<String, Object> outputs, Map<String, Object> data) {
        if (!enabled(config)) return;
        String variable = variable(config);
        outputs.put("structuredVariable", variable);
        outputs.put("structuredData", data);
        Object messages = outputs.get("chatMessages");
        if (messages instanceof List<?> list && !list.isEmpty() && list.getLast() instanceof Map<?, ?> last) {
            var message = (Map<String, Object>) last;
            message.put("structuredData", data);
        }
        // 追问结果只作为候选，正式对象与文本答案一起由用户采用，不能提前流入交付。
        if (config.containsKey("_formalAnswer")) {
            outputs.put("candidateStructuredData", data);
            Object formal = config.get("_formalStructuredData");
            outputs.put("structuredData", formal == null ? Map.of() : formal);
            outputs.put(variable, formal == null ? Map.of() : formal);
        } else outputs.put(variable, data);
    }
    public static void adopt(Map<String, Object> config, Map<String, Object> outputs, Object raw) {
        if (!enabled(config)) return;
        var data = validate(config, raw);
        outputs.put(variable(config), data); outputs.put("structuredData", data);
        outputs.put("structuredVariable", variable(config)); outputs.remove("candidateStructuredData");
    }
    public static void adoptForAnswer(Map<String, Object> config, Map<String, Object> outputs, String answer) {
        if (!enabled(config)) return;
        if (outputs.get("chatMessages") instanceof List<?> messages) {
            if (outputs.get("selectedResultMessageIndex") instanceof Number index && index.intValue() >= 0 && index.intValue() < messages.size()
                && messages.get(index.intValue()) instanceof Map<?, ?> message && answer.equals(message.get("content"))) {
                adopt(config, outputs, message.get("structuredData")); return;
            }
            for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i) instanceof Map<?, ?> message && answer.equals(message.get("content"))) {
                adopt(config, outputs, message.get("structuredData")); return;
            }
        }
        // 新模式手工修改业务结果时必须提交完整对象，不能只改展示文字却继续沿用旧业务数据。
        adopt(config, outputs, answer);
    }
    @SuppressWarnings("unchecked")
    public static String readableEditedAnswer(Map<String, Object> config, Map<String, Object> outputs, String answer) {
        if (!enabled(config)) return answer;
        var messages = outputs.get("chatMessages") instanceof List<?> list ? list : List.of();
        if (messages.stream().anyMatch(it -> it instanceof Map<?, ?> message && answer.equals(message.get("content")))) return answer;
        Map<String, Object> data = (Map<String, Object>) outputs.get(variable(config));
        StringBuilder display = new StringBuilder();
        for (var field : fields(config)) {
            display.append("### ").append(field.label() == null || field.label().isBlank() ? field.key() : field.label()).append("\n\n");
            Object value = data.get(field.key());
            if ("table".equals(field.type()) && value instanceof List<?> rows) {
                display.append("| ").append(String.join(" | ", field.columns().stream().map(it -> it.label() == null || it.label().isBlank() ? it.key() : it.label()).toList())).append(" |\n");
                display.append("| ").append(String.join(" | ", field.columns().stream().map(it -> "---").toList())).append(" |\n");
                for (var raw : rows) if (raw instanceof Map<?, ?> row) display.append("| ").append(String.join(" | ", field.columns().stream().map(it -> Objects.toString(row.get(it.key()), "").replace("|", "\\|").replace("\n", " ")).toList())).append(" |\n");
            } else if (value instanceof List<?> paragraphs) display.append(String.join("\n\n", paragraphs.stream().map(Object::toString).toList()));
            else display.append(Objects.toString(value, ""));
            display.append("\n\n");
        }
        String text = display.toString().strip();
        List<Object> next = new ArrayList<>(messages);
        next.add(Map.of("role", "assistant", "content", text, "structuredData", data));
        outputs.put("chatMessages", next);
        return text;
    }
}
