package com.agentum.workbench.interfaces;

import java.time.Instant;
import java.util.List;
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
     * @param metrics            概览统计指标
     * @param pendingTodos       我的待办
     * @param recentRuns         最近任务运行
     * @param runtimeAvailable   工作流运行态是否已上线
     * @param runtimeStatusLabel 运行态状态文案，例如“运行态已接入”
     * @param generatedAt        响应生成时间，便于前端按时间戳缓存或对比
     */
    public record WorkbenchSummary(
        WorkbenchMetrics metrics,
        List<PendingTodoRow> pendingTodos,
        List<RecentRunRow> recentRuns,
        boolean runtimeAvailable,
        String runtimeStatusLabel,
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
     * 我的待办。
     */
    public record PendingTodoRow(
        UUID id,
        UUID runId,
        UUID nodeRunId,
        String title,
        String workflowName,
        String nodeName,
        String waitingReason,
        String waitingFor,
        String action,
        Instant createdAt
    ) {
    }

    /**
     * 最近任务运行。
     */
    public record RecentRunRow(
        UUID id,
        String title,
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
        java.util.Map<String, Object> payload
    ) {
    }

    public record TaskRunRow(
        UUID id,
        String title,
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
        PendingTodoRow openTodo
    ) {
    }

    public record NodeRunRow(
        UUID id,
        String nodeId,
        String nodeType,
        String name,
        String state,
        String stateLabel,
        java.util.Map<String, Object> inputs,
        java.util.Map<String, Object> outputs,
        java.util.Map<String, Object> config,
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
