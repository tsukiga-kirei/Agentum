package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 部门树用于待办分派、审核范围和资源过滤；排序与移动策略后续会进入组织管理 API。
@Entity
@Table(name = "departments")
public class DepartmentEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 80)
    private String code;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DepartmentEntity() {
    }

    // 第一阶段只开放新增部门，默认 active；后续停用、移动和排序都需要补审计事件。
    public static DepartmentEntity create(UUID tenantId, UUID parentId, String name, String code, int sortOrder) {
        DepartmentEntity department = new DepartmentEntity();
        department.id = UUID.randomUUID();
        department.tenantId = tenantId;
        department.parentId = parentId;
        department.name = name;
        department.code = code;
        department.sortOrder = sortOrder;
        department.status = "active";
        department.updatedAt = Instant.now();
        return department;
    }

    public void update(String name, UUID parentId, int sortOrder) {
        this.name = name;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.updatedAt = Instant.now();
    }

    // 部门删除采用停用，避免历史成员、待办和审计记录失去组织上下文。
    public void disable() {
        this.status = "disabled";
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getStatus() {
        return status;
    }
}
