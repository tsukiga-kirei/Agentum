package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 租户内自定义角色，用于第二层细粒度权限。
// page_permissions 控制业务用户在租户内可见的页签（如 workbench、designer、assets、audit）。
// 与 user_role_assignments 的系统角色（business/tenant_admin/system_admin）是不同层级。
@Entity
@Table(name = "tenant_org_roles")
public class TenantOrgRoleEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    private String description;

    // JSON 数组，如 ["workbench","designer","assets","audit"]
    @Column(name = "page_permissions", nullable = false, columnDefinition = "jsonb")
    private String pagePermissions;

    @Column(name = "is_system", nullable = false)
    private boolean systemRole;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantOrgRoleEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPagePermissions() {
        return pagePermissions;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public String getStatus() {
        return status;
    }
}
