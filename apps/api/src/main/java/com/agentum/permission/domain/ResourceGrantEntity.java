package com.agentum.permission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 资源授权支持角色、部门、人员三个授权主体；运行时仍需要结合租户上下文和资源类型再次校验。
@Entity
@Table(name = "resource_grants")
public class ResourceGrantEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "principal_type", nullable = false, length = 30)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] actions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResourceGrantEntity() {
    }

    public static ResourceGrantEntity create(UUID tenantId, String resourceType, UUID resourceId, String principalType, UUID principalId, String[] actions) {
        ResourceGrantEntity entity = new ResourceGrantEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.resourceType = resourceType;
        entity.resourceId = resourceId;
        entity.principalType = principalType;
        entity.principalId = principalId;
        entity.actions = actions;
        entity.createdAt = Instant.now();
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String[] getActions() {
        return actions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
