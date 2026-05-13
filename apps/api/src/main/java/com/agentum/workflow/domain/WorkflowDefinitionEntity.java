package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 工作流定义是设计态入口，运行实例和发布版本后续独立建模，避免草稿修改影响已运行流程。
@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 180)
    private String name;

    private String description;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "pause_point_count", nullable = false)
    private int pausePointCount;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowDefinitionEntity() {
    }

    public static WorkflowDefinitionEntity create(UUID tenantId, String name, String description, UUID operatorUserId, Instant now) {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.name = name;
        entity.description = description;
        entity.status = "draft";
        entity.nodeCount = 0;
        entity.pausePointCount = 0;
        entity.createdBy = operatorUserId;
        entity.updatedBy = operatorUserId;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void updateGraphSummary(int nodeCount, int pausePointCount, UUID operatorUserId, Instant now) {
        this.nodeCount = nodeCount;
        this.pausePointCount = pausePointCount;
        this.updatedBy = operatorUserId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getPausePointCount() {
        return pausePointCount;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
