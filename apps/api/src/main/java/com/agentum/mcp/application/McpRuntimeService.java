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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeService.class);

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

    public McpRuntimeResult executeConfiguredMcps(McpRuntimeRequest request) {
        List<String> mcpIds = readStringList(request.nodeConfig(), "mcpIds", "mcpServices", "mcpId");
        if (mcpIds.isEmpty()) {
            return new McpRuntimeResult(Map.of());
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> callSummaries = new ArrayList<>();
        for (String mcpId : mcpIds) {
            UUID capabilityId = parseUuid(mcpId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MCP_CAPABILITY_ID_INVALID", "MCP 能力 ID 不合法"));
            SystemCapabilityEntity capability = resolveCapability(request, capabilityId);
            String toolName = firstNonBlank(
                stringValue(request.nodeConfig().get("toolName")),
                stringValue(request.nodeConfig().get("mcpToolName")),
                stringValue(capability.getConfig().get("defaultToolName"))
            );
            Map<String, Object> arguments = resolveArguments(request);
            Map<String, Object> requestPayload = Map.of(
                "toolName", toolName,
                "arguments", sanitizeMap(arguments)
            );
            Instant now = clock.instant();
            McpCallLogEntity callLog = McpCallLogEntity.started(request.run(), request.nodeRun(), capability, toolName, requestPayload, now);
            mcpCallLogRepository.save(callLog);
            if (toolName.isBlank()) {
                String reason = "MCP 能力已选择，但节点未配置具体工具名称，运行时未发起外部调用。";
                callLog.skipped(reason, clock.instant());
                mcpCallLogRepository.save(callLog);
                callSummaries.add(Map.of("capabilityCode", capability.getCode(), "status", "skipped", "summary", reason, "logId", callLog.getId().toString()));
                continue;
            }
            try {
                McpRuntimeClient.ToolResult result = mcpRuntimeClient.callTool(new McpRuntimeClient.ToolCall(
                    capability.getId(),
                    stringValue(capability.getConfig().get("sseUrl")),
                    toolName,
                    arguments
                ));
                callLog.succeed(sanitizeMap(result.responsePayload()), result.latencyMs(), clock.instant());
                mcpCallLogRepository.save(callLog);
                String outputKey = firstNonBlank(stringValue(request.nodeConfig().get("mcpOutput")), capability.getCode());
                outputs.put(outputKey, result.responsePayload());
                callSummaries.add(Map.of(
                    "capabilityCode", capability.getCode(),
                    "toolName", toolName,
                    "status", "success",
                    "logId", callLog.getId().toString()
                ));
            } catch (ApiException exception) {
                callLog.fail(exception.getCode(), exception.getMessage(), 0L, clock.instant());
                mcpCallLogRepository.save(callLog);
                log.warn(
                    "MCP 运行调用失败 tenantId={} runId={} nodeRunId={} capabilityId={} toolName={} errorCode={} requestId={}",
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
        if (!callSummaries.isEmpty()) {
            outputs.put("mcpCalls", callSummaries);
            outputs.put("summary", "已完成 " + callSummaries.size() + " 个 MCP 能力处理。");
        }
        return new McpRuntimeResult(outputs);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArguments(McpRuntimeRequest request) {
        Object explicit = request.nodeConfig().get("toolArguments");
        if (explicit == null) {
            explicit = request.nodeConfig().get("arguments");
        }
        if (explicit instanceof Map<?, ?> map) {
            return renderMap((Map<String, Object>) map, request.variables());
        }
        return new LinkedHashMap<>(request.variables());
    }

    private Map<String, Object> renderMap(Map<String, Object> input, Map<String, Object> variables) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        input.forEach((key, value) -> rendered.put(key, value instanceof String text ? renderString(text, variables) : value));
        return rendered;
    }

    private String renderString(String value, Map<String, Object> variables) {
        String result = value == null ? "" : value;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue().toString());
        }
        return result;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, isSensitive(key) ? "***" : value));
        return result;
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
                    .filter(text -> !text.isBlank() && !"none".equals(text) && !"custom".equals(text))
                    .forEach(result::add);
            } else {
                String text = value == null ? "" : value.toString().trim();
                if (!text.isBlank() && !"none".equals(text) && !"custom".equals(text)) {
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
}
