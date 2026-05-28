package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 租户内自定义角色，用于第二层细粒度权限。
// page_permissions 控制业务用户在租户内可见的页签（如 workbench、designer、assets）。
// resource_permissions 控制当前租户已启用的 MCP、Skill、提示词模板和交付能力可被哪些租户内角色使用。
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

    // JSON 数组，如 ["workbench","designer","assets"]
    @Column(name = "page_permissions", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String pagePermissions;

    // JSON 数组，如 [{"resourceType":"skill","resourceId":"...","actions":["use"]}]
    @Column(name = "resource_permissions", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String resourcePermissions;

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

    public static TenantOrgRoleEntity create(UUID tenantId, String name, String description, String pagePermissions, String resourcePermissions) {
        Instant now = Instant.now();
        TenantOrgRoleEntity role = new TenantOrgRoleEntity();
        role.id = UUID.randomUUID();
        role.tenantId = tenantId;
        role.name = name;
        role.description = description;
        role.pagePermissions = pagePermissions;
        role.resourcePermissions = resourcePermissions;
        role.systemRole = false;
        role.status = "active";
        role.createdAt = now;
        role.updatedAt = now;
        return role;
    }

    public void update(String name, String description, String pagePermissions, String resourcePermissions, String status) {
        this.name = name;
        this.description = description;
        this.pagePermissions = pagePermissions;
        this.resourcePermissions = resourcePermissions;
        this.status = status;
        this.updatedAt = Instant.now();
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

    public String getResourcePermissions() {
        return resourcePermissions;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
