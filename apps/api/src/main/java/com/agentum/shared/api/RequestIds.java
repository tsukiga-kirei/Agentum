package com.agentum.shared.api;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIds {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "agentum.requestId";

    private RequestIds() {
    }

    public static String current(HttpServletRequest request) {
        Object requestId = request.getAttribute(ATTRIBUTE_NAME);

        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }

        return "req_unknown";
    }
}
