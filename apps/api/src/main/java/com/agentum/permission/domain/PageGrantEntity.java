package com.agentum.permission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 页签授权只决定业务侧模块入口可见性；具体能力调用仍由资源授权和运行时权限网关复核。
@Entity
@Table(name = "page_grants")
public class PageGrantEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "page_key", nullable = false, length = 80)
    private String pageKey;

    @Column(name = "principal_type", nullable = false, length = 30)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PageGrantEntity() {
    }

    public static PageGrantEntity create(UUID tenantId, String pageKey, String principalType, UUID principalId) {
        PageGrantEntity entity = new PageGrantEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.pageKey = pageKey;
        entity.principalType = principalType;
        entity.principalId = principalId;
        entity.createdAt = Instant.now();
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getPageKey() {
        return pageKey;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
