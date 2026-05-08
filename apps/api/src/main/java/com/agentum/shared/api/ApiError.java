package com.agentum.shared.api;

import java.util.Map;

// 统一错误结构只暴露业务错误码、中文提示和脱敏 details，避免把异常栈或底层依赖响应直接返回前端。
public record ApiError(String code, String message, Map<String, Object> details) {
    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
