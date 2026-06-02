package com.agentum.system.infrastructure;

import com.agentum.system.domain.TenantModelAssignmentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantModelAssignmentRepository extends JpaRepository<TenantModelAssignmentEntity, UUID> {

    List<TenantModelAssignmentEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<TenantModelAssignmentEntity> findByTenantIdAndProviderId(UUID tenantId, UUID providerId);

    boolean existsByProviderIdAndStatus(UUID providerId, String status);
}
