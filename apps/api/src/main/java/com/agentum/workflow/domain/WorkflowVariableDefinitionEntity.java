package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// 变量定义独立于节点保存，避免后续发布版本、运行快照和交付节点只能从松散字符串数组反推业务含义。
@Entity
@Table(name = "workflow_variable_definitions")
public class WorkflowVariableDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "variable_key", nullable = false, length = 120)
    private String variableKey;

    @Column(name = "variable_type", nullable = false, length = 40)
    private String variableType;

    @Column(name = "source_node_key", nullable = false, length = 120)
    private String sourceNodeKey;

    private String description;

    @Column(name = "json_schema", nullable = false, columnDefinition = "jsonb")
    private String jsonSchema;

    @Column(nullable = false)
    private boolean sensitive;

    @Column(nullable = false)
    private boolean deliverable;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowVariableDefinitionEntity() {
    }

    public static WorkflowVariableDefinitionEntity create(
        UUID workflowId,
        String variableKey,
        String variableType,
        String sourceNodeKey,
        String description,
        String jsonSchema,
        boolean sensitive,
        boolean deliverable,
        int sortOrder,
        Instant now
    ) {
        WorkflowVariableDefinitionEntity entity = new WorkflowVariableDefinitionEntity();
        entity.id = UUID.randomUUID();
        entity.workflowId = workflowId;
        entity.variableKey = variableKey;
        entity.variableType = variableType;
        entity.sourceNodeKey = sourceNodeKey;
        entity.description = description;
        entity.jsonSchema = jsonSchema;
        entity.sensitive = sensitive;
        entity.deliverable = deliverable;
        entity.sortOrder = sortOrder;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public String getVariableKey() {
        return variableKey;
    }

    public String getVariableType() {
        return variableType;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public String getDescription() {
        return description;
    }

    public String getJsonSchema() {
        return jsonSchema;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isDeliverable() {
        return deliverable;
    }
}
