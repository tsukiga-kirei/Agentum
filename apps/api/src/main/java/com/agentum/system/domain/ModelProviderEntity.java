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

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModelProviderEntity() {
    }

    public static ModelProviderEntity create(
        String name,
        String providerType,
        String baseUrl,
        String defaultModel,
        String status,
        Instant now
    ) {
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.id = UUID.randomUUID();
        entity.name = name;
        entity.providerType = providerType;
        entity.baseUrl = baseUrl;
        entity.defaultModel = defaultModel;
        entity.status = status == null ? "draft" : status;
        entity.settings = new HashMap<>();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
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

    public String getDefaultModel() {
        return defaultModel;
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
}
