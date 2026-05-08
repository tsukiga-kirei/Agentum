package com.agentum.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception, HttpServletRequest request) {
        // 业务异常按预期错误返回中文提示，同时保留错误码和 requestId 供前后端排查。
        log.warn(
            "业务请求被拒绝 path={} code={} status={} requestId={}",
            request.getRequestURI(),
            exception.getCode(),
            exception.getStatus().value(),
            RequestIds.current(request)
        );
        ApiError error = new ApiError(exception.getCode(), exception.getMessage(), exception.getDetails());
        return ResponseEntity
            .status(exception.getStatus())
            .body(ApiResponse.failure(error, RequestIds.current(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        // 参数校验只返回首个用户可理解的中文错误，避免把 Bean Validation 细节暴露到前端。
        String message = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage() == null ? "请求参数不完整" : error.getDefaultMessage())
            .orElse("请求参数不完整");
        ApiError error = new ApiError("SYSTEM_REQUEST_INVALID", message);
        log.warn("请求参数校验失败 path={} requestId={} message={}", request.getRequestURI(), RequestIds.current(request), message);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, RequestIds.current(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        // 非预期异常统一兜底，响应不暴露堆栈、SQL 或依赖原始错误，详细信息只进后端日志。
        ApiError error = new ApiError("SYSTEM_INTERNAL_ERROR", "系统暂时无法处理请求，请稍后重试");
        log.error("系统异常 path={} requestId={}", request.getRequestURI(), RequestIds.current(request), exception);
        return ResponseEntity.internalServerError().body(ApiResponse.failure(error, RequestIds.current(request)));
    }
}
