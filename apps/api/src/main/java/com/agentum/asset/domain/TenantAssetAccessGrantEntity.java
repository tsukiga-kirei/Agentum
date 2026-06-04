package com.agentum.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_asset_access_grants")
public class TenantAssetAccessGrantEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "grantee_user_id", nullable = false)
    private UUID granteeUserId;

    @Column(name = "access_level", nullable = false, length = 20)
    private String accessLevel;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantAssetAccessGrantEntity() {
    }

    public static TenantAssetAccessGrantEntity create(
        UUID tenantId,
        UUID assetId,
        UUID granteeUserId,
        String accessLevel,
        UUID operatorUserId,
        Instant now
    ) {
        TenantAssetAccessGrantEntity entity = new TenantAssetAccessGrantEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.assetId = assetId;
        entity.granteeUserId = granteeUserId;
        entity.accessLevel = accessLevel;
        entity.createdBy = operatorUserId;
        entity.createdAt = now;
        return entity;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getGranteeUserId() {
        return granteeUserId;
    }

    public String getAccessLevel() {
        return accessLevel;
    }
}
