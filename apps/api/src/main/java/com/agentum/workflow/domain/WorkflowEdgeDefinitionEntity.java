package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 边只表达设计态路由关系；条件表达式的安全解析会在发布校验和运行态再次处理。
@Entity
@Table(name = "workflow_edge_definitions")
public class WorkflowEdgeDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "edge_key", nullable = false, length = 120)
    private String edgeKey;

    @Column(name = "source_node_key", nullable = false, length = 120)
    private String sourceNodeKey;

    @Column(name = "target_node_key", nullable = false, length = 120)
    private String targetNodeKey;

    @Column(length = 120)
    private String label;

    @Column(name = "condition_expression")
    private String conditionExpression;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowEdgeDefinitionEntity() {
    }

    public static WorkflowEdgeDefinitionEntity create(
        UUID workflowId,
        String edgeKey,
        String sourceNodeKey,
        String targetNodeKey,
        String label,
        String conditionExpression,
        int sortOrder,
        Instant now
    ) {
        WorkflowEdgeDefinitionEntity entity = new WorkflowEdgeDefinitionEntity();
        entity.id = UUID.randomUUID();
        entity.workflowId = workflowId;
        entity.edgeKey = edgeKey;
        entity.sourceNodeKey = sourceNodeKey;
        entity.targetNodeKey = targetNodeKey;
        entity.label = label;
        entity.conditionExpression = conditionExpression;
        entity.sortOrder = sortOrder;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public String getEdgeKey() {
        return edgeKey;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public String getTargetNodeKey() {
        return targetNodeKey;
    }

    public String getLabel() {
        return label;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }
}
