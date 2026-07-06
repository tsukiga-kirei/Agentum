package com.agentum.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 每次定时触发都单独留痕，运行成功或中止都能在个人定时任务页和审计侧回放。
@Entity
@Table(name = "workflow_schedule_executions")
public class WorkflowScheduleExecutionEntity {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_ABORTED = "aborted";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(length = 600)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowScheduleExecutionEntity() {
    }

    public static WorkflowScheduleExecutionEntity running(WorkflowScheduleEntity schedule, Instant scheduledAt, Instant now) {
        WorkflowScheduleExecutionEntity entity = new WorkflowScheduleExecutionEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = schedule.getTenantId();
        entity.scheduleId = schedule.getId();
        entity.workflowId = schedule.getWorkflowId();
        entity.workflowVersionId = schedule.getWorkflowVersionId();
        entity.ownerId = schedule.getOwnerId();
        entity.status = STATUS_RUNNING;
        entity.scheduledAt = scheduledAt;
        entity.startedAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void bindRun(UUID runId, Instant now) {
        this.runId = runId;
        this.updatedAt = now;
    }

    public void succeed(String message, Instant now) {
        this.status = STATUS_SUCCEEDED;
        this.message = message;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void abort(String message, Instant now) {
        this.status = STATUS_ABORTED;
        this.message = message;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getScheduleId() { return scheduleId; }
    public UUID getRunId() { return runId; }
    public UUID getWorkflowId() { return workflowId; }
    public UUID getWorkflowVersionId() { return workflowVersionId; }
    public UUID getOwnerId() { return ownerId; }
    public String getStatus() { return status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
