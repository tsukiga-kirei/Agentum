package com.agentum.system.infrastructure;

import com.agentum.system.domain.TenantCapabilityGrantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantCapabilityGrantRepository extends JpaRepository<TenantCapabilityGrantEntity, UUID> {

    List<TenantCapabilityGrantEntity> findAllByOrderByCreatedAtDesc();

    List<TenantCapabilityGrantEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<TenantCapabilityGrantEntity> findByTenantIdAndCapabilityId(UUID tenantId, UUID capabilityId);

    boolean existsByCapabilityIdAndStatus(UUID capabilityId, String status);
}
