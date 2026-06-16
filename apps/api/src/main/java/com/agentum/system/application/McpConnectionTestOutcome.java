package com.agentum.system.application;

import java.util.List;
import java.util.Map;

public record McpConnectionTestOutcome(
    String status,
    String summary,
    List<McpToolDescriptor> tools
) {

    public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    }
}
