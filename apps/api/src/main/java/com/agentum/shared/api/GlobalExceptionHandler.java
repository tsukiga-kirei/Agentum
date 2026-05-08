package com.agentum.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception, HttpServletRequest request) {
        ApiError error = new ApiError(exception.getCode(), exception.getMessage(), exception.getDetails());
        return ResponseEntity
            .status(exception.getStatus())
            .body(ApiResponse.failure(error, RequestIds.current(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage() == null ? "请求参数不完整" : error.getDefaultMessage())
            .orElse("请求参数不完整");
        ApiError error = new ApiError("SYSTEM_REQUEST_INVALID", message);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, RequestIds.current(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        ApiError error = new ApiError("SYSTEM_INTERNAL_ERROR", "系统暂时无法处理请求，请稍后重试");
        return ResponseEntity.internalServerError().body(ApiResponse.failure(error, RequestIds.current(request)));
    }
}
