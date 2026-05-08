package com.agentum.shared.api;

import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details) {
    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
