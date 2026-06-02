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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "connectivity_status", nullable = false, length = 30)
    private String connectivityStatus;

    @Column(name = "connectivity_checked_at")
    private Instant connectivityCheckedAt;

    protected SystemCapabilityEntity() {
    }

    public static SystemCapabilityEntity create(
        String capabilityType,
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String status,
        Map<String, Object> config,
        Instant now
    ) {
        SystemCapabilityEntity entity = new SystemCapabilityEntity();
        entity.id = UUID.randomUUID();
        entity.capabilityType = capabilityType;
        entity.name = name;
        entity.code = code;
        entity.version = version == null ? "v1" : version;
        entity.description = description == null ? "" : description;
        entity.riskLevel = riskLevel == null ? "low" : riskLevel;
        entity.status = status == null ? "draft" : status;
        entity.config = config == null ? new HashMap<>() : new HashMap<>(config);
        entity.connectivityStatus = "offline";
        entity.connectivityCheckedAt = null;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void updateProfile(
        String capabilityType,
        String name,
        String version,
        String description,
        String riskLevel,
        String status,
        Map<String, Object> config,
        Instant now
    ) {
        this.capabilityType = capabilityType;
        this.name = name;
        this.version = version == null ? "v1" : version;
        this.description = description == null ? "" : description;
        this.riskLevel = riskLevel == null ? "low" : riskLevel;
        this.status = status == null ? "draft" : status;
        this.config = config == null ? new HashMap<>() : new HashMap<>(config);
        resetConnectivity(now);
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

    public String getDescription() {
        return description;
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

    public String getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getConnectivityCheckedAt() {
        return connectivityCheckedAt;
    }
}
