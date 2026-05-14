package com.agentum.organization.infrastructure;

import com.agentum.organization.domain.UserMembershipEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMembershipRepository extends JpaRepository<UserMembershipEntity, UUID> {

    List<UserMembershipEntity> findByUserIdAndTenantIdAndStatus(UUID userId, UUID tenantId, String status);

    List<UserMembershipEntity> findByTenantIdAndStatus(UUID tenantId, String status);

    List<UserMembershipEntity> findByTenantId(UUID tenantId);

    Optional<UserMembershipEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<UserMembershipEntity> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);

    long countByTenantIdAndDepartmentIdAndStatus(UUID tenantId, UUID departmentId, String status);

}
