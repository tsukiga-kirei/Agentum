package com.agentum.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 租户是最高业务隔离边界，后续模型额度、能力授权、数据保留和凭证策略都会挂到这里。
@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    /**
     * 平台系统管理员调整租户可用状态；公开租户列表仅展示 active，suspended 用于暂停租户登录与资源写入。
     */
    public void applyPlatformStatus(String newStatus, Instant updatedAt) {
        this.status = newStatus;
        this.updatedAt = updatedAt;
    }
}
