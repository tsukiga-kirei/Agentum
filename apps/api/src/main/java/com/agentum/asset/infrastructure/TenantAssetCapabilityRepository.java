package com.agentum.asset.infrastructure;

import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantAssetCapabilityRepository extends JpaRepository<TenantAssetCapabilityEntity, UUID> {

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndCreatedBy(UUID tenantId, UUID createdBy);

    boolean existsByTenantIdAndCodeAndVersion(UUID tenantId, String code, String version);

    Optional<TenantAssetCapabilityEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndCodeAndVersionAndIdNot(UUID tenantId, String code, String version, UUID id);

    @Query("""
        select asset from TenantAssetCapabilityEntity asset
        where asset.tenantId = :tenantId
          and asset.createdBy = :createdBy
          and (:assetType is null or asset.assetType = :assetType)
          and (:status is null or asset.status = :status)
          and (
            lower(asset.name) like lower(concat('%', :keyword, '%'))
            or lower(asset.code) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(asset.description, '')) like lower(concat('%', :keyword, '%'))
          )
        """)
    Page<TenantAssetCapabilityEntity> searchMine(
        @Param("tenantId") UUID tenantId,
        @Param("createdBy") UUID createdBy,
        @Param("keyword") String keyword,
        @Param("assetType") String assetType,
        @Param("status") String status,
        Pageable pageable
    );
}
