package com.agentum.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 用户账号只描述登录主体，具体在某个租户和部门能做什么由成员关系和角色授权决定。
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 255)
    private String email;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserAccount() {
    }

    // 当前阶段由管理员直接创建本地账号；后续应替换为邀请注册、首次改密和账号安全审计。
    public static UserAccount create(String username, String passwordHash, String displayName, String email) {
        UserAccount user = new UserAccount();
        user.id = UUID.randomUUID();
        user.username = username;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.email = email;
        user.avatarUrl = "";
        user.status = "active";
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void markLoggedIn(Instant loginTime) {
        this.lastLoginAt = loginTime;
    }

    public void updateProfile(String username, String displayName, String email) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
    }
}
