package com.agentum.mcp.domain;

import java.util.Arrays;

public enum McpTransportType {
    SSE("sse"),
    STREAMABLE_HTTP("streamable_http");

    private final String value;

    McpTransportType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static McpTransportType fromValue(String value) {
        if (value == null) {
            return SSE;
        }
        return Arrays.stream(values())
            .filter(t -> t.value.equalsIgnoreCase(value.trim()))
            .findFirst()
            .orElse(SSE);
    }
}
