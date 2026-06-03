package com.agentum.permission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 角色是动作能力集合；tenantId 为空表示平台级角色，非空表示租户内角色。
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 30)
    private String status;

    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoleEntity() {
    }

    public static RoleEntity create(UUID tenantId, String code, String name, String description) {
        RoleEntity role = new RoleEntity();
        role.id = UUID.randomUUID();
        role.tenantId = tenantId;
        role.code = code;
        role.name = name;
        role.description = description;
        role.status = "active";
        role.builtIn = false;
        role.updatedAt = Instant.now();
        return role;
    }

    public void update(String name, String description, String status) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }
}
