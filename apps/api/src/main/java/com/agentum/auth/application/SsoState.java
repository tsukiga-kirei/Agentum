package com.agentum.auth.application;

import java.time.Instant;
import java.util.UUID;

public record SsoState(
    UUID tenantId,
    UUID providerId,
    String portal,
    String nonce,
    Instant issuedAt,
    Instant expiresAt
) {
}
