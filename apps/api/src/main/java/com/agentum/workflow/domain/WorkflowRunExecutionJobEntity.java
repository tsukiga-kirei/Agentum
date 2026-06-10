package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 节点执行作业：每次「执行节点」动作（advance / 重新执行 / 恢复进度）都会落一条作业记录。
 *
 * <p>作业是前端 activeJob 判定与超时回收（StaleExecutionReaper）的事实来源；
 * 执行进程崩溃后节点不会永远停留在 running，而是由作业状态 + Redis 租约共同判定失败。</p>
 */
@Entity
@Table(name = "workflow_run_execution_jobs")
public class WorkflowRunExecutionJobEntity {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELED = "canceled";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id", nullable = false)
    private UUID nodeRunId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "operator_id")
    private UUID operatorId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 600)
    private String errorMessage;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "worker_id", length = 120)
    private String workerId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowRunExecutionJobEntity() {
    }

    public static WorkflowRunExecutionJobEntity queued(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        int attempt,
        UUID operatorId,
        String requestId,
        Instant deadlineAt,
        Instant now
    ) {
        WorkflowRunExecutionJobEntity entity = new WorkflowRunExecutionJobEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.runId = runId;
        entity.nodeRunId = nodeRunId;
        entity.status = STATUS_QUEUED;
        entity.attempt = attempt;
        entity.idempotencyKey = runId + ":" + nodeRunId + ":" + attempt;
        entity.operatorId = operatorId;
        entity.requestId = requestId;
        entity.enqueuedAt = now;
        entity.deadlineAt = deadlineAt;
        entity.updatedAt = now;
        return entity;
    }

    public void markRunning(String workerId, Instant now) {
        this.status = STATUS_RUNNING;
        this.workerId = workerId;
        this.startedAt = now;
        this.updatedAt = now;
    }

    public void markSucceeded(Instant now) {
        this.status = STATUS_SUCCEEDED;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String errorCode, String errorMessage, Instant now) {
        this.status = STATUS_FAILED;
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage, 600);
        this.finishedAt = now;
        this.updatedAt = now;
    }

    public void markCanceled(Instant now) {
        this.status = STATUS_CANCELED;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    public boolean isTerminal() {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELED.equals(status);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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

    public String getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public String getWorkerId() {
        return workerId;
    }
}
