package com.agentum.workflow.domain;

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

// 等待事件是业务待办的事实来源，所有恢复动作都必须先解析到这张表再推进运行状态。
@Entity
@Table(name = "workflow_waiting_events")
public class WorkflowWaitingEventEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id", nullable = false)
    private UUID nodeRunId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_key", nullable = false, length = 120)
    private String nodeKey;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(name = "waiting_reason", nullable = false, length = 300)
    private String waitingReason;

    @Column(name = "waiting_for_type", nullable = false, length = 30)
    private String waitingForType;

    @Column(name = "waiting_for_id")
    private UUID waitingForId;

    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected WorkflowWaitingEventEntity() {
    }

    public static WorkflowWaitingEventEntity openForUser(
        UUID runId,
        UUID nodeRunId,
        UUID tenantId,
        UUID workflowId,
        String nodeKey,
        String title,
        String waitingReason,
        UUID waitingForUserId,
        String actionType,
        Map<String, Object> payload,
        Instant now
    ) {
        WorkflowWaitingEventEntity entity = new WorkflowWaitingEventEntity();
        entity.id = UUID.randomUUID();
        entity.runId = runId;
        entity.nodeRunId = nodeRunId;
        entity.tenantId = tenantId;
        entity.workflowId = workflowId;
        entity.nodeKey = nodeKey;
        entity.title = title;
        entity.waitingReason = waitingReason;
        entity.waitingForType = "user";
        entity.waitingForId = waitingForUserId;
        entity.actionType = actionType;
        entity.status = "open";
        entity.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        entity.createdAt = now;
        return entity;
    }

    public void resolve(UUID operatorUserId, Instant now) {
        this.status = "resolved";
        this.resolvedBy = operatorUserId;
        this.resolvedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getNodeRunId() {
        return nodeRunId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getTitle() {
        return title;
    }

    public String getWaitingReason() {
        return waitingReason;
    }

    public String getWaitingForType() {
        return waitingForType;
    }

    public UUID getWaitingForId() {
        return waitingForId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
