package com.agentum.system.domain;

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

@Entity
@Table(name = "model_providers")
public class ModelProviderEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "provider_type", nullable = false, length = 80)
    private String providerType;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "credential_ref", length = 200)
    private String credentialRef;

    @Column(name = "default_model", length = 160)
    private String defaultModel;

    @Column(name = "reasoning_model", nullable = false)
    private boolean reasoningModel;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "connectivity_status", nullable = false, length = 30)
    private String connectivityStatus;

    @Column(name = "connectivity_checked_at")
    private Instant connectivityCheckedAt;

    protected ModelProviderEntity() {
    }

    public static ModelProviderEntity create(
        String name,
        String providerType,
        String baseUrl,
        String defaultModel,
        boolean reasoningModel,
        String status,
        Instant now
    ) {
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.id = UUID.randomUUID();
        entity.name = name;
        entity.providerType = providerType;
        entity.baseUrl = baseUrl;
        entity.defaultModel = defaultModel;
        entity.reasoningModel = reasoningModel;
        entity.status = status == null ? "draft" : status;
        entity.settings = new HashMap<>();
        entity.connectivityStatus = "offline";
        entity.connectivityCheckedAt = null;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void updateProfile(String name, String providerType, String baseUrl, String defaultModel, boolean reasoningModel, String status, Instant now) {
        this.name = name;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.reasoningModel = reasoningModel;
        this.status = status == null ? "draft" : status;
        resetConnectivity(now);
    }

    public void markApiKeyConfigured(Instant now) {
        // 当前阶段只记录“已配置密钥”的引用状态；密钥密文放在 settings，列表和日志都不能回显明文。
        this.credentialRef = "inline-api-key";
        this.updatedAt = now;
    }

    public void storeEncryptedApiKey(String encryptedApiKey, Instant now) {
        if (encryptedApiKey == null || encryptedApiKey.isBlank()) {
            return;
        }
        this.settings.put("encryptedApiKey", encryptedApiKey);
        markApiKeyConfigured(now);
    }

    public String getEncryptedApiKey() {
        Object value = settings.get("encryptedApiKey");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public boolean hasCredentialRef() {
        return credentialRef != null && !credentialRef.isBlank();
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public boolean isReasoningModel() {
        return reasoningModel;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void recordConnectivityCheck(String status, Instant checkedAt, Instant now) {
        this.connectivityStatus = "online".equals(status) ? "online" : "offline";
        this.connectivityCheckedAt = checkedAt;
        this.updatedAt = now;
    }

    public void resetConnectivity(Instant now) {
        this.connectivityStatus = "offline";
        this.connectivityCheckedAt = null;
        this.updatedAt = now;
    }

    public String getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getConnectivityCheckedAt() {
        return connectivityCheckedAt;
    }
}
