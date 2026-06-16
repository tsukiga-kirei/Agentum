package com.agentum.delivery.domain;

import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
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

// 交付记录是业务闭环的落点，文件、邮件、Webhook 和失败重试都从这里追溯。
@Entity
@Table(name = "delivery_records")
public class DeliveryRecordEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id", nullable = false)
    private UUID nodeRunId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "capability_id")
    private UUID capabilityId;

    @Column(name = "delivery_type", nullable = false, length = 40)
    private String deliveryType;

    @Column(length = 300)
    private String target;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> resultSnapshot;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DeliveryRecordEntity() {
    }

    public static DeliveryRecordEntity started(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity nodeRun,
        SystemCapabilityEntity capability,
        String deliveryType,
        String target,
        String title,
        Map<String, Object> payload,
        UUID operatorUserId,
        Instant now
    ) {
        DeliveryRecordEntity entity = new DeliveryRecordEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = run.getTenantId();
        entity.runId = run.getId();
        entity.nodeRunId = nodeRun.getId();
        entity.workflowId = run.getWorkflowId();
        entity.workflowVersionId = run.getWorkflowVersionId();
        entity.capabilityId = capability == null ? null : capability.getId();
        entity.deliveryType = deliveryType;
        entity.target = target;
        entity.title = title;
        entity.status = "running";
        entity.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        entity.resultSnapshot = new HashMap<>();
        entity.createdBy = operatorUserId;
        entity.createdAt = now;
        return entity;
    }

    public void succeed(Map<String, Object> resultSnapshot, Instant now) {
        this.status = "success";
        this.resultSnapshot = resultSnapshot == null ? new HashMap<>() : new HashMap<>(resultSnapshot);
        this.completedAt = now;
    }

    public void fail(String errorCode, String errorMessage, Instant now) {
        this.status = "failed";
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);
        this.completedAt = now;
    }

    public void expire(Instant now) {
        this.status = "expired";
        this.errorCode = "DELIVERY_DOCUMENT_EXPIRED";
        this.errorMessage = "交付文档已超过保留期限并清理";
        this.completedAt = now;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getDeliveryType() {
        return deliveryType;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getResultSnapshot() {
        return resultSnapshot;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getNodeRunId() {
        return nodeRunId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getWorkflowVersionId() {
        return workflowVersionId;
    }

    public UUID getCapabilityId() {
        return capabilityId;
    }

    public String getTarget() {
        return target;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
