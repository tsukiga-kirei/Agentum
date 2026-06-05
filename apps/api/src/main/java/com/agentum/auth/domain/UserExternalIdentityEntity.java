package com.agentum.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 外部身份绑定只保存稳定 subject 与本地用户的关系；业务权限仍由 Agentum 本地角色和资源范围决定。
@Entity
@Table(name = "user_external_identities")
public class UserExternalIdentityEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(length = 255)
    private String email;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserExternalIdentityEntity() {
    }

    public static UserExternalIdentityEntity create(
        UUID userId,
        UUID tenantId,
        UUID providerId,
        String subject,
        String email,
        String displayName,
        Instant now
    ) {
        UserExternalIdentityEntity entity = new UserExternalIdentityEntity();
        entity.id = UUID.randomUUID();
        entity.userId = userId;
        entity.tenantId = tenantId;
        entity.providerId = providerId;
        entity.subject = subject;
        entity.email = email;
        entity.displayName = displayName;
        entity.lastLoginAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void markLoggedIn(String email, String displayName, Instant now) {
        this.email = email;
        this.displayName = displayName;
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getSubject() {
        return subject;
    }
}
