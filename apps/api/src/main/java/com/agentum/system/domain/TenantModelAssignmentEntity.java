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
@Table(name = "tenant_model_assignments")
public class TenantModelAssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "default_model", length = 160)
    private String defaultModel;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantModelAssignmentEntity() {
    }

    public static TenantModelAssignmentEntity create(UUID tenantId, UUID providerId, String defaultModel, String status, Instant now) {
        TenantModelAssignmentEntity entity = new TenantModelAssignmentEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.providerId = providerId;
        entity.defaultModel = defaultModel;
        entity.status = status == null ? "enabled" : status;
        entity.settings = new HashMap<>();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void updateStatus(String status, Instant now) {
        this.status = status == null ? "enabled" : status;
        this.updatedAt = now;
    }

    public void updateDefaultModel(String defaultModel, Instant now) {
        this.defaultModel = defaultModel;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
