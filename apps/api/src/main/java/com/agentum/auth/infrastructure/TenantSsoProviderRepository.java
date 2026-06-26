package com.agentum.auth.infrastructure;

import com.agentum.auth.domain.TenantSsoProviderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSsoProviderRepository extends JpaRepository<TenantSsoProviderEntity, UUID> {

    List<TenantSsoProviderEntity> findByTenantIdAndStatusOrderByNameAsc(UUID tenantId, String status);

    List<TenantSsoProviderEntity> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<TenantSsoProviderEntity> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);

    Optional<TenantSsoProviderEntity> findByTenantIdAndProviderType(UUID tenantId, String providerType);
}
