package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

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

    protected DepartmentEntity() {
    }

    public static DepartmentEntity create(UUID tenantId, UUID parentId, String name, String code, int sortOrder) {
        DepartmentEntity department = new DepartmentEntity();
        department.id = UUID.randomUUID();
        department.tenantId = tenantId;
        department.parentId = parentId;
        department.name = name;
        department.code = code;
        department.sortOrder = sortOrder;
        department.status = "active";
        return department;
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
