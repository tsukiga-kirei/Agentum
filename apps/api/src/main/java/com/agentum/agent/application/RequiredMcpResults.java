package com.agentum.agent.application;

import com.agentum.mcp.application.McpResultPolicy;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import java.util.*;

/** 模板交付声明的工具结果构成执行依赖；只有本次调用实际保存的结果才能满足依赖。 */
public final class RequiredMcpResults {
    private RequiredMcpResults() {}
    public static Map<String, Object> config(Map<String, Object> original, WorkflowNodeRunEntity source, Integer member,
                                              List<WorkflowNodeRunEntity> nodes) {
        Set<String> paths = new LinkedHashSet<>();
        String prefix = member == null ? "" : "member_" + member + ".";
        for (var node : nodes) {
            if (node.getSortOrder() <= source.getSortOrder() || !"delivery".equals(node.getNodeType())) continue;
            var config = node.getConfigSnapshot();
            if ("multiple".equals(config.get("deliveryConfigMode")) && config.get("deliveryItems") instanceof List<?> items) {
                for (var raw : items) {
                    var item = McpResultPolicy.map(raw);
                    if (!Boolean.FALSE.equals(item.get("enabled"))) collect(McpResultPolicy.map(item.get("config")), source.getNodeKey(), prefix, paths);
                }
            } else collect(config, source.getNodeKey(), prefix, paths);
        }
        var result = new LinkedHashMap<>(original);
        result.put("_requiredMcpResults", new ArrayList<>(paths));
        return result;
    }
    private static void collect(Map<String, Object> config, String source, String prefix, Set<String> paths) {
        if (!"file_template".equals(config.get("generationMode")) || "direct".equals(config.get("deliveryMode"))) return;
        if (!(config.get("templateBindings") instanceof List<?> bindings)) return;
        for (var raw : bindings) {
            var binding = McpResultPolicy.map(raw);
            if (Boolean.TRUE.equals(binding.get("constant")) || !source.equals(binding.get("sourceNodeId")) || !"mcp_datasets".equals(binding.get("variable"))) continue;
            String path = Objects.toString(binding.get("path"), "");
            if (prefix.isEmpty() ? path.startsWith("cap_") : path.startsWith(prefix)) paths.add(prefix.isEmpty() ? path : path.substring(prefix.length()));
        }
    }
    public static List<String> paths(Map<String, Object> config) {
        return config.get("_requiredMcpResults") instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
    }
    public static void validate(Map<String, Object> config, Map<String, Object> results) {
        for (String path : paths(config)) {
            Object value = com.agentum.delivery.template.DeliveryTemplateService.path(results, path);
            if (!(value instanceof Map<?, ?> ref) || !ref.containsKey("datasetId")) throw McpResultPolicy.error("交付依赖的 MCP 工具尚未产生完整结果：" + path + "。请执行对应取数工具后继续");
        }
    }
}
