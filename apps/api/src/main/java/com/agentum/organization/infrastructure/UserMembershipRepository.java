package com.agentum.organization.infrastructure;

import com.agentum.organization.domain.UserMembershipEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMembershipRepository extends JpaRepository<UserMembershipEntity, UUID> {

    List<UserMembershipEntity> findByUserIdAndTenantIdAndStatus(UUID userId, UUID tenantId, String status);

    List<UserMembershipEntity> findByTenantIdAndStatus(UUID tenantId, String status);
}
