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

// 运行事件用于任务详情和后续审计聚合，记录发生了什么而不替代节点状态表。
@Entity
@Table(name = "workflow_run_events")
public class WorkflowRunEventEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 600)
    private String description;

    @Column(name = "node_key", length = 120)
    private String nodeKey;

    @Column(name = "operator_id")
    private UUID operatorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    protected WorkflowRunEventEntity() {
    }

    public static WorkflowRunEventEntity create(
        UUID runId,
        UUID tenantId,
        String eventType,
        String title,
        String description,
        String nodeKey,
        UUID operatorId,
        Map<String, Object> payload,
        Instant now
    ) {
        WorkflowRunEventEntity entity = new WorkflowRunEventEntity();
        entity.id = UUID.randomUUID();
        entity.runId = runId;
        entity.tenantId = tenantId;
        entity.eventType = eventType;
        entity.title = title;
        entity.description = description;
        entity.nodeKey = nodeKey;
        entity.operatorId = operatorId;
        entity.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        entity.eventTime = now;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public Instant getEventTime() {
        return eventTime;
    }
}
