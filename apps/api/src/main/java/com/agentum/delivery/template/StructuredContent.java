package com.agentum.delivery.template;

import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 新模式专用校验器：不使用旧文本输出的宽容截断解析，避免把残缺 JSON 当成业务结果。 */
public final class StructuredContent {
    private static final Set<String> TYPES = Set.of("text", "paragraphs", "markdown", "number", "date", "boolean", "table");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private StructuredContent() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String text) {
        String value = text == null ? "" : text.strip();
        // 只接受完整、唯一的外层围栏；禁止用截取首尾大括号的方式猜测结果。
        if (value.startsWith("```")) {
            if (!value.matches("(?s)^```(?:json)?\\s*\\R.*\\R```$")) throw error("JSON 代码围栏不完整");
            value = value.substring(value.indexOf('\n') + 1, value.lastIndexOf("```" )).strip();
        }
        if (value.length() > 1_000_000) throw error("结构化结果超过长度限制");
        try {
            Object parsed = JSON.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?>)) throw error("结构化结果必须是 JSON 对象");
            return (Map<String, Object>) parsed;
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw error("JSON 格式不正确，请返回完整对象，勿附加解释或省略号"); }
    }

    public static void validateFields(List<TemplateModels.Field> fields) { validateFields(fields, 0); }
    private static void validateFields(List<TemplateModels.Field> fields, int depth) {
        if (fields == null || fields.isEmpty() || fields.size() > 100 || depth > 1) throw error("请配置 1～100 个字段，表格仅支持一层数据列");
        Set<String> keys = new HashSet<>();
        for (var field : fields) {
            if (field.key() == null || !field.key().matches("[a-z][a-z0-9_]{0,79}") || !keys.add(field.key())) throw error("字段标识必须唯一，使用小写字母、数字和下划线");
            if (!TYPES.contains(field.type())) throw error("字段「" + field.key() + "」的内容类型不支持");
            if (depth > 0 && Set.of("table", "markdown", "paragraphs").contains(field.type())) throw error("表格数据列仅支持文字、数字、日期和布尔值");
            if (field.maxLength() != null && (field.maxLength() < 1 || field.maxLength() > 200000)) throw error("字段字数上限应为 1～200000");
            if (field.maxItems() != null && (field.maxItems() < 1 || field.maxItems() > 100000)) throw error("字段条目上限应为 1～100000");
            if (field.enumValues() != null && !field.enumValues().isEmpty() && (!"text".equals(field.type()) || field.enumValues().size() > 100 || field.enumValues().stream().anyMatch(it -> it == null || it.length() > 100))) throw error("枚举仅支持文字字段，最多 100 项，每项最多 100 字");
            if ("table".equals(field.type())) validateFields(field.columns(), depth + 1);
        }
    }

    public static void validate(Map<String, Object> data, List<TemplateModels.Field> fields) {
        validateFields(fields);
        validateObject(data, fields, "");
    }
    private static void validateObject(Map<?, ?> data, List<TemplateModels.Field> fields, String prefix) {
        Set<String> allowed = new HashSet<>();
        for (var field : fields) {
            allowed.add(field.key());
            Object value = data.get(field.key());
            String label = prefix + field.key();
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (field.required()) throw error("字段「" + label + "」不能为空");
                continue;
            }
            int maxLength = field.maxLength() == null ? 200000 : field.maxLength();
            int maxItems = field.maxItems() == null ? 1000 : field.maxItems();
            if (field.enumValues() != null && !field.enumValues().isEmpty() && !field.enumValues().contains(value)) throw error("字段「" + label + "」不在允许的选项中");
            switch (field.type()) {
                case "text", "markdown", "date" -> {
                    if (!(value instanceof String text)) throw error("字段「" + label + "」必须是文字");
                    if (text.length() > maxLength) throw error("字段「" + label + "」超过字数上限");
                    if ("date".equals(field.type())) {
                        try { java.time.LocalDate.parse(text); } catch (Exception ex) { throw error("字段「" + label + "」必须是 yyyy-MM-dd 日期"); }
                    }
                    if ("markdown".equals(field.type())) validateMarkdown(text, label);
                }
                case "number" -> { if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw error("字段「" + label + "」必须是有限数字"); }
                case "boolean" -> { if (!(value instanceof Boolean)) throw error("字段「" + label + "」必须是布尔值"); }
                case "paragraphs", "table" -> {
                    if (!(value instanceof List<?> list)) throw error("字段「" + label + "」必须是列表");
                    if (list.size() > maxItems || (field.required() && list.isEmpty())) throw error("字段「" + label + "」条目数不符合要求");
                    int length = 0;
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if ("paragraphs".equals(field.type())) {
                            if (!(item instanceof String text)) throw error("字段「" + label + "」的每段必须是文字");
                            length += text.length();
                        } else {
                            if (!(item instanceof Map<?, ?> row)) throw error("字段「" + label + "」每行必须是对象");
                            validateObject(row, field.columns(), label + "[" + (i + 1) + "].");
                        }
                    }
                    if (length > maxLength) throw error("字段「" + label + "」超过总字数上限");
                }
                default -> throw error("字段内容类型无效");
            }
        }
        if (data.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw error("结构化结果包含未声明字段");
    }

    public static void validateMarkdown(String text, String label) {
        if (text.contains("```") || text.matches("(?s).*(<[/a-zA-Z][^>]*>|!\\[[^]]*]\\().*"))
            throw error("字段「" + label + "」不支持 HTML、图片或代码块，请使用正文、标题、列表和表格");
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            if (lines[i + 1].strip().matches("\\|?\\s*:?-{3,}:?\\s*\\|.*")) {
                int count = tableCells(lines[i]).size();
                for (int j = i + 1; j < lines.length && lines[j].strip().startsWith("|"); j++)
                    if (tableCells(lines[j]).size() != count) throw error("字段「" + label + "」的 Markdown 表格列数不一致");
            }
        }
    }
    public static List<String> tableCells(String line) {
        String text = line.strip().replaceAll("^\\||\\|$", "");
        return Arrays.stream(text.split("\\|", -1)).map(String::strip).toList();
    }
    public static Map<String, Object> schema(List<TemplateModels.Field> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var field : fields) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("description", Objects.toString(field.description(), field.label() == null ? field.key() : field.label()));
            item.put("type", switch (field.type()) { case "number", "boolean" -> field.type(); case "table", "paragraphs" -> "array"; default -> "string"; });
            if (Set.of("text", "markdown", "date").contains(field.type()) && field.maxLength() != null) item.put("maxLength", field.maxLength());
            if ("table".equals(field.type())) item.put("items", schema(field.columns()));
            if ("paragraphs".equals(field.type())) item.put("items", Map.of("type", "string"));
            if (field.maxItems() != null && Set.of("table", "paragraphs").contains(field.type())) item.put("maxItems", field.maxItems());
            properties.put(field.key(), item);
            if (field.enumValues() != null && !field.enumValues().isEmpty()) item.put("enum", field.enumValues());
            if (field.required()) required.add(field.key());
        }
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
    public static ApiException error(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_TEMPLATE_CONTENT_INVALID", message); }
}
