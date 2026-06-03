package com.agentum.asset.domain;

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

// 租户自建资产只记录“业务可治理资产”的外壳；真实 MCP 凭证、系统启用和人员分配仍沿用系统管理与租户管理链路。
@Entity
@Table(name = "tenant_asset_capabilities")
public class TenantAssetCapabilityEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "asset_type", nullable = false, length = 40)
    private String assetType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 30)
    private String visibility;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "base_system_capability_id")
    private UUID baseSystemCapabilityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected TenantAssetCapabilityEntity() {
    }

    public static TenantAssetCapabilityEntity create(
        UUID tenantId,
        String assetType,
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String status,
        String visibility,
        UUID baseSystemCapabilityId,
        Map<String, Object> config,
        UUID operatorUserId,
        Instant now
    ) {
        TenantAssetCapabilityEntity entity = new TenantAssetCapabilityEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.assetType = assetType;
        entity.name = name;
        entity.code = code;
        entity.version = version == null ? "v1" : version;
        entity.description = description;
        entity.riskLevel = riskLevel == null ? "low" : riskLevel;
        entity.status = status == null ? "draft" : status;
        entity.visibility = visibility == null ? "private" : visibility;
        entity.sourceType = baseSystemCapabilityId == null ? "custom" : "derived";
        entity.baseSystemCapabilityId = baseSystemCapabilityId;
        entity.config = config == null ? new HashMap<>() : new HashMap<>(config);
        entity.createdBy = operatorUserId;
        entity.updatedBy = operatorUserId;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void updateDraft(
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String visibility,
        Map<String, Object> config,
        UUID operatorUserId,
        Instant now
    ) {
        this.name = name;
        this.code = code;
        this.version = version;
        this.description = description;
        this.riskLevel = riskLevel;
        this.visibility = visibility;
        this.config = config == null ? new HashMap<>() : new HashMap<>(config);
        this.updatedBy = operatorUserId;
        this.updatedAt = now;
    }

    public void publish(UUID operatorUserId, Instant now) {
        this.status = "published";
        this.visibility = "tenant";
        this.updatedBy = operatorUserId;
        this.updatedAt = now;
        this.publishedAt = now;
    }

    public void revertToDraft(UUID operatorUserId, Instant now) {
        this.status = "draft";
        this.visibility = "private";
        this.publishedAt = null;
        this.updatedBy = operatorUserId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getAssetType() {
        return assetType;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getBaseSystemCapabilityId() {
        return baseSystemCapabilityId;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
