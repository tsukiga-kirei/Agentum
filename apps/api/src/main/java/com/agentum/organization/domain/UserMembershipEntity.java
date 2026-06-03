package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// 用户租户成员关系负责把用户、租户和部门绑定起来；多角色关系由 user_membership_roles 单独维护。
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

    @Column(name = "is_default", nullable = false)
    private boolean defaultMembership;

    @Column(nullable = false, length = 30)
    private String status;

    protected UserMembershipEntity() {
    }

    // 当前新增成员默认成为该租户内的默认成员；角色绑定由中间表维护，避免一个人多个角色时被展示成多个人。
    public static UserMembershipEntity create(UUID tenantId, UUID userId, UUID departmentId) {
        return create(tenantId, userId, departmentId, true);
    }

    private static UserMembershipEntity create(UUID tenantId, UUID userId, UUID departmentId, boolean defaultMembership) {
        UserMembershipEntity membership = new UserMembershipEntity();
        membership.id = UUID.randomUUID();
        membership.tenantId = tenantId;
        membership.userId = userId;
        membership.departmentId = departmentId;
        membership.defaultMembership = defaultMembership;
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

    public UUID getDepartmentId() {
        return departmentId;
    }

    public boolean isDefaultMembership() {
        return defaultMembership;
    }

    public String getStatus() {
        return status;
    }

    // 部门调整会影响待办分派与可见范围，允许清空部门以表示未归属。
    public void assignDepartment(UUID departmentId) {
        this.departmentId = departmentId;
    }

    // 成员状态影响该成员在当前租户内的登录入口和业务可见性，禁用不删除历史关系。
    public void updateStatus(String status) {
        this.status = status;
    }
}
