package com.agentum.asset.infrastructure;

import com.agentum.asset.domain.TenantAssetAccessGrantEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAssetAccessGrantRepository extends JpaRepository<TenantAssetAccessGrantEntity, UUID> {

    List<TenantAssetAccessGrantEntity> findByAssetId(UUID assetId);

    List<TenantAssetAccessGrantEntity> findByAssetIdIn(Collection<UUID> assetIds);

    List<TenantAssetAccessGrantEntity> findByTenantIdAndGranteeUserIdOrderByCreatedAtDesc(UUID tenantId, UUID granteeUserId);

    void deleteByAssetId(UUID assetId);
}
