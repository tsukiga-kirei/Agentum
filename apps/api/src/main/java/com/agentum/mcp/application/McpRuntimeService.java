package com.agentum.mcp.application;

import com.agentum.mcp.domain.McpCallLogEntity;
import com.agentum.mcp.infrastructure.McpCallLogRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpRuntimeService {

    private static final Set<String> SENTINEL_VALUES = Set.of("", "none", "custom");

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final McpCallLogRepository mcpCallLogRepository;
    private final McpRuntimeClient mcpRuntimeClient;
    private final Clock clock;

    public McpRuntimeService(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        McpCallLogRepository mcpCallLogRepository,
        McpRuntimeClient mcpRuntimeClient,
        Clock clock
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.mcpCallLogRepository = mcpCallLogRepository;
        this.mcpRuntimeClient = mcpRuntimeClient;
        this.clock = clock;
    }

    public List<McpToolBinding> resolveMcpTools(McpRuntimeRequest request) {
        List<String> mcpIds = readStringList(request.nodeConfig(), "mcpIds", "mcpServices", "mcpId");
        if (mcpIds.isEmpty()) {
            return List.of();
        }
        List<McpToolBinding> bindings = new ArrayList<>();
        int index = 0;
        for (String mcpId : mcpIds) {
            UUID capabilityId = parseUuid(mcpId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MCP_CAPABILITY_ID_INVALID", "MCP 能力 ID 不合法"));
            SystemCapabilityEntity capability = resolveCapability(request, capabilityId);
            String remoteToolName = firstNonBlank(
                stringValue(request.nodeConfig().get("toolName")),
                stringValue(request.nodeConfig().get("mcpToolName")),
                stringValue(capability.getConfig().get("defaultToolName")),
                stringValue(capability.getConfig().get("toolName"))
            );
            String suffix = remoteToolName.isBlank() ? "call" : remoteToolName;
            String transportType = firstNonBlank(stringValue(capability.getConfig().get("transport")), "sse");
            String endpointUrl = firstNonBlank(stringValue(capability.getConfig().get("endpointUrl")), stringValue(capability.getConfig().get("sseUrl")));
            bindings.add(new McpToolBinding(
                sanitizeToolName("mcp_" + capability.getCode() + "_" + suffix + "_" + index),
                capability.getId(),
                capability.getCode(),
                capability.getName(),
                firstNonBlank(capability.getDescription(), "调用租户已授权 MCP 能力"),
                remoteToolName,
                transportType,
                endpointUrl,
                endpointUrl,
                mcpToolParameters(capability)
            ));
            index++;
        }
        return bindings;
    }

    public ExecutedMcpTool executeResolvedTool(McpRuntimeRequest request, McpToolBinding binding, Map<String, Object> rawArguments) {
        SystemCapabilityEntity capability = resolveCapability(request, binding.capabilityId());
        String toolName = firstNonBlank(binding.remoteToolName(), capability.getCode());
        if (toolName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TOOL_NAME_REQUIRED", "MCP 节点未配置工具名称");
        }
        Map<String, Object> arguments = unwrapArguments(rawArguments);
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
            callLog.succeed(sanitizeMap(result.responsePayload()), result.latencyMs(), clock.instant());
            mcpCallLogRepository.save(callLog);
            return new ExecutedMcpTool(binding.functionName(), toolName, capability.getCode(), result.responsePayload(), result.latencyMs(), callLog.getId());
        } catch (ApiException exception) {
            callLog.fail(exception.getCode(), exception.getMessage(), 0L, clock.instant());
            mcpCallLogRepository.save(callLog);
            throw exception;
        }
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
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, isSensitive(key) ? "***" : value));
        return result;
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
    private Map<String, Object> mcpToolParameters(SystemCapabilityEntity capability) {
        Object schema = capability.getConfig().get("inputSchema");
        if (!(schema instanceof Map<?, ?>)) {
            schema = capability.getConfig().get("parameters");
        }
        if (schema instanceof Map<?, ?> map && !map.isEmpty()) {
            return new LinkedHashMap<>((Map<String, Object>) map);
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
        return normalized.contains("password") || normalized.contains("token") || normalized.contains("secret") || normalized.contains("apikey") || normalized.contains("api_key");
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
        @Deprecated
        String sseUrl,
        Map<String, Object> parameters
    ) {
        public McpToolBinding {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
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
