package com.agentum.mcp.application;

import java.util.Map;
import java.util.UUID;

public interface McpRuntimeClient {

    ToolResult callTool(ToolCall call);

    record ToolCall(UUID capabilityId, String sseUrl, String toolName, Map<String, Object> arguments) {
        public ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record ToolResult(Map<String, Object> responsePayload, long latencyMs) {
        public ToolResult {
            responsePayload = responsePayload == null ? Map.of() : Map.copyOf(responsePayload);
        }
    }
}
