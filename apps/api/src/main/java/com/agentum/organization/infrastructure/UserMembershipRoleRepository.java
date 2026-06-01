package com.agentum.organization.infrastructure;

import com.agentum.organization.domain.UserMembershipRoleEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserMembershipRoleRepository extends JpaRepository<UserMembershipRoleEntity, UUID> {

    List<UserMembershipRoleEntity> findByMembershipIdAndStatus(UUID membershipId, String status);

    List<UserMembershipRoleEntity> findByMembershipIdInAndStatus(Collection<UUID> membershipIds, String status);

    Optional<UserMembershipRoleEntity> findByMembershipIdAndRoleIdAndStatus(UUID membershipId, UUID roleId, String status);

    @Query("""
        select count(link)
        from UserMembershipRoleEntity link
        join UserMembershipEntity membership on membership.id = link.membershipId
        where membership.tenantId = :tenantId
          and membership.status = :membershipStatus
          and link.roleId = :roleId
          and link.status = :roleStatus
        """)
    long countActiveMembershipsByTenantIdAndRoleId(
        @Param("tenantId") UUID tenantId,
        @Param("roleId") UUID roleId,
        @Param("membershipStatus") String membershipStatus,
        @Param("roleStatus") String roleStatus
    );

    void deleteByRoleId(UUID roleId);
}
