package com.agentum.workflow.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowDraftApi {

    private WorkflowDraftApi() {
    }

    public record WorkflowDraftRow(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        String status,
        int nodeCount,
        int pausePointCount,
        String ownerName,
        Instant updatedAt
    ) {
    }

    public record WorkflowDraftDetail(
        WorkflowDraftRow draft,
        List<WorkflowNodeRow> nodes,
        List<WorkflowEdgeRow> edges
    ) {
    }

    public record WorkflowNodeRow(
        String nodeId,
        String nodeType,
        String name,
        double positionX,
        double positionY,
        List<String> inputVariables,
        List<String> outputVariables,
        Map<String, Object> config
    ) {
    }

    public record WorkflowEdgeRow(
        String edgeId,
        String sourceNodeId,
        String targetNodeId,
        String label,
        String conditionExpression
    ) {
    }

    public record CreateWorkflowDraftRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String description
    ) {
    }

    public record SaveWorkflowDraftGraphRequest(
        @Valid @NotNull List<WorkflowNodeDraft> nodes,
        @Valid @NotNull List<WorkflowEdgeDraft> edges
    ) {
    }

    public record WorkflowNodeDraft(
        @NotBlank @Size(max = 120) String nodeId,
        @NotBlank @Size(max = 40) String nodeType,
        @NotBlank @Size(max = 160) String name,
        double positionX,
        double positionY,
        List<String> inputVariables,
        List<String> outputVariables,
        Map<String, Object> config
    ) {
    }

    public record WorkflowEdgeDraft(
        @NotBlank @Size(max = 120) String edgeId,
        @NotBlank @Size(max = 120) String sourceNodeId,
        @NotBlank @Size(max = 120) String targetNodeId,
        @Size(max = 120) String label,
        @Size(max = 1000) String conditionExpression
    ) {
    }
}
