package com.agentum.workbench.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 业务工作台契约。
 *
 * <p>第一阶段的业务工作台聚合“概览统计 + 可发起的已发布流程 + 我的待办 + 最近任务运行”。
 * 由于工作流运行态、待办与运行实例仍在建设中，{@code pendingTodos} 与 {@code recentRuns}
 * 在当前阶段返回空数组，并通过 {@code runtimeAvailable=false} 通知前端展示“运行态建设中”空态。
 * 待运行实例、节点运行、暂停事件等表落地后，再扩展待办与最近运行的真实数据源。</p>
 */
public final class WorkbenchApi {

    private WorkbenchApi() {
    }

    /**
     * 业务工作台概览响应。
     *
     * @param metrics            概览统计指标
     * @param pendingTodos       我的待办（当前阶段为空数组）
     * @param recentRuns         最近任务运行（当前阶段为空数组）
     * @param runtimeAvailable   工作流运行态是否已上线
     * @param runtimeStatusLabel 运行态状态文案，例如“运行态建设中”
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
     * <p>{@code pendingTodoTotal} 与 {@code runningRunTotal} 在运行态未上线前固定为 0，
     * 前端需配合 {@code runtimeAvailable} 展示运行态建设中提示。</p>
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
     * 可发起的已发布工作流模板。
     *
     * <p>对应租户内至少存在一个冻结版本且 {@code launch_enabled=true} 的流程。
     * 设计态在已发布后再次编辑会回到草稿状态，但旧版本未收回时仍可从业务入口发起；
     * 第一阶段流程发起入口未接入运行实例，前端点击后只跳转到流程详情/设计预览或提示运行态待上线。</p>
     */
    public record AvailableWorkflowRow(
        UUID id,
        String name,
        String description,
        int nodeCount,
        int latestVersionNumber,
        Instant publishedAt,
        UUID ownerId,
        String ownerName
    ) {
    }

    /**
     * 我的待办。
     *
     * <p>第一阶段运行态未上线，待办列表暂时返回空数组；保留字段定义便于后续接入
     * {@code WorkflowRun + NodeRun + WaitingEvent} 后直接落库不再改契约。</p>
     */
    public record PendingTodoRow(
        UUID id,
        String title,
        String workflowName,
        String waitingReason,
        String waitingFor,
        String action,
        Instant dueAt
    ) {
    }

    /**
     * 最近任务运行。
     *
     * <p>同样为运行态预留契约，运行态上线前固定返回空数组。</p>
     */
    public record RecentRunRow(
        UUID id,
        String workflowName,
        String state,
        String currentNode,
        String ownerName,
        int completedNodeCount,
        int totalNodeCount,
        Instant updatedAt
    ) {
    }
}
