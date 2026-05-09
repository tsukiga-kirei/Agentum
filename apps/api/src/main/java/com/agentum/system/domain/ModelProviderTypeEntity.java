package com.agentum.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "model_provider_types")
public class ModelProviderTypeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "auth_scheme", nullable = false, length = 40)
    private String authScheme;

    @Column(name = "default_base_url", length = 500)
    private String defaultBaseUrl;

    @Column(name = "model_list_endpoint", length = 200)
    private String modelListEndpoint;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModelProviderTypeEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthScheme() {
        return authScheme;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getModelListEndpoint() {
        return modelListEndpoint;
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
