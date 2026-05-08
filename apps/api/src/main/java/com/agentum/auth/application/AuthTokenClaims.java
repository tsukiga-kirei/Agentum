package com.agentum.auth.application;

import java.time.Instant;
import java.util.UUID;

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
