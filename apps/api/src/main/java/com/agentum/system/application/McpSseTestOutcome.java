package com.agentum.system.application;

import java.util.List;
import java.util.Map;

public record McpSseTestOutcome(
    String status,
    String summary,
    List<McpToolDescriptor> tools
) {

    public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    }
}
