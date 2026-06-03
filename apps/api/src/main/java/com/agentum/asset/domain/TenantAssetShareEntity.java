package com.agentum.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_asset_shares")
public class TenantAssetShareEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "grantee_user_id", nullable = false)
    private UUID granteeUserId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantAssetShareEntity() {
    }

    public static TenantAssetShareEntity create(UUID tenantId, UUID assetId, UUID granteeUserId, UUID operatorUserId, Instant now) {
        TenantAssetShareEntity entity = new TenantAssetShareEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.assetId = assetId;
        entity.granteeUserId = granteeUserId;
        entity.createdBy = operatorUserId;
        entity.createdAt = now;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getGranteeUserId() {
        return granteeUserId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
