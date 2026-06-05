package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 运行实例只引用不可变发布版本，避免设计态草稿后续修改影响已创建任务的执行链路。
@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity {

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

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "workflow_name", nullable = false, length = 180)
    private String workflowName;

    @Column(nullable = false, length = 30)
    private String state;

    @Column(name = "current_node_key", length = 120)
    private String currentNodeKey;

    @Column(name = "current_node_name", length = 160)
    private String currentNodeName;

    @Column(name = "current_node_type", length = 40)
    private String currentNodeType;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "total_node_count", nullable = false)
    private int totalNodeCount;

    @Column(name = "completed_node_count", nullable = false)
    private int completedNodeCount;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // 未保存的草稿只在发起页面临时存在，退出后删除，不进入待办列表。
    @Column(nullable = false)
    private boolean saved;

    // 运行编号含日期前缀，供待办和任务记录统一展示。
    @Column(name = "run_number", nullable = false, length = 40)
    private String runNumber;

    protected WorkflowRunEntity() {
    }

    public static WorkflowRunEntity create(
        UUID tenantId,
        UUID workflowId,
        UUID workflowVersionId,
        int workflowVersionNumber,
        String title,
        String workflowName,
        UUID createdBy,
        int totalNodeCount,
        String runNumber,
        Instant now
    ) {
        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.workflowId = workflowId;
        entity.workflowVersionId = workflowVersionId;
        entity.workflowVersionNumber = workflowVersionNumber;
        entity.title = title;
        entity.workflowName = workflowName;
        entity.state = "running";
        entity.createdBy = createdBy;
        entity.totalNodeCount = totalNodeCount;
        entity.completedNodeCount = 0;
        entity.progressPercent = 0;
        entity.saved = false;
        entity.runNumber = runNumber;
        entity.startedAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void markSaved(Instant now) {
        this.saved = true;
        this.updatedAt = now;
    }

    public void updateTitle(String title, Instant now) {
        this.title = title;
        this.updatedAt = now;
    }

    public void pauseAt(String nodeKey, String nodeName, String nodeType, int completedNodeCount, Instant now) {
        this.state = "paused";
        this.currentNodeKey = nodeKey;
        this.currentNodeName = nodeName;
        this.currentNodeType = nodeType;
        updateProgress(completedNodeCount);
        this.updatedAt = now;
    }

    public void complete(int completedNodeCount, Instant now) {
        this.state = "completed";
        this.currentNodeKey = null;
        this.currentNodeName = null;
        this.currentNodeType = null;
        updateProgress(completedNodeCount);
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void failAt(String nodeKey, String nodeName, String nodeType, int completedNodeCount, Instant now) {
        this.state = "failed";
        this.currentNodeKey = nodeKey;
        this.currentNodeName = nodeName;
        this.currentNodeType = nodeType;
        updateProgress(completedNodeCount);
        this.updatedAt = now;
    }

    public void markRunning(String nodeKey, String nodeName, String nodeType, int completedNodeCount, Instant now) {
        this.state = "running";
        this.currentNodeKey = nodeKey;
        this.currentNodeName = nodeName;
        this.currentNodeType = nodeType;
        updateProgress(completedNodeCount);
        this.updatedAt = now;
    }

    private void updateProgress(int completedNodeCount) {
        this.completedNodeCount = completedNodeCount;
        this.progressPercent = totalNodeCount <= 0 ? 100 : Math.min(100, Math.round((completedNodeCount * 100f) / totalNodeCount));
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getWorkflowVersionId() {
        return workflowVersionId;
    }

    public int getWorkflowVersionNumber() {
        return workflowVersionNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public String getState() {
        return state;
    }

    public String getCurrentNodeKey() {
        return currentNodeKey;
    }

    public String getCurrentNodeName() {
        return currentNodeName;
    }

    public String getCurrentNodeType() {
        return currentNodeType;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public int getTotalNodeCount() {
        return totalNodeCount;
    }

    public int getCompletedNodeCount() {
        return completedNodeCount;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isSaved() {
        return saved;
    }

    public String getRunNumber() {
        return runNumber;
    }
}
