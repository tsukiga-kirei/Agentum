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
        UUID ownerId,
        String ownerName,
        String accessLevel,
        int latestVersionNumber,
        Instant latestPublishedAt,
        boolean hasUnpublishedChanges,
        boolean launchEnabled,
        Instant updatedAt
    ) {
    }

    public record WorkflowDraftDetail(
        WorkflowDraftRow draft,
        List<WorkflowNodeRow> nodes,
        List<WorkflowEdgeRow> edges,
        List<WorkflowVariableRow> variables,
        WorkflowAccessDetail access
    ) {
    }

    public record WorkflowAccessDetail(
        String readScope,
        String editScope,
        List<UUID> readUserIds,
        List<UUID> editUserIds,
        String accessLevel,
        boolean canManageAccess
    ) {
    }

    public record ShareableMemberRow(UUID userId, String username, String displayName) {
    }

    public record WorkflowPublishValidationResult(
        boolean valid,
        int nodeCount,
        int edgeCount,
        List<WorkflowValidationIssue> issues
    ) {
    }

    public record WorkflowPublishResult(
        WorkflowDraftRow draft,
        int versionNumber,
        Instant publishedAt
    ) {
    }

    public record WorkflowExportDocument(
        @NotBlank String schemaVersion,
        @NotNull Instant exportedAt,
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String description,
        UUID sourceWorkflowId,
        UUID sourceTenantId,
        int latestVersionNumber,
        List<WorkflowNodeDraft> nodes,
        List<WorkflowEdgeDraft> edges,
        List<WorkflowVariableDraft> variables
    ) {
    }

    public record ImportWorkflowDraftRequest(
        @Valid @NotNull WorkflowExportDocument document,
        @Size(max = 180) String name,
        @Size(max = 1000) String description
    ) {
    }

    public record WorkflowDesignerCatalog(
        WorkflowBrickTemplate systemTrigger,
        List<WorkflowBrickTemplate> brickTemplates,
        Map<String, WorkflowVariableTemplate> variableMetadata,
        AgentRuntimeLimits agentRuntimeLimits,
        List<WorkflowModelOption> modelOptions
    ) {
    }

    public record WorkflowModelOption(
        UUID providerId,
        String providerName,
        String providerType,
        String modelName,
        boolean reasoningModel
    ) {
    }

    public record AgentRuntimeLimits(
        int suggestedIterationsPerTurn,
        int maxIterationsPerTurn
    ) {
    }

    public record WorkflowBrickTemplate(
        String brickType,
        String label,
        String description,
        String nodeType,
        String defaultName,
        String defaultSummary,
        String outputPrefix,
        String firstOutputVariable,
        List<String> defaultInputVariables,
        List<String> defaultOutputVariables,
        Map<String, Object> defaultConfig,
        String runState,
        String outputMode,
        int toolCount,
        boolean allowQuestion
    ) {
    }

    public record WorkflowVariableTemplate(
        String type,
        boolean sensitive,
        boolean deliverable,
        String description
    ) {
    }

    public record WorkflowValidationIssue(
        String code,
        String level,
        String message,
        String nodeId,
        String nodeName
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

    public record WorkflowVariableRow(
        String name,
        String type,
        String sourceNode,
        String description,
        Map<String, Object> jsonSchema,
        boolean sensitive,
        boolean deliverable
    ) {
    }

    public record CreateWorkflowDraftRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String description,
        @Size(max = 30) String readScope,
        @Size(max = 30) String editScope,
        List<UUID> readUserIds,
        List<UUID> editUserIds
    ) {
    }

    public record UpdateWorkflowDraftRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String description
    ) {
    }

    public record UpdateWorkflowAccessRequest(
        @NotBlank @Size(max = 30) String readScope,
        @NotBlank @Size(max = 30) String editScope,
        List<UUID> readUserIds,
        List<UUID> editUserIds
    ) {
    }

    public record SaveWorkflowDraftGraphRequest(
        @Valid @NotNull List<WorkflowNodeDraft> nodes,
        @Valid @NotNull List<WorkflowEdgeDraft> edges,
        @Valid @NotNull List<WorkflowVariableDraft> variables
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

    public record WorkflowVariableDraft(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String type,
        @NotBlank @Size(max = 120) String sourceNode,
        @Size(max = 1000) String description,
        Map<String, Object> jsonSchema,
        boolean sensitive,
        boolean deliverable
    ) {
    }
}
