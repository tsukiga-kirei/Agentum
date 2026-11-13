package com.agentum.mcp.application;

import com.agentum.mcp.domain.McpCallLogEntity;
import com.agentum.mcp.infrastructure.McpCallLogRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class McpRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeService.class);
    private static final Set<String> SENTINEL_VALUES = Set.of("", "none", "custom");

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final McpCallLogRepository mcpCallLogRepository;
    private final McpRuntimeClient mcpRuntimeClient;
    private final Clock clock;
    private final McpDatasetService datasets;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.agentum.runtime.cancel.RunCancellationGuard cancellation;

    public McpRuntimeService(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        McpCallLogRepository mcpCallLogRepository,
        McpRuntimeClient mcpRuntimeClient,
        Clock clock, McpDatasetService datasets, com.fasterxml.jackson.databind.ObjectMapper mapper,
        com.agentum.runtime.cancel.RunCancellationGuard cancellation
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.mcpCallLogRepository = mcpCallLogRepository;
        this.mcpRuntimeClient = mcpRuntimeClient;
        this.clock = clock; this.datasets = datasets; this.mapper = mapper; this.cancellation = cancellation;
    }

    public List<McpToolBinding> resolveMcpTools(McpRuntimeRequest request) {
        List<String> mcpIds = readStringList(request.nodeConfig(), "mcpIds", "mcpServices", "mcpId");
        if (mcpIds.isEmpty()) {
            return List.of();
        }
        List<McpToolBinding> bindings = new ArrayList<>();
        int bindingIndex = 0;
        for (String mcpId : mcpIds) {
            UUID capabilityId = parseUuid(mcpId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MCP_CAPABILITY_ID_INVALID", "MCP 能力 ID 不合法"));
            SystemCapabilityEntity capability = resolveCapability(request, capabilityId);
            String configuredToolName = firstNonBlank(
                stringValue(request.nodeConfig().get("toolName")),
                stringValue(request.nodeConfig().get("mcpToolName")),
                stringValue(capability.getConfig().get("defaultToolName")),
                stringValue(capability.getConfig().get("toolName"))
            );
            String transportType = firstNonBlank(stringValue(capability.getConfig().get("transport")), "sse");
            String endpointUrl = firstNonBlank(stringValue(capability.getConfig().get("endpointUrl")), stringValue(capability.getConfig().get("sseUrl")));
            // MCP 工具契约由远端服务控制，系统管理保存的 config.tools 只能用于预览，不能成为运行时真相。
            // Agent loop 每个模型推理回合都会进入这里，使新增、删除、重命名和 Schema 变化在本轮模型调用前生效。
            List<DiscoveredMcpTool> discoveredTools = discoverRuntimeTools(request, capability, transportType, endpointUrl);
            if (!configuredToolName.isBlank()) {
                DiscoveredMcpTool selected = discoveredTools.stream()
                    .filter(tool -> configuredToolName.equals(tool.name()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MCP_CONFIGURED_TOOL_NOT_FOUND",
                        "MCP 当前未提供已配置工具：" + configuredToolName
                    ));
                bindings.add(toBinding(capability, selected, transportType, endpointUrl, bindingIndex++));
                continue;
            }
            if (discoveredTools.isEmpty()) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MCP_TOOL_LIST_EMPTY",
                    "MCP 当前未提供可用工具"
                );
            }
            for (DiscoveredMcpTool tool : discoveredTools) {
                bindings.add(toBinding(capability, tool, transportType, endpointUrl, bindingIndex++));
            }
        }
        return bindings;
    }

    private List<DiscoveredMcpTool> discoverRuntimeTools(
        McpRuntimeRequest request,
        SystemCapabilityEntity capability,
        String transportType,
        String endpointUrl
    ) {
        try {
            McpRuntimeClient.ToolListResult result = mcpRuntimeClient.listTools(new McpRuntimeClient.ToolListRequest(
                capability.getId(),
                transportType,
                endpointUrl
            ));
            List<DiscoveredMcpTool> tools = result.tools().stream()
                .filter(tool -> !tool.name().isBlank())
                .map(tool -> new DiscoveredMcpTool(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
            log.info(
                "MCP 运行时工具发现成功 tenantId={} runId={} nodeRunId={} capabilityId={} toolCount={} latencyMs={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                capability.getId(),
                tools.size(),
                result.latencyMs(),
                RequestIds.current()
            );
            return tools;
        } catch (ApiException exception) {
            log.warn(
                "MCP 运行时工具发现失败 tenantId={} runId={} nodeRunId={} capabilityId={} errorCode={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                capability.getId(),
                exception.getCode(),
                RequestIds.current()
            );
            throw exception;
        }
    }

    private McpToolBinding toBinding(
        SystemCapabilityEntity capability,
        DiscoveredMcpTool tool,
        String transportType,
        String endpointUrl,
        int index
    ) {
        String description = firstNonBlank(tool.description(), capability.getDescription(), "调用租户已授权 MCP 能力");
        var policy = McpResultPolicy.map(McpResultPolicy.map(capability.getConfig().get("toolResultPolicies")).get(tool.name()));
        if (Set.of("dataset", "auto").contains(Objects.toString(policy.get("mode"), "direct"))) description += "。完整结果名称：cap_" + capability.getId().toString().replace('-', '_') + "." + Objects.toString(policy.getOrDefault("datasetKey", tool.name()));
        return new McpToolBinding(
            sanitizeToolName("mcp_" + capability.getCode() + "_" + tool.name() + "_" + index),
            capability.getId(),
            capability.getCode(),
            capability.getName() + " / " + tool.name(),
            description,
            tool.name(),
            transportType,
            endpointUrl,
            mcpToolParameters(tool.inputSchema())
        );
    }

    public ExecutedMcpTool executeResolvedTool(McpRuntimeRequest request, McpToolBinding binding, Map<String, Object> rawArguments) {
        SystemCapabilityEntity capability = resolveCapability(request, binding.capabilityId());
        String toolName = firstNonBlank(binding.remoteToolName());
        if (toolName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TOOL_NAME_REQUIRED", "MCP 节点未配置工具名称");
        }
        Map<String, Object> arguments = unwrapArguments(rawArguments);
        var initialPolicy = McpResultPolicy.map(McpResultPolicy.map(capability.getConfig().get("toolResultPolicies")).get(toolName));
        McpResultPolicy.validatePolicies(Map.of(toolName, initialPolicy));
        var paging = McpResultPolicy.map(initialPolicy.get("pagination"));
        if (!"direct".equals(initialPolicy.getOrDefault("mode", "direct")) && "page".equals(paging.get("type"))) {
            String pageParam = Objects.toString(paging.getOrDefault("pageParam", "page"));
            String sizeParam = Objects.toString(paging.getOrDefault("pageSizeParam", "pageSize"));
            if (arguments.containsKey(pageParam) && !"1".equals(arguments.get(pageParam).toString())) throw McpResultPolicy.error("完整数据采集必须从第一页开始");
            arguments.put(pageParam, 1);
            arguments.put(sizeParam, McpResultPolicy.integer(paging, "pageSize", 500, 10000));
        }
        Instant now = clock.instant();
        McpCallLogEntity callLog = McpCallLogEntity.started(request.run(), request.nodeRun(), capability, toolName, requestPayload(toolName, arguments), now);
        mcpCallLogRepository.save(callLog);
        try {
            String transportType = firstNonBlank(binding.transportType(), stringValue(capability.getConfig().get("transport")), "sse");
            String endpointUrl = firstNonBlank(binding.endpointUrl(), stringValue(capability.getConfig().get("endpointUrl")), stringValue(capability.getConfig().get("sseUrl")));
            McpRuntimeClient.ToolResult result = mcpRuntimeClient.callTool(new McpRuntimeClient.ToolCall(
                capability.getId(),
                transportType,
                endpointUrl,
                toolName,
                arguments
            ));
            if (Boolean.TRUE.equals(result.responsePayload().get("isError"))) {
                throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "MCP_TOOL_EXECUTION_FAILED",
                    firstNonBlank(stringValue(result.responsePayload().get("text")), "MCP 工具返回执行失败")
                );
            }
            Map<String, Object> sanitizedResponse = processResult(request, capability, binding, arguments, result, callLog.getId());
            callLog.succeed(sanitizedResponse, result.latencyMs(), clock.instant());
            mcpCallLogRepository.save(callLog);
            log.info(
                "MCP 工具调用成功 tenantId={} runId={} nodeRunId={} capabilityId={} toolName={} latencyMs={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                capability.getId(),
                toolName,
                result.latencyMs(),
                RequestIds.current()
            );
            return new ExecutedMcpTool(binding.functionName(), toolName, capability.getCode(), sanitizedResponse, result.latencyMs(), callLog.getId());
        } catch (ApiException exception) {
            callLog.fail(exception.getCode(), exception.getMessage(), clock.instant());
            mcpCallLogRepository.save(callLog);
            log.warn(
                "MCP 工具调用失败 tenantId={} runId={} nodeRunId={} capabilityId={} toolName={} errorCode={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                capability.getId(),
                toolName,
                exception.getCode(),
                RequestIds.current()
            );
            throw exception;
        }
    }

    /** 在进入日志、SSE 和模型消息之前处理整个响应，避免 structuredContent 或文本原文绕过预览策略。 */
    private Map<String, Object> processResult(McpRuntimeRequest request, SystemCapabilityEntity capability, McpToolBinding binding,
        Map<String, Object> arguments, McpRuntimeClient.ToolResult first, UUID callId) {
        var policy = McpResultPolicy.map(McpResultPolicy.map(capability.getConfig().get("toolResultPolicies")).get(binding.remoteToolName()));
        McpResultPolicy.validatePolicies(Map.of(binding.remoteToolName(), policy));
        String mode = stringValue(policy.getOrDefault("mode", "direct"));
        String requiredPath = "cap_" + capability.getId().toString().replace('-', '_') + "." + Objects.toString(policy.getOrDefault("datasetKey", binding.remoteToolName()));
        if ("auto".equals(mode) && com.agentum.agent.application.RequiredMcpResults.paths(request.nodeConfig()).contains(requiredPath)) mode = "dataset";
        Map<String, Object> response = sanitizeMap(first.responsePayload());
        int bytes = encodedSize(response);
        if ("direct".equals(mode) || ("auto".equals(mode) && bytes <= McpResultPolicy.integer(policy, "inlineBytes", 32000, 256000)
            && !policy.containsKey("fields") && !"page".equals(McpResultPolicy.map(policy.get("pagination")).get("type")))) {
            if (bytes > 256000) throw McpResultPolicy.error("MCP 完整结果超过 256 KB，请配置数据集模式");
            return response;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> paging = McpResultPolicy.map(policy.get("pagination"));
        boolean paginate = "page".equals(paging.get("type"));
        String pageParam = Objects.toString(paging.getOrDefault("pageParam", "page"));
        String sizeParam = Objects.toString(paging.getOrDefault("pageSizeParam", "pageSize"));
        if (paginate && arguments.containsKey(pageParam) && !"1".equals(arguments.get(pageParam).toString())) throw McpResultPolicy.error("全量采集必须从第一页开始");
        int maxPages = McpResultPolicy.integer(paging, "maxPages", 200, 1000);
        int maxRows = McpResultPolicy.integer(policy, "maxRows", 100000, 100000);
        Set<String> seenPages = new java.util.HashSet<>();
        Map<String, Object> current = response;
        Integer expectedTotal = null;
        int page = 1, sizeBytes = 0;
        Instant deadline = clock.instant().plusSeconds(120);
        while (true) {
            cancellation.assertExecutable(request.run().getId());
            Object root = decoded(current, policy);
            List<Map<String, Object>> items = McpResultPolicy.rows(McpResultPolicy.path(root, Objects.toString(policy.getOrDefault("rowsPath", "$.rows"))));
            if (policy.containsKey("totalPath")) {
                Object total = McpResultPolicy.path(root, policy.get("totalPath").toString());
                int parsed;
                try { parsed = Integer.parseInt(Objects.toString(total)); } catch (NumberFormatException ex) { throw McpResultPolicy.error("分页总数不是有效整数"); }
                if (parsed < 0 || (expectedTotal != null && expectedTotal != parsed)) throw McpResultPolicy.error("分页总数发生变化，请重新取数");
                expectedTotal = parsed;
            }
            boolean more = false;
            if (policy.containsKey("hasMorePath")) {
                Object value = McpResultPolicy.path(root, policy.get("hasMorePath").toString());
                if (!(value instanceof Boolean)) throw McpResultPolicy.error("hasMore 必须是布尔值");
                more = Boolean.TRUE.equals(value);
            }
            if (items.isEmpty() && more) throw McpResultPolicy.error("中间页为空但接口声明仍有下一页");
            if (!items.isEmpty() && paginate && !seenPages.add(pageHash(items))) throw McpResultPolicy.error("分页重复，已停止采集以避免重复明细");
            rows.addAll(items); sizeBytes += encodedSize(items);
            if (rows.size() > maxRows || sizeBytes > 20 * 1024 * 1024) throw McpResultPolicy.error("数据采集超过行数或 20 MB 上限");
            if (!more) break;
            if (!paginate) throw McpResultPolicy.error("接口返回尚有下一页，必须配置自动分页才能形成完整数据集");
            if (page >= maxPages || clock.instant().isAfter(deadline)) throw McpResultPolicy.error("数据采集超过页数或耗时上限，未保存不完整数据");
            Map<String, Object> next = new LinkedHashMap<>(arguments);
            next.put(pageParam, ++page); next.put(sizeParam, arguments.getOrDefault(sizeParam, McpResultPolicy.integer(paging, "pageSize", 500, 10000)));
            var result = mcpRuntimeClient.callTool(new McpRuntimeClient.ToolCall(capability.getId(), binding.transportType(), binding.endpointUrl(), binding.remoteToolName(), next));
            if (Boolean.TRUE.equals(result.responsePayload().get("isError"))) throw McpResultPolicy.error("MCP 分页调用失败，未保存不完整数据");
            current = sanitizeMap(result.responsePayload());
        }
        if (expectedTotal != null && rows.size() != expectedTotal) throw McpResultPolicy.error("已获取条数与接口总数不一致，未保存不完整数据");
        return datasets.save(request, capability.getId(), callId, Objects.toString(policy.getOrDefault("datasetKey", binding.remoteToolName())), rows, policy);
    }
    private Object decoded(Map<String, Object> response, Map<String, Object> policy) {
        String source = Objects.toString(policy.getOrDefault("source", "structuredContent"));
        if ("response".equals(source)) return response;
        if ("text_json".equals(source)) {
            Object content = response.get("content");
            if (!(content instanceof List<?> parts)) throw McpResultPolicy.error("MCP 响应没有文本内容");
            var texts = parts.stream().map(McpResultPolicy::map).filter(p -> "text".equals(p.get("type"))).toList();
            if (texts.size() != 1) throw McpResultPolicy.error("JSON 文本解析要求唯一 text 块，请使用结构化响应");
            try { return mapper.readValue(Objects.toString(texts.getFirst().get("text")), Object.class); }
            catch (java.io.IOException ex) { throw McpResultPolicy.error("工具文本不是完整 JSON"); }
        }
        if (!response.containsKey("structuredContent")) throw McpResultPolicy.error("MCP 响应缺少 structuredContent，请调整解析来源");
        return response.get("structuredContent");
    }
    private String pageHash(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException | java.io.IOException ex) { throw McpResultPolicy.error("分页校验失败"); }
    }
    private int encodedSize(Object value) {
        try { return mapper.writeValueAsBytes(value).length; } catch (java.io.IOException ex) { throw McpResultPolicy.error("工具响应无法序列化"); }
    }
    public Map<String, Object> datasetReferences(UUID nodeRunId, List<Map<String, Object>> calls) { return datasets.references(nodeRunId, calls.stream().map(c -> Objects.toString(c.get("callLogId"), "")).collect(java.util.stream.Collectors.toSet())); }
    public Map<String, Object> queryDataset(McpRuntimeRequest request, Map<String, Object> args) {
        Set<UUID> allowed = new java.util.HashSet<>();
        for (String id : readStringList(request.nodeConfig(), "mcpIds", "mcpServices", "mcpId")) {
            UUID capabilityId = parseUuid(id).orElseThrow(() -> McpResultPolicy.error("MCP 能力标识不合法"));
            resolveCapability(request, capabilityId); allowed.add(capabilityId);
        }
        return datasets.query(request, allowed, args);
    }

    private SystemCapabilityEntity resolveCapability(McpRuntimeRequest request, UUID capabilityId) {
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MCP_CAPABILITY_NOT_FOUND", "MCP 能力不存在"));
        if (!"active".equals(capability.getStatus()) || !"mcp".equals(capability.getCapabilityType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_CAPABILITY_NOT_ACTIVE", "MCP 能力未启用或类型不匹配");
        }
        boolean granted = tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(request.run().getTenantId(), capabilityId)
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .isPresent();
        if (!granted) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_CAPABILITY_NOT_ASSIGNED", "该 MCP 能力未分配给当前租户");
        }
        return capability;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((key, value) -> result.put(key, isSensitive(key) ? "***" : sanitizeValue(value)));
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String keyText = key == null ? "" : key.toString();
                sanitized.put(keyText, isSensitive(keyText) ? "***" : sanitizeValue(nestedValue));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapArguments(Map<String, Object> rawArguments) {
        Map<String, Object> safeArguments = rawArguments == null ? Map.of() : rawArguments;
        Object nested = safeArguments.get("arguments");
        if (nested instanceof Map<?, ?> nestedMap) {
            return new LinkedHashMap<>((Map<String, Object>) nestedMap);
        }
        return new LinkedHashMap<>(safeArguments);
    }

    private Map<String, Object> requestPayload(String toolName, Map<String, Object> arguments) {
        return Map.of(
            "toolName", toolName,
            "arguments", sanitizeMap(arguments == null ? Map.of() : arguments)
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mcpToolParameters(Map<String, Object> inputSchema) {
        if (inputSchema != null && !inputSchema.isEmpty()) {
            return new LinkedHashMap<>(inputSchema);
        }
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "arguments", Map.of(
                    "type", "object",
                    "description", "传递给 MCP tools/call 的 JSON 参数对象"
                )
            ),
            "required", List.of("arguments")
        );
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("authorization")
            || normalized.contains("credential")
            || normalized.contains("cookie")
            || normalized.contains("privatekey")
            || normalized.contains("private_key");
    }

    private static List<String> readStringList(Map<String, Object> config, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            Object value = config.get(key);
            if (value instanceof List<?> list) {
                list.stream().map(item -> item == null ? "" : item.toString().trim())
                    .filter(text -> !SENTINEL_VALUES.contains(text))
                    .forEach(result::add);
            } else {
                String text = value == null ? "" : value.toString().trim();
                if (!SENTINEL_VALUES.contains(text)) {
                    result.add(text);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
        return value == null ? "" : value.toString().trim();
    }

    private static String sanitizeToolName(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return sanitized.isBlank() ? "mcp_call" : sanitized;
    }

    public record McpToolBinding(
        String functionName,
        UUID capabilityId,
        String capabilityCode,
        String displayName,
        String description,
        String remoteToolName,
        String transportType,
        String endpointUrl,
        Map<String, Object> parameters
    ) {
        public McpToolBinding {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    private record DiscoveredMcpTool(String name, String description, Map<String, Object> inputSchema) {
    }

    public record ExecutedMcpTool(
        String functionName,
        String remoteToolName,
        String capabilityCode,
        Map<String, Object> responsePayload,
        long latencyMs,
        UUID callLogId
    ) {
        public ExecutedMcpTool {
            responsePayload = responsePayload == null ? Map.of() : Map.copyOf(responsePayload);
        }
    }
}
