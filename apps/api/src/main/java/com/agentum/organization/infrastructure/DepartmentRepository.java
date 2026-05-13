package com.agentum.organization.infrastructure;

import com.agentum.organization.domain.DepartmentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, UUID> {

    List<DepartmentEntity> findByTenantIdAndStatusOrderBySortOrderAscNameAsc(UUID tenantId, String status);

    Optional<DepartmentEntity> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);

    Optional<DepartmentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndCode(UUID tenantId, String code);
}
