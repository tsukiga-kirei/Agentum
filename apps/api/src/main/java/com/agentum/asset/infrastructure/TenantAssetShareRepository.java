package com.agentum.asset.infrastructure;

import com.agentum.asset.domain.TenantAssetShareEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAssetShareRepository extends JpaRepository<TenantAssetShareEntity, UUID> {

    List<TenantAssetShareEntity> findByAssetId(UUID assetId);

    List<TenantAssetShareEntity> findByTenantIdAndGranteeUserIdOrderByCreatedAtDesc(UUID tenantId, UUID granteeUserId);

    boolean existsByAssetIdAndGranteeUserId(UUID assetId, UUID granteeUserId);

    void deleteByAssetId(UUID assetId);

    long countByAssetId(UUID assetId);
}
