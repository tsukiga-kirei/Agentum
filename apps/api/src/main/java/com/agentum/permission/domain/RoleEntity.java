package com.agentum.permission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    private String scope;

    @Column(nullable = false, length = 30)
    private String status;

    protected RoleEntity() {
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

    public String getScope() {
        return scope;
    }

    public String getStatus() {
        return status;
    }
}
