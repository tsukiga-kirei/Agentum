package com.agentum.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_access_grants")
public class WorkflowAccessGrantEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "grantee_user_id", nullable = false)
    private UUID granteeUserId;

    @Column(name = "access_level", nullable = false, length = 20)
    private String accessLevel;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowAccessGrantEntity() {
    }

    public static WorkflowAccessGrantEntity create(
        UUID tenantId,
        UUID workflowId,
        UUID granteeUserId,
        String accessLevel,
        UUID operatorUserId,
        Instant now
    ) {
        WorkflowAccessGrantEntity entity = new WorkflowAccessGrantEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.workflowId = workflowId;
        entity.granteeUserId = granteeUserId;
        entity.accessLevel = accessLevel;
        entity.createdBy = operatorUserId;
        entity.createdAt = now;
        return entity;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getGranteeUserId() {
        return granteeUserId;
    }

    public String getAccessLevel() {
        return accessLevel;
    }
}
