package com.agentum.workbench.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务工作台契约。
 *
 * <p>业务工作台聚合“概览统计 + 全部已发布流程 + 我的待办 + 任务运行”。创建任务列表会展示
 * 当前租户内全部未收回且已有发布版本的流程，但通过 {@code canLaunch} 标记当前账号是否可发起，
 * 避免用户误以为系统里不存在那些未开放流程。</p>
 */
public final class WorkbenchApi {

    private WorkbenchApi() {
    }

    /**
     * 业务工作台概览响应。
     *
     * @param metrics      概览统计指标
     * @param pendingTodos 我的待办（已保存且未完成）
     * @param recentRuns   最近已完成任务记录
     * @param generatedAt  响应生成时间，便于前端按时间戳缓存或对比
     */
    public record WorkbenchSummary(
        WorkbenchMetrics metrics,
        List<PendingTodoRow> pendingTodos,
        List<RecentRunRow> recentRuns,
        Instant generatedAt
    ) {
    }

    /**
     * 概览统计指标，全部来自当前真实数据。
     *
     */
    public record WorkbenchMetrics(
        long pendingTodoTotal,
        long runningRunTotal,
        long publishedWorkflowTotal,
        long availableWorkflowTotal,
        long openedCapabilityTotal,
        long myAssetTotal
    ) {
    }

    /**
     * 已发布工作流模板。
     *
     * <p>对应租户内至少存在一个冻结版本且 {@code launch_enabled=true} 的流程。
     * 列表返回全部流程，并通过权限字段区分“可发起 / 无权限”。</p>
     */
    public record AvailableWorkflowRow(
        UUID id,
        String name,
        String description,
        int nodeCount,
        int latestVersionNumber,
        Instant publishedAt,
        UUID ownerId,
        String ownerName,
        String visibility,
        boolean canLaunch,
        String launchBlockedReason
    ) {
    }

    /**
     * 我的待办：已主动保存且未完成的任务运行。
     */
    public record PendingTodoRow(
        UUID id,
        UUID runId,
        UUID openTodoId,
        String title,
        String runNumber,
        String workflowName,
        String currentNodeName,
        String state,
        String stateLabel,
        String waitingReason,
        String action,
        boolean hasOpenTodo,
        int progressPercent,
        int completedNodeCount,
        int totalNodeCount,
        Instant updatedAt
    ) {
    }

    /**
     * 最近已完成任务记录。
     */
    public record RecentRunRow(
        UUID id,
        String title,
        String runNumber,
        String workflowName,
        String state,
        String stateLabel,
        String currentNode,
        String ownerName,
        int completedNodeCount,
        int totalNodeCount,
        Instant updatedAt
    ) {
    }

    public record CreateRunRequest(
        UUID workflowId,
        String title
    ) {
    }

    public record CompleteTodoRequest(
        String action,
        String comment,
        Map<String, Object> payload
    ) {
    }

    public record TaskRunRow(
        UUID id,
        String title,
        String runNumber,
        String workflowName,
        int workflowVersionNumber,
        String state,
        String stateLabel,
        String currentNodeName,
        String ownerName,
        int completedNodeCount,
        int totalNodeCount,
        int progressPercent,
        boolean hasOpenTodo,
        Instant updatedAt
    ) {
    }

    public record RunDetail(
        UUID id,
        String title,
        String runNumber,
        boolean saved,
        boolean readOnly,
        UUID workflowId,
        String workflowName,
        int workflowVersionNumber,
        String state,
        String stateLabel,
        int progressPercent,
        String currentNodeKey,
        String currentNodeName,
        String currentNodeType,
        String ownerName,
        Instant startedAt,
        Instant updatedAt,
        List<NodeRunRow> nodes,
        List<RunEventRow> events,
        PendingTodoRow openTodo,
        ActiveJobInfo activeJob
    ) {
    }

    /**
     * 在途执行作业摘要：前端「进入即执行 / 刷新无感恢复」的判定依据。
     * 存在 queued/running 作业说明后端仍在执行，前端只需连 SSE 回放，绝不重复触发推进。
     */
    public record ActiveJobInfo(
        UUID jobId,
        String status,
        UUID nodeRunId,
        int attempt,
        Instant enqueuedAt,
        Instant startedAt
    ) {
    }

    public record SaveRunRequest(
        String title
    ) {
    }

    public record RollbackRunRequest(
        UUID nodeRunId
    ) {
    }

    public record FollowUpNodeRequest(
        @jakarta.validation.constraints.NotBlank String message
    ) {
    }

    public record UpdateFinalAnswerRequest(
        @jakarta.validation.constraints.NotBlank String content
    ) {
    }

    public record NodeRunRow(
        UUID id,
        String nodeId,
        String nodeType,
        String name,
        String state,
        String stateLabel,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        Map<String, Object> config,
        int sortOrder
    ) {
    }

    public record RunEventRow(
        UUID id,
        String eventType,
        String title,
        String description,
        String nodeId,
        Instant eventTime
    ) {
    }

    /**
     * 发起前预览：基于最新发布版本快照返回业务节点，便于用户在创建任务前了解流程步骤。
     */
    public record AvailableWorkflowNodeRow(
        String nodeId,
        String nodeType,
        String name,
        String summary,
        int sortOrder
    ) {
    }

    public record AvailableWorkflowPreview(
        UUID workflowId,
        int versionNumber,
        List<AvailableWorkflowNodeRow> nodes
    ) {
    }
}
