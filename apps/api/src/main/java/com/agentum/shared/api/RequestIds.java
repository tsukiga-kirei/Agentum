package com.agentum.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

// requestId 是接口响应、后端日志、运行记录和审计事件的共同追踪键。
public final class RequestIds {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "agentum.requestId";
    public static final String MDC_KEY = "requestId";

    private RequestIds() {
    }

    public static String current(HttpServletRequest request) {
        Object requestId = request.getAttribute(ATTRIBUTE_NAME);

        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }

        return "req_unknown";
    }

    public static String current() {
        String requestId = MDC.get(MDC_KEY);

        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }

        return "req_unknown";
    }
}
