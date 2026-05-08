package com.agentum.shared.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

// 业务异常统一承载 HTTP 状态和错误码，由 GlobalExceptionHandler 转成标准 ApiResponse。
// details 只能放脱敏后的字段级信息，禁止塞入 SQL、Token、凭证或供应商原始响应。
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
