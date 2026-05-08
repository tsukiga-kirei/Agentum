package com.agentum.auth.application;

import java.time.Instant;
import java.util.UUID;

// Token claims 只保存请求鉴权所需的上下文，不承载密码、凭证或租户能力明细。
public record AuthTokenClaims(
    UUID userId,
    String username,
    UUID tenantId,
    String role,
    String portal,
    String spaceCode,
    Instant issuedAt,
    Instant expiresAt
) {
}
