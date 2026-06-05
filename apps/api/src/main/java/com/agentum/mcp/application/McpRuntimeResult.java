package com.agentum.mcp.application;

import java.util.Map;

public record McpRuntimeResult(Map<String, Object> outputs) {
    public McpRuntimeResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }
}
