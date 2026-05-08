package com.agentum.auth.interfaces;

public record AuthUserResponse(
    String id,
    String username,
    String displayName,
    String email,
    String avatar,
    String role,
    String tenantId,
    String tenantName,
    String tenantCode,
    String organization,
    String space,
    String lastLoginAt
) {
}
