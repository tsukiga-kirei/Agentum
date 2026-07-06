package com.agentum.schedule.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowScheduleApi {

    private WorkflowScheduleApi() {
    }

    public record ScheduleRow(
        UUID id,
        UUID workflowId,
        String workflowName,
        int workflowVersionNumber,
        String name,
        String cronExpression,
        String shortcutKey,
        String shortcutLabel,
        String status,
        Map<String, Object> inputPayload,
        Instant nextRunAt,
        Instant lastRunAt,
        UUID lastRunId,
        String lastRunState,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record InputFieldRow(
        String nodeId,
        String nodeName,
        String variable,
        String label,
        String placeholder,
        boolean required,
        String valueType
    ) {
    }

    public record WorkflowInputFieldsResponse(
        UUID workflowId,
        String workflowName,
        int workflowVersionNumber,
        List<InputFieldRow> inputFields
    ) {
    }

    public record CreateScheduleRequest(
        UUID workflowId,
        String name,
        String cronExpression,
        String shortcutKey,
        String shortcutLabel,
        Map<String, Object> inputPayload
    ) {
    }

    public record UpdateScheduleRequest(
        String name,
        String cronExpression,
        String shortcutKey,
        String shortcutLabel,
        String status,
        Map<String, Object> inputPayload
    ) {
    }

    public record ScheduleExecutionRow(
        UUID id,
        UUID scheduleId,
        UUID runId,
        String status,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        String message
    ) {
    }
}
