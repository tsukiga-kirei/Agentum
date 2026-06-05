package com.agentum.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 租户 SSO 身份源配置只用于认证入口发现和 OIDC 协议参数，不承载业务角色或资源权限。
@Entity
@Table(name = "tenant_sso_providers")
public class TenantSsoProviderEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "provider_type", nullable = false, length = 30)
    private String providerType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 500)
    private String issuer;

    @Column(name = "client_id", nullable = false, length = 200)
    private String clientId;

    @Column(name = "encrypted_client_secret", columnDefinition = "TEXT")
    private String encryptedClientSecret;

    @Column(name = "authorization_endpoint", nullable = false, length = 800)
    private String authorizationEndpoint;

    @Column(name = "token_endpoint", nullable = false, length = 800)
    private String tokenEndpoint;

    @Column(name = "jwks_uri", nullable = false, length = 800)
    private String jwksUri;

    @Column(name = "logout_endpoint", length = 800)
    private String logoutEndpoint;

    @Column(name = "email_domain", length = 160)
    private String emailDomain;

    @Column(name = "auto_bind_email", nullable = false)
    private boolean autoBindEmail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantSsoProviderEntity() {
    }

    public static TenantSsoProviderEntity createOidc(
        UUID tenantId,
        String name,
        String issuer,
        String clientId,
        String encryptedClientSecret,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwksUri,
        String emailDomain,
        Instant now
    ) {
        TenantSsoProviderEntity entity = new TenantSsoProviderEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.providerType = "oidc";
        entity.name = name;
        entity.status = "enabled";
        entity.issuer = issuer;
        entity.clientId = clientId;
        entity.encryptedClientSecret = encryptedClientSecret;
        entity.authorizationEndpoint = authorizationEndpoint;
        entity.tokenEndpoint = tokenEndpoint;
        entity.jwksUri = jwksUri;
        entity.logoutEndpoint = "";
        entity.emailDomain = emailDomain;
        entity.autoBindEmail = true;
        entity.config = new HashMap<>();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void forceIdForTest(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEncryptedClientSecret() {
        return encryptedClientSecret;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public String getLogoutEndpoint() {
        return logoutEndpoint;
    }

    public String getEmailDomain() {
        return emailDomain;
    }

    public boolean isAutoBindEmail() {
        return autoBindEmail;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
