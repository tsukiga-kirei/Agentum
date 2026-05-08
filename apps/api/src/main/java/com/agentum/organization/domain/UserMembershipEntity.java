package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_memberships")
public class UserMembershipEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "space_code", nullable = false, length = 80)
    private String spaceCode;

    @Column(name = "is_default", nullable = false)
    private boolean defaultMembership;

    @Column(nullable = false, length = 30)
    private String status;

    protected UserMembershipEntity() {
    }

    public static UserMembershipEntity create(UUID tenantId, UUID userId, UUID departmentId, UUID roleId, String spaceCode) {
        UserMembershipEntity membership = new UserMembershipEntity();
        membership.id = UUID.randomUUID();
        membership.tenantId = tenantId;
        membership.userId = userId;
        membership.departmentId = departmentId;
        membership.roleId = roleId;
        membership.spaceCode = spaceCode;
        membership.defaultMembership = true;
        membership.status = "active";
        return membership;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public String getSpaceCode() {
        return spaceCode;
    }

    public boolean isDefaultMembership() {
        return defaultMembership;
    }

    public String getStatus() {
        return status;
    }
}
