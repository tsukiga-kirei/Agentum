package com.agentum.permission.infrastructure;

import com.agentum.permission.domain.PageGrantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PageGrantRepository extends JpaRepository<PageGrantEntity, UUID> {

    List<PageGrantEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<PageGrantEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndPrincipalTypeAndPrincipalIdAndPageKey(UUID tenantId, String principalType, UUID principalId, String pageKey);
}
