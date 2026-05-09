package com.agentum.auth.application;

import java.time.Instant;
import java.util.UUID;

// Token claims 只保存请求鉴权所需的上下文，不承载密码、凭证或租户能力明细。
// roleAssignmentId 用于快速定位当前活跃的角色分配记录。
public record AuthTokenClaims(
    UUID userId,
    String username,
    UUID tenantId,
    String role,
    String portal,
    String spaceCode,
    UUID roleAssignmentId,
    Instant issuedAt,
    Instant expiresAt
) {
}
