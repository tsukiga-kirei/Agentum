package com.agentum.mcp.application;

import com.agentum.shared.api.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 工具结果策略由管理员配置；数据路径只做确定性读取，不猜测供应商字段含义。 */
public final class McpResultPolicy {
    private McpResultPolicy() {}
    public static ApiException error(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MCP_RESULT_INVALID", message);
    }
    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), v)); return result;
    }
    public static Object path(Object value, String path) {
        if (path == null || path.isBlank() || "$".equals(path)) return value;
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw error("结果路径仅支持对象字段路径，例如 $.data.rows");
        for (String part : normalized.split("\\.")) {
            if (!(value instanceof Map<?, ?> object) || !object.containsKey(part)) throw error("结果路径不存在：" + path);
            value = object.get(part);
        }
        return value;
    }
    public static int integer(Map<String, Object> config, String key, int fallback, int max) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) return fallback;
        try { int parsed = Integer.parseInt(value.toString()); if (parsed >= 1 && parsed <= max) return parsed; }
        catch (NumberFormatException ignored) { /* 统一给出中文配置错误。 */ }
        throw error(key + " 必须为 1～" + max + " 的整数");
    }
    public static Map<String, Object> validatePolicies(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>) || map(value).size() > 100) throw error("请按工具名称配置结果策略，最多 100 项");
        Map<String, Object> policies = map(value);
        Set<String> resultKeys = new HashSet<>();
        policies.forEach((tool, raw) -> {
            if (tool.isBlank() || tool.length() > 160 || !(raw instanceof Map<?, ?>)) throw error("工具结果策略格式不正确");
            Map<String, Object> p = map(raw);
            if (!Set.of("direct", "dataset", "auto").contains(Objects.toString(p.getOrDefault("mode", "direct")))) throw error("结果处理方式无效");
            if (!Set.of("summary", "head", "tail", "head_tail").contains(Objects.toString(p.getOrDefault("preview", "summary")))) throw error("模型预览方式无效");
            if (!Set.of("structuredContent", "response", "text_json").contains(Objects.toString(p.getOrDefault("source", "structuredContent")))) throw error("结果解析来源无效");
            for (String key : List.of("rowsPath", "totalPath", "hasMorePath")) if (p.containsKey(key) && !Objects.toString(p.get(key), "").matches("\\$|(?:\\$\\.)?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw error("结果路径格式不正确：" + key);
            String rowsPath = Objects.toString(p.getOrDefault("rowsPath", "$.rows"));
            if (!rowsPath.matches("\\$|(?:\\$\\.)?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw error("明细路径格式不正确");
            integer(p, "previewRows", 3, 100); integer(p, "maxRows", 100000, 100000);
            integer(p, "inlineBytes", 32000, 256000);
            if (p.containsKey("columns") && (!(p.get("columns") instanceof List<?> columns) || columns.size() > 100 || columns.stream().anyMatch(column -> !(column instanceof String text) || !text.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")))) throw error("结果字段应为有效字段路径，最多 100 项");
            if (p.containsKey("fields") && (!(p.get("fields") instanceof List<?> fields) || fields.size() > 100 || fields.stream().anyMatch(f -> !(f instanceof String) || f.toString().isBlank()))) throw error("模型可见字段必须为字段名列表");
            Map<String, Object> paging = map(p.get("pagination"));
            if (!paging.isEmpty()) {
                if (!Set.of("none", "page").contains(Objects.toString(paging.getOrDefault("type", "none")))) throw error("当前分页支持 none 或 page");
                integer(paging, "maxPages", 200, 1000); integer(paging, "pageSize", 500, 10000);
                for (String key : List.of("pageParam", "pageSizeParam")) if (paging.containsKey(key) && !paging.get(key).toString().matches("[A-Za-z_][A-Za-z0-9_]*")) throw error("分页参数必须为顶层字段名");
                if ("page".equals(paging.get("type")) && !p.containsKey("hasMorePath")) throw error("自动分页必须配置 hasMorePath");
            }
            if (!"direct".equals(p.getOrDefault("mode", "direct"))) {
                String key = Objects.toString(p.getOrDefault("datasetKey", tool));
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,159}")) {
                    throw error("同一 MCP 的结果名称须为有效标识符（仅支持英文字母、数字和下划线，且须以字母或下划线开头，不能包含中文）");
                }
                if (!resultKeys.add(key)) {
                    throw error("同一 MCP 的结果名称不能重复：" + key);
                }
            }
            if (p.containsKey("datasetKey") && !p.get("datasetKey").toString().matches("[A-Za-z_][A-Za-z0-9_]{0,99}")) {
                throw error("数据集标识只能使用英文字母、数字和下划线，且长度不超过 100 字符");
            }
        });
        return policies;
    }
    public static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list)) throw error("明细路径必须指向数组，路径错误不能视为空数据");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) throw error("明细中的每条记录必须为对象");
            var row = map(item);
            if (row.size() > 100 || row.keySet().stream().anyMatch(k -> k.length() > 128)) throw error("明细最多支持 100 个字段，字段名不能超过 128 字符");
            rows.add(row);
        }
        return rows;
    }
}
