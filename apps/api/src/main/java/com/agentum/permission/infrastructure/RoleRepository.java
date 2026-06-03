package com.agentum.permission.infrastructure;

import com.agentum.permission.domain.RoleEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByIdAndStatus(UUID id, String status);

    List<RoleEntity> findByTenantIdAndStatusOrderByNameAsc(UUID tenantId, String status);

    List<RoleEntity> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<RoleEntity> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);

    Optional<RoleEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<RoleEntity> findByTenantIdAndCodeAndStatus(UUID tenantId, String code, String status);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
