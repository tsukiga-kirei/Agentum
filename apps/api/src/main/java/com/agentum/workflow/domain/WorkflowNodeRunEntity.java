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

// 节点运行保存的是发布版本当时的节点快照和执行状态，运行详情不再回读设计态草稿节点。
@Entity
@Table(name = "workflow_node_runs")
public class WorkflowNodeRunEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "node_key", nullable = false, length = 120)
    private String nodeKey;

    @Column(name = "node_type", nullable = false, length = 40)
    private String nodeType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 30)
    private String state;

    @Column(name = "state_label", nullable = false, length = 80)
    private String stateLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outputSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configSnapshot;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowNodeRunEntity() {
    }

    public static WorkflowNodeRunEntity pending(
        UUID runId,
        UUID tenantId,
        UUID workflowId,
        UUID workflowVersionId,
        String nodeKey,
        String nodeType,
        String name,
        Map<String, Object> inputSnapshot,
        Map<String, Object> outputSnapshot,
        Map<String, Object> configSnapshot,
        int sortOrder,
        Instant now
    ) {
        WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
        entity.id = UUID.randomUUID();
        entity.runId = runId;
        entity.tenantId = tenantId;
        entity.workflowId = workflowId;
        entity.workflowVersionId = workflowVersionId;
        entity.nodeKey = nodeKey;
        entity.nodeType = nodeType;
        entity.name = name;
        entity.state = "pending";
        entity.stateLabel = "等待中";
        entity.inputSnapshot = inputSnapshot == null ? new HashMap<>() : new HashMap<>(inputSnapshot);
        entity.outputSnapshot = outputSnapshot == null ? new HashMap<>() : new HashMap<>(outputSnapshot);
        entity.configSnapshot = configSnapshot == null ? new HashMap<>() : new HashMap<>(configSnapshot);
        entity.sortOrder = sortOrder;
        entity.updatedAt = now;
        return entity;
    }

    public void complete(Map<String, Object> output, Instant now) {
        this.state = "completed";
        this.stateLabel = "已完成";
        this.outputSnapshot = output == null ? new HashMap<>() : new HashMap<>(output);
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void fail(Map<String, Object> output, Instant now) {
        this.state = "failed";
        this.stateLabel = "执行失败";
        this.outputSnapshot = output == null ? new HashMap<>() : new HashMap<>(output);
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void start(Instant now) {
        this.state = "running";
        this.stateLabel = "运行中";
        this.startedAt = now;
        this.updatedAt = now;
    }

    /**
     * 运行中增量写入输出快照（如智能体集群已完成子智能体），不改变节点终态。
     */
    public void patchOutputSnapshot(Map<String, Object> partialOutput, Instant now) {
        if (partialOutput == null || partialOutput.isEmpty()) {
            return;
        }
        if (this.outputSnapshot == null) {
            this.outputSnapshot = new HashMap<>();
        }
        this.outputSnapshot.putAll(partialOutput);
        this.updatedAt = now;
    }

    public void waitForInput(Instant now) {
        this.state = "waiting";
        this.stateLabel = "等待处理";
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.updatedAt = now;
    }

    /**
     * 用户主动中断：节点进入 canceled 终态并清空输出快照。
     * canceled 与 failed 严格区分——前者只能「重新执行」整步重跑，后者可「恢复进度」复用已成功部分。
     */
    public void cancel(Instant now) {
        this.state = "canceled";
        this.stateLabel = "已中断";
        this.outputSnapshot = new HashMap<>();
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.completedAt = now;
        this.updatedAt = now;
    }

    /**
     * 追问续聊：保留对话上下文配置，清空本轮输出并重新进入运行态。
     */
    public void prepareForFollowUp(Map<String, Object> nextConfigSnapshot, Instant now) {
        this.configSnapshot = nextConfigSnapshot == null ? new HashMap<>() : new HashMap<>(nextConfigSnapshot);
        this.outputSnapshot = new HashMap<>();
        this.state = "running";
        this.stateLabel = "运行中";
        this.completedAt = null;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.updatedAt = now;
    }

    // 回退到指定步骤时，将目标节点及后续节点重置为待执行，清除已写入的输出快照。
    public void resetToPending(Instant now) {
        this.state = "pending";
        this.stateLabel = "等待中";
        this.outputSnapshot = new HashMap<>();
        this.startedAt = null;
        this.completedAt = null;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getName() {
        return name;
    }

    public String getState() {
        return state;
    }

    public String getStateLabel() {
        return stateLabel;
    }

    public Map<String, Object> getInputSnapshot() {
        return inputSnapshot;
    }

    public Map<String, Object> getOutputSnapshot() {
        return outputSnapshot;
    }

    public Map<String, Object> getConfigSnapshot() {
        return configSnapshot;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
