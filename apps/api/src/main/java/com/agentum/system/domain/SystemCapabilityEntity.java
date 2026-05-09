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
@Table(name = "system_capabilities")
public class SystemCapabilityEntity {

    @Id
    private UUID id;

    @Column(name = "capability_type", nullable = false, length = 40)
    private String capabilityType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemCapabilityEntity() {
    }

    public static SystemCapabilityEntity create(
        String capabilityType,
        String name,
        String code,
        String version,
        String riskLevel,
        String status,
        Instant now
    ) {
        SystemCapabilityEntity entity = new SystemCapabilityEntity();
        entity.id = UUID.randomUUID();
        entity.capabilityType = capabilityType;
        entity.name = name;
        entity.code = code;
        entity.version = version == null ? "v1" : version;
        entity.riskLevel = riskLevel == null ? "low" : riskLevel;
        entity.status = status == null ? "draft" : status;
        entity.config = new HashMap<>();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public String getCapabilityType() {
        return capabilityType;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getVersion() {
        return version;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
