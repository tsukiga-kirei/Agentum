package com.agentum.shared.api;

public record ApiResponse<T>(boolean success, T data, ApiError error, String requestId) {

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public static ApiResponse<Void> success(String requestId) {
        return new ApiResponse<>(true, null, null, requestId);
    }

    public static ApiResponse<Void> failure(ApiError error, String requestId) {
        return new ApiResponse<>(false, null, error, requestId);
    }
}
