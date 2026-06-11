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

/**
 * 智能体集群子智能体运行记录。
 *
 * <p>子智能体结果逐个落库（而不是只写节点 output_snapshot）有两个业务原因：
 * 1) 并发执行时避免多个线程竞争更新同一行 JSONB；
 * 2) 被动「恢复进度」时可以只重跑失败的子智能体，已成功结果直接复用，损失最小。</p>
 */
@Entity
@Table(name = "workflow_cluster_agent_runs")
public class WorkflowClusterAgentRunEntity {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELED = "canceled";

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id", nullable = false)
    private UUID nodeRunId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_index", nullable = false)
    private int agentIndex;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> output;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 600)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowClusterAgentRunEntity() {
    }

    public static WorkflowClusterAgentRunEntity started(
        UUID runId,
        UUID nodeRunId,
        UUID tenantId,
        int agentIndex,
        String name,
        Instant now
    ) {
        WorkflowClusterAgentRunEntity entity = new WorkflowClusterAgentRunEntity();
        entity.id = UUID.randomUUID();
        entity.runId = runId;
        entity.nodeRunId = nodeRunId;
        entity.tenantId = tenantId;
        entity.agentIndex = agentIndex;
        entity.name = name;
        entity.status = STATUS_RUNNING;
        entity.output = new HashMap<>();
        entity.startedAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void succeed(Map<String, Object> output, Instant now) {
        this.status = STATUS_SUCCEEDED;
        this.output = output == null ? new HashMap<>() : new HashMap<>(output);
        this.errorCode = null;
        this.errorMessage = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    /** 用户在运行详情中修改子智能体最终答案时，只更新该子智能体的输出快照，不触发重新执行。 */
    public void patchOutput(Map<String, Object> output, Instant now) {
        this.output = output == null ? new HashMap<>() : new HashMap<>(output);
        this.updatedAt = now;
    }

    public void fail(String errorCode, String errorMessage, Instant now) {
        this.status = STATUS_FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null || errorMessage.length() <= 600 ? errorMessage : errorMessage.substring(0, 600);
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = STATUS_CANCELED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public boolean isSucceeded() {
        return STATUS_SUCCEEDED.equals(status);
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

    public int getAgentIndex() {
        return agentIndex;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
