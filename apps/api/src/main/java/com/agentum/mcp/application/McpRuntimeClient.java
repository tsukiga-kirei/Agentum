package com.agentum.mcp.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface McpRuntimeClient {

    ToolListResult listTools(ToolListRequest request);

    ToolResult callTool(ToolCall call);

    record ToolListRequest(UUID capabilityId, String transportType, String endpointUrl) {
    }

    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
        public ToolDescriptor {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    record ToolListResult(List<ToolDescriptor> tools, long latencyMs) {
        public ToolListResult {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record ToolCall(UUID capabilityId, String transportType, String endpointUrl, String toolName, Map<String, Object> arguments) {
        @Deprecated
        public ToolCall(UUID capabilityId, String sseUrl, String toolName, Map<String, Object> arguments) {
            this(capabilityId, "sse", sseUrl, toolName, arguments);
        }

        public ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        @Deprecated
        public String sseUrl() {
            return endpointUrl;
        }
    }

    record ToolResult(Map<String, Object> responsePayload, long latencyMs) {
        public ToolResult {
            responsePayload = responsePayload == null ? Map.of() : Map.copyOf(responsePayload);
        }
    }
}
