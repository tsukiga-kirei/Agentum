package com.agentum.permission.infrastructure;

import com.agentum.permission.domain.ResourceGrantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrantEntity, UUID> {

    List<ResourceGrantEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ResourceGrantEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
        UUID tenantId,
        String principalType,
        UUID principalId,
        String resourceType,
        UUID resourceId
    );
}
