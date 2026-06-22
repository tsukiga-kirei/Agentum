package com.agentum.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_refresh_tokens")
public class AuthRefreshTokenEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_assignment_id", nullable = false)
    private UUID roleAssignmentId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected AuthRefreshTokenEntity() {
    }

    public static AuthRefreshTokenEntity create(UUID userId, UUID roleAssignmentId, String tokenHash, Instant now, Instant expiresAt) {
        AuthRefreshTokenEntity token = new AuthRefreshTokenEntity();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.roleAssignmentId = roleAssignmentId;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = expiresAt;
        return token;
    }

    public void revoke(Instant now) {
        revokedAt = now;
        lastUsedAt = now;
    }

    public UUID getUserId() { return userId; }
    public UUID getRoleAssignmentId() { return roleAssignmentId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
