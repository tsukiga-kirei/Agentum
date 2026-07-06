package com.agentum.audit.application;

import com.agentum.agent.application.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运行实例全链路审计证据链 DTO。
 * 包含运行状态、节点运行记录、变量快照、轨迹事件流、AI模型调用日志、MCP调用日志和交付记录。
 */
public record AuditEvidenceDto(
    WorkflowRunInfo runInfo,
    TokenUsage tokenUsage,
    List<NodeRunInfo> nodeRuns,
    List<VariableSnapshotInfo> variableSnapshots,
    List<RunEventInfo> runEvents,
    List<ModelCallLogInfo> modelCallLogs,
    List<McpCallLogInfo> mcpCallLogs,
    List<DeliveryRecordInfo> deliveryRecords
) {

    public record WorkflowRunInfo(
        UUID id,
        String title,
        String workflowName,
        int versionNumber,
        String triggerSource,
        UUID triggerScheduleId,
        Map<String, Object> triggerPayload,
        String state,
        Instant startedAt,
        Instant completedAt,
        String operatorName
    ) {}

    public record NodeRunInfo(
        UUID id,
        String nodeKey,
        String nodeType,
        String name,
        String state,
        String stateLabel,
        Map<String, Object> inputSnapshot,
        Map<String, Object> outputSnapshot,
        Map<String, Object> configSnapshot,
        Instant startedAt,
        Instant completedAt
    ) {}

    public record VariableSnapshotInfo(
        UUID id,
        UUID nodeRunId,
        String variableName,
        String valueType,
        Object value, // 经过脱敏的值
        String sourceNodeKey,
        boolean sensitive,
        boolean deliveryVisible,
        Instant createdAt
    ) {}

    public record RunEventInfo(
        UUID id,
        String eventType,
        String title,
        String description,
        String nodeKey,
        String operatorName,
        Instant eventTime
    ) {}

    public record ModelCallLogInfo(
        UUID id,
        UUID nodeRunId,
        String modelName,
        String status,
        Map<String, Object> promptSnapshot, // 经过脱敏
        Map<String, Object> responseSnapshot, // 经过脱敏
        TokenUsage tokenUsage,
        Long latencyMs,
        Instant createdAt,
        Instant completedAt
    ) {}

    public record McpCallLogInfo(
        UUID id,
        UUID nodeRunId,
        String toolName,
        String capabilityCode,
        String status,
        Map<String, Object> requestPayload, // 经过脱敏
        Map<String, Object> responsePayload, // 经过脱敏
        Long latencyMs,
        Instant createdAt,
        Instant completedAt
    ) {}

    public record DeliveryRecordInfo(
        UUID id,
        UUID nodeRunId,
        String deliveryType,
        String target,
        String title,
        String status,
        Map<String, Object> payload, // 经过脱敏
        Map<String, Object> resultSnapshot, // 经过脱敏
        String errorMessage,
        Instant createdAt,
        Instant completedAt
    ) {}
}
