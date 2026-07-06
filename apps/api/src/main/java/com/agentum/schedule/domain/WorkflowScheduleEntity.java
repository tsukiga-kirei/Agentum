package com.agentum.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 定时任务配置属于用户个人自动化入口；执行时仍按 owner 的流程读取权限和当前发布版本边界复核。
@Entity
@Table(name = "workflow_schedules")
public class WorkflowScheduleEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "workflow_version_number", nullable = false)
    private int workflowVersionNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "workflow_name", nullable = false, length = 180)
    private String workflowName;

    @Column(name = "cron_expression", nullable = false, length = 120)
    private String cronExpression;

    @Column(name = "shortcut_key", length = 40)
    private String shortcutKey;

    @Column(name = "shortcut_label", length = 80)
    private String shortcutLabel;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputPayload;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    @Column(name = "last_run_state", length = 30)
    private String lastRunState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowScheduleEntity() {
    }

    public static WorkflowScheduleEntity create(
        UUID tenantId,
        UUID workflowId,
        UUID workflowVersionId,
        int workflowVersionNumber,
        UUID ownerId,
        String name,
        String workflowName,
        String cronExpression,
        String shortcutKey,
        String shortcutLabel,
        Map<String, Object> inputPayload,
        Instant nextRunAt,
        Instant now
    ) {
        WorkflowScheduleEntity entity = new WorkflowScheduleEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.workflowId = workflowId;
        entity.workflowVersionId = workflowVersionId;
        entity.workflowVersionNumber = workflowVersionNumber;
        entity.ownerId = ownerId;
        entity.name = name;
        entity.workflowName = workflowName;
        entity.cronExpression = cronExpression;
        entity.shortcutKey = shortcutKey;
        entity.shortcutLabel = shortcutLabel;
        entity.status = STATUS_ACTIVE;
        entity.inputPayload = inputPayload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputPayload);
        entity.nextRunAt = nextRunAt;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void update(
        String name,
        UUID workflowVersionId,
        int workflowVersionNumber,
        String workflowName,
        String cronExpression,
        String shortcutKey,
        String shortcutLabel,
        Map<String, Object> inputPayload,
        Instant nextRunAt,
        Instant now
    ) {
        this.name = name;
        this.workflowVersionId = workflowVersionId;
        this.workflowVersionNumber = workflowVersionNumber;
        this.workflowName = workflowName;
        this.cronExpression = cronExpression;
        this.shortcutKey = shortcutKey;
        this.shortcutLabel = shortcutLabel;
        this.inputPayload = inputPayload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputPayload);
        this.nextRunAt = STATUS_ACTIVE.equals(this.status) ? nextRunAt : null;
        this.updatedAt = now;
    }

    public void updateStatus(String status, Instant nextRunAt, Instant now) {
        this.status = status;
        this.nextRunAt = STATUS_ACTIVE.equals(status) ? nextRunAt : null;
        this.updatedAt = now;
    }

    public void markTriggered(UUID runId, Instant lastRunAt, Instant nextRunAt, Instant now) {
        this.lastRunAt = lastRunAt;
        this.lastRunId = runId;
        this.lastRunState = "running";
        this.nextRunAt = nextRunAt;
        this.updatedAt = now;
    }

    public void markLastRunState(String state, Instant now) {
        this.lastRunState = state;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWorkflowId() { return workflowId; }
    public UUID getWorkflowVersionId() { return workflowVersionId; }
    public int getWorkflowVersionNumber() { return workflowVersionNumber; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getWorkflowName() { return workflowName; }
    public String getCronExpression() { return cronExpression; }
    public String getShortcutKey() { return shortcutKey; }
    public String getShortcutLabel() { return shortcutLabel; }
    public String getStatus() { return status; }
    public Map<String, Object> getInputPayload() { return inputPayload == null ? Map.of() : inputPayload; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public UUID getLastRunId() { return lastRunId; }
    public String getLastRunState() { return lastRunState; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
