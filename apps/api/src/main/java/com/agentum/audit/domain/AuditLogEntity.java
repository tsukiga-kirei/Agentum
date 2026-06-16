package com.agentum.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 全局配置与权限变动操作审计日志实体。
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "operator_id")
    private UUID operatorId;

    @Column(name = "operator_name", nullable = false, length = 120)
    private String operatorName;

    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "target_type", nullable = false, length = 60)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
        // JPA 默认构造
    }

    public static AuditLogEntity record(
        UUID tenantId,
        UUID operatorId,
        String operatorName,
        String actionType,
        String targetType,
        String targetId,
        String targetName,
        String description,
        String payloadJson,
        String clientIp
    ) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.operatorId = operatorId;
        entity.operatorName = operatorName != null ? operatorName : "System";
        entity.actionType = actionType;
        entity.targetType = targetType;
        entity.targetId = targetId;
        entity.targetName = targetName;
        entity.description = description;
        entity.payload = payloadJson != null ? payloadJson : "{}";
        entity.clientIp = clientIp;
        entity.createdAt = Instant.now();
        return entity;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public String getActionType() { return actionType; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public String getDescription() { return description; }
    public String getPayload() { return payload; }
    public String getClientIp() { return clientIp; }
    public Instant getCreatedAt() { return createdAt; }
}
