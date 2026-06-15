package com.agentum.delivery.application;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将交付内容模板中的 {@code {{变量名}}} 替换为运行态变量值。
 *
 * <p>直接交付与能力交付的正文模板共用同一套替换规则，智能体输出会优先提取 final_answer 等常见字段。</p>
 */
@Component
public class DeliveryContentTemplateRenderer {

    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", objectToDisplayText(entry.getValue()));
            }
        }
        return result;
    }

    private String objectToDisplayText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("final_answer", "agent_response", "summary", "content", "text")) {
                Object nested = map.get(key);
                if (nested != null) {
                    return objectToDisplayText(nested);
                }
            }
            StringBuilder builder = new StringBuilder();
            map.forEach((key, item) -> builder.append("- **").append(key).append("**：").append(item == null ? "" : item).append("\n"));
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                builder.append("- ").append(item == null ? "" : item).append("\n");
            }
            return builder.toString();
        }
        return value.toString();
    }
}
