package com.agentum.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tenant_capability_grants")
public class TenantCapabilityGrantEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> quota;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantCapabilityGrantEntity() {
    }

    public static TenantCapabilityGrantEntity create(UUID tenantId, UUID capabilityId, String status, Instant now) {
        TenantCapabilityGrantEntity entity = new TenantCapabilityGrantEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.capabilityId = capabilityId;
        entity.status = status == null ? "enabled" : status;
        entity.quota = new HashMap<>();
        entity.createdAt = now;
        return entity;
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCapabilityId() {
        return capabilityId;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getQuota() {
        return quota;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
