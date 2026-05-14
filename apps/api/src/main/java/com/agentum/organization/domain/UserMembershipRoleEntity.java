package com.agentum.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 人员角色中间表：一个租户成员关系可以绑定多个角色，避免用多条成员记录伪装多角色。
@Entity
@Table(name = "user_membership_roles")
public class UserMembershipRoleEntity {

    @Id
    private UUID id;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserMembershipRoleEntity() {
    }

    public static UserMembershipRoleEntity create(UUID membershipId, UUID roleId) {
        Instant now = Instant.now();
        UserMembershipRoleEntity entity = new UserMembershipRoleEntity();
        entity.id = UUID.randomUUID();
        entity.membershipId = membershipId;
        entity.roleId = roleId;
        entity.status = "active";
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMembershipId() {
        return membershipId;
    }

    public UUID getRoleId() {
        return roleId;
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

    public void disable() {
        this.status = "disabled";
        this.updatedAt = Instant.now();
    }
}
