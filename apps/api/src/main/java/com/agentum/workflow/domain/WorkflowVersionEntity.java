package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 发布版本是运行态后续唯一可信的定义来源；草稿继续可编辑，但历史版本必须保持不可变。
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "definition_snapshot", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String definitionSnapshot;

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "pause_point_count", nullable = false)
    private int pausePointCount;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected WorkflowVersionEntity() {
    }

    public static WorkflowVersionEntity create(
        UUID workflowId,
        UUID tenantId,
        int versionNumber,
        String definitionSnapshot,
        int nodeCount,
        int pausePointCount,
        UUID publishedBy,
        Instant publishedAt
    ) {
        WorkflowVersionEntity entity = new WorkflowVersionEntity();
        entity.id = UUID.randomUUID();
        entity.workflowId = workflowId;
        entity.tenantId = tenantId;
        entity.versionNumber = versionNumber;
        entity.definitionSnapshot = definitionSnapshot;
        entity.nodeCount = nodeCount;
        entity.pausePointCount = pausePointCount;
        entity.publishedBy = publishedBy;
        entity.publishedAt = publishedAt;
        return entity;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
