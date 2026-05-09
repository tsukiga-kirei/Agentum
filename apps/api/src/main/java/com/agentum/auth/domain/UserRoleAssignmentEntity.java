package com.agentum.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 用户系统级角色分配（参照 AuraOA user_role_assignments）。
// 一个用户可拥有多条记录，表达在不同租户中以不同系统角色登录。
// system_admin 角色的 tenant_id 为 NULL。
@Entity
@Table(name = "user_role_assignments")
public class UserRoleAssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // 系统角色：business / tenant_admin / system_admin
    @Column(nullable = false, length = 30)
    private String role;

    // 关联租户（system_admin 时为 NULL）
    @Column(name = "tenant_id")
    private UUID tenantId;

    // 前端展示标签，如"云程科技 - 业务用户"
    @Column(length = 200)
    private String label;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAssignment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserRoleAssignmentEntity() {
    }

    public static UserRoleAssignmentEntity create(UUID userId, String role, UUID tenantId, String label, boolean isDefault) {
        UserRoleAssignmentEntity entity = new UserRoleAssignmentEntity();
        entity.id = UUID.randomUUID();
        entity.userId = userId;
        entity.role = role;
        entity.tenantId = tenantId;
        entity.label = label;
        entity.defaultAssignment = isDefault;
        entity.createdAt = Instant.now();
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getLabel() {
        return label;
    }

    public boolean isDefaultAssignment() {
        return defaultAssignment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
