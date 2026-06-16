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

// 变量快照是运行态的数据勾稽来源，节点输出、交付和审计都通过变量名回看当时的值。
@Entity
@Table(name = "variable_snapshots")
public class WorkflowVariableSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id")
    private UUID nodeRunId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "variable_name", nullable = false, length = 120)
    private String variableName;

    @Column(name = "value_type", nullable = false, length = 40)
    private String valueType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> valueSnapshot;

    @Column(name = "source_node_key", length = 120)
    private String sourceNodeKey;

    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "delivery_visible", nullable = false)
    private boolean deliveryVisible;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowVariableSnapshotEntity() {
    }

    public static WorkflowVariableSnapshotEntity create(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity nodeRun,
        String variableName,
        Object value,
        boolean sensitive,
        boolean deliveryVisible,
        Instant now
    ) {
        WorkflowVariableSnapshotEntity entity = new WorkflowVariableSnapshotEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = run.getTenantId();
        entity.runId = run.getId();
        entity.nodeRunId = nodeRun.getId();
        entity.workflowId = run.getWorkflowId();
        entity.workflowVersionId = run.getWorkflowVersionId();
        entity.variableName = variableName;
        entity.valueType = valueType(value);
        entity.valueSnapshot = new HashMap<>();
        entity.valueSnapshot.put("value", value);
        entity.sourceNodeKey = nodeRun.getNodeKey();
        entity.sensitive = sensitive;
        entity.deliveryVisible = deliveryVisible;
        entity.createdAt = now;
        return entity;
    }

    private static String valueType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof Iterable<?>) {
            return "array";
        }
        return "string";
    }

    public String getVariableName() {
        return variableName;
    }

    public Map<String, Object> getValueSnapshot() {
        return valueSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
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

    public String getValueType() {
        return valueType;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isDeliveryVisible() {
        return deliveryVisible;
    }
}
