package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 节点表只保存设计态协议和布局；执行状态、输入输出快照会进入运行态表。
@Entity
@Table(name = "workflow_node_definitions")
public class WorkflowNodeDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_key", nullable = false, length = 120)
    private String nodeKey;

    @Column(name = "node_type", nullable = false, length = 40)
    private String nodeType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "position_x", nullable = false)
    private BigDecimal positionX;

    @Column(name = "position_y", nullable = false)
    private BigDecimal positionY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_variables", nullable = false, columnDefinition = "jsonb")
    private List<String> inputVariables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_variables", nullable = false, columnDefinition = "jsonb")
    private List<String> outputVariables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowNodeDefinitionEntity() {
    }

    public static WorkflowNodeDefinitionEntity create(
        UUID workflowId,
        String nodeKey,
        String nodeType,
        String name,
        BigDecimal positionX,
        BigDecimal positionY,
        List<String> inputVariables,
        List<String> outputVariables,
        Map<String, Object> config,
        int sortOrder,
        Instant now
    ) {
        WorkflowNodeDefinitionEntity entity = new WorkflowNodeDefinitionEntity();
        entity.id = UUID.randomUUID();
        entity.workflowId = workflowId;
        entity.nodeKey = nodeKey;
        entity.nodeType = nodeType;
        entity.name = name;
        entity.positionX = positionX;
        entity.positionY = positionY;
        entity.inputVariables = inputVariables == null ? new ArrayList<>() : new ArrayList<>(inputVariables);
        entity.outputVariables = outputVariables == null ? new ArrayList<>() : new ArrayList<>(outputVariables);
        entity.config = config == null ? new HashMap<>() : new HashMap<>(config);
        entity.sortOrder = sortOrder;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
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

    public BigDecimal getPositionX() {
        return positionX;
    }

    public BigDecimal getPositionY() {
        return positionY;
    }

    public List<String> getInputVariables() {
        return inputVariables;
    }

    public List<String> getOutputVariables() {
        return outputVariables;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
