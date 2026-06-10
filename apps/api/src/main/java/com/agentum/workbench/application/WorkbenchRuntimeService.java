package com.agentum.workbench.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.CollaborationAccessPolicy.AccessLevel;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.messaging.NodeExecuteCommand;
import com.agentum.runtime.messaging.NodeExecuteCommandPublisher;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowClusterAgentRunEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowRunEventEntity;
import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import com.agentum.workflow.domain.WorkflowVariableSnapshotEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.domain.WorkflowWaitingEventEntity;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowClusterAgentRunRepository;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunEventRepository;
import com.agentum.workflow.infrastructure.WorkflowRunExecutionJobRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.agentum.workflow.infrastructure.WorkflowVariableSnapshotRepository;
import com.agentum.workflow.infrastructure.WorkflowWaitingEventRepository;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbenchRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchRuntimeService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final SortWhitelist WORKFLOW_SORT = SortWhitelist.mapped(
        "updatedAt",
        Map.of(
            "updatedAt", "updatedAt",
            "name", "name",
            "nodeCount", "nodeCount",
            "publishedAt", "updatedAt"
        )
    );
    private static final SortWhitelist RUN_SORT = SortWhitelist.of("updatedAt", "title", "workflowName", "state", "startedAt", "updatedAt");

    private final TenantRepository tenantRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowAccessGrantRepository workflowAccessGrantRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowNodeRunRepository workflowNodeRunRepository;
    private final WorkflowWaitingEventRepository workflowWaitingEventRepository;
    private final WorkflowRunEventRepository workflowRunEventRepository;
    private final WorkflowVariableSnapshotRepository workflowVariableSnapshotRepository;
    private final UserAccountRepository userAccountRepository;
    private final CollaborationAccessPolicy collaborationAccessPolicy;
    private final ObjectMapper objectMapper;
    private final WorkflowRuntimeExecutor workflowRuntimeExecutor;
    private final Clock clock;
    private final WorkflowRunExecutionJobRepository jobRepository;
    private final WorkflowClusterAgentRunRepository clusterAgentRunRepository;
    private final NodeExecuteCommandPublisher commandPublisher;
    private final RunProgressStreamWriter streamWriter;
    private final RunCancellationGuard cancellationGuard;
    private final RuntimeExecutionProperties runtimeProperties;

    public WorkbenchRuntimeService(
        TenantRepository tenantRepository,
        WorkflowDefinitionRepository workflowDefinitionRepository,
        WorkflowVersionRepository workflowVersionRepository,
        WorkflowAccessGrantRepository workflowAccessGrantRepository,
        WorkflowRunRepository workflowRunRepository,
        WorkflowNodeRunRepository workflowNodeRunRepository,
        WorkflowWaitingEventRepository workflowWaitingEventRepository,
        WorkflowRunEventRepository workflowRunEventRepository,
        WorkflowVariableSnapshotRepository workflowVariableSnapshotRepository,
        UserAccountRepository userAccountRepository,
        CollaborationAccessPolicy collaborationAccessPolicy,
        ObjectMapper objectMapper,
        WorkflowRuntimeExecutor workflowRuntimeExecutor,
        Clock clock,
        WorkflowRunExecutionJobRepository jobRepository,
        WorkflowClusterAgentRunRepository clusterAgentRunRepository,
        NodeExecuteCommandPublisher commandPublisher,
        RunProgressStreamWriter streamWriter,
        RunCancellationGuard cancellationGuard,
        RuntimeExecutionProperties runtimeProperties
    ) {
        this.tenantRepository = tenantRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.workflowAccessGrantRepository = workflowAccessGrantRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowNodeRunRepository = workflowNodeRunRepository;
        this.workflowWaitingEventRepository = workflowWaitingEventRepository;
        this.workflowRunEventRepository = workflowRunEventRepository;
        this.workflowVariableSnapshotRepository = workflowVariableSnapshotRepository;
        this.userAccountRepository = userAccountRepository;
        this.collaborationAccessPolicy = collaborationAccessPolicy;
        this.objectMapper = objectMapper;
        this.workflowRuntimeExecutor = workflowRuntimeExecutor;
        this.clock = clock;
        this.jobRepository = jobRepository;
        this.clusterAgentRunRepository = clusterAgentRunRepository;
        this.commandPublisher = commandPublisher;
        this.streamWriter = streamWriter;
        this.cancellationGuard = cancellationGuard;
        this.runtimeProperties = runtimeProperties;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.AvailableWorkflowRow> listLaunchableWorkflows(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), WORKFLOW_SORT);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<WorkflowDefinitionEntity> resultPage = workflowDefinitionRepository.searchAllLaunchableWorkflows(tenantId, normalizedKeyword, pageable);

        Set<UUID> workflowIds = resultPage.getContent().stream().map(WorkflowDefinitionEntity::getId).collect(Collectors.toSet());
        Map<UUID, WorkflowVersionEntity> latestVersions = workflowIds.isEmpty()
            ? Map.of()
            : workflowVersionRepository.findLatestByWorkflowIds(workflowIds).stream()
                .collect(Collectors.toMap(WorkflowVersionEntity::getWorkflowId, Function.identity(), (left, right) -> left));
        Map<UUID, List<WorkflowAccessGrantEntity>> grantsByWorkflow = workflowIds.isEmpty()
            ? Map.of()
            : workflowAccessGrantRepository.findByWorkflowIdIn(workflowIds).stream()
                .collect(Collectors.groupingBy(WorkflowAccessGrantEntity::getWorkflowId));
        Map<UUID, UserAccount> ownersById = loadUsersById(resultPage.getContent().stream()
            .map(WorkflowDefinitionEntity::getCreatedBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));

        return PageResponse.from(resultPage.map(definition -> toAvailableWorkflow(
            definition,
            latestVersions.get(definition.getId()),
            ownersById,
            resolveAccess(definition, principal.userId(), grantsByWorkflow.getOrDefault(definition.getId(), List.of())),
            isTenantManager(principal)
        )));
    }

    @Transactional(readOnly = true)
    public WorkbenchApi.AvailableWorkflowPreview getAvailableWorkflowPreview(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID workflowId
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findByIdAndTenantId(workflowId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_DRAFT_NOT_FOUND", "流程不存在"));
        if (!definition.isLaunchEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_WORKFLOW_RECALLED", "该流程入口已被收回，暂不能查看发布节点");
        }
        WorkflowVersionEntity version = workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VERSION_REQUIRED", "流程尚未发布，无法预览节点"));
        VersionSnapshot snapshot = readSnapshot(version);
        List<SnapshotNode> snapshotNodes = snapshot.nodes() == null ? List.of() : snapshot.nodes();
        List<WorkbenchApi.AvailableWorkflowNodeRow> nodes = new ArrayList<>();
        int sortOrder = 0;
        for (SnapshotNode node : snapshotNodes) {
            if ("trigger".equals(node.nodeType())) {
                continue;
            }
            nodes.add(new WorkbenchApi.AvailableWorkflowNodeRow(
                node.nodeId(),
                node.nodeType(),
                node.name(),
                nodeSummary(node.config()),
                sortOrder++
            ));
        }
        return new WorkbenchApi.AvailableWorkflowPreview(workflowId, version.getVersionNumber(), nodes);
    }

    @Transactional
    public WorkbenchApi.RunDetail createRun(UUID tenantId, CurrentUserPrincipal principal, WorkbenchApi.CreateRunRequest request) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        UUID workflowId = request == null ? null : request.workflowId();
        if (workflowId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_WORKFLOW_ID_REQUIRED", "请选择要发起的流程");
        }
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findByIdAndTenantId(workflowId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_DRAFT_NOT_FOUND", "流程不存在"));
        WorkflowVersionEntity version = workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VERSION_REQUIRED", "流程尚未发布，无法发起任务"));
        if (!definition.isLaunchEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_WORKFLOW_RECALLED", "该流程入口已被收回，暂不能发起任务");
        }
        AccessLevel access = resolveAccess(definition, principal.userId(), workflowAccessGrantRepository.findByWorkflowId(workflowId));
        if (!isTenantManager(principal) && !access.canRead()) {
            log.warn(
                "业务任务创建被拒绝：流程未开放 tenantId={} workflowId={} userId={} requestId={}",
                tenantId,
                workflowId,
                principal.userId(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_WORKFLOW_LAUNCH_FORBIDDEN", "当前账号没有该流程的读取或发起权限");
        }

        VersionSnapshot snapshot = readSnapshot(version);
        List<SnapshotNode> snapshotNodes = snapshot.nodes() == null ? List.of() : snapshot.nodes();
        String title = normalizeTitle(request.title(), definition.getName());
        Instant now = clock.instant();
        WorkflowRunEntity run = WorkflowRunEntity.create(
            tenantId,
            definition.getId(),
            version.getId(),
            version.getVersionNumber(),
            title,
            snapshot.name() == null || snapshot.name().isBlank() ? definition.getName() : snapshot.name(),
            principal.userId(),
            snapshotNodes.size(),
            generateRunNumber(now),
            now
        );
        workflowRunRepository.save(run);

        List<WorkflowNodeRunEntity> nodeRuns = new ArrayList<>();
        for (int index = 0; index < snapshotNodes.size(); index++) {
            SnapshotNode node = snapshotNodes.get(index);
            nodeRuns.add(WorkflowNodeRunEntity.pending(
                run.getId(),
                tenantId,
                definition.getId(),
                version.getId(),
                node.nodeId(),
                node.nodeType(),
                node.name(),
                snapshotVariables(node.inputVariables(), "等待上游输入"),
                Map.of(),
                node.config(),
                index,
                now
            ));
        }
        workflowNodeRunRepository.saveAll(nodeRuns);

        List<WorkflowRunEventEntity> events = new ArrayList<>();
        events.add(WorkflowRunEventEntity.create(
            run.getId(),
            tenantId,
            "run_created",
            "任务已创建",
            "已基于流程 v" + version.getVersionNumber() + " 生成不可变运行快照。",
            null,
            principal.userId(),
            Map.of("workflowId", definition.getId().toString(), "version", version.getVersionNumber()),
            now
        ));
        workflowRunEventRepository.save(events.get(0));

        WorkflowWaitingEventEntity openTodo = advanceUntilPause(run, nodeRuns, 0, principal.userId(), now, events);
        workflowRunRepository.save(run);

        log.info(
            "业务任务创建成功 tenantId={} userId={} workflowId={} runId={} state={} requestId={}",
            tenantId,
            principal.userId(),
            workflowId,
            run.getId(),
            run.getState(),
            RequestIds.current()
        );
        return toRunDetail(run, nodeRuns, events, openTodo, loadUsersById(Set.of(principal.userId())));
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.TaskRunRow> listActiveRuns(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), RUN_SORT);
        Page<WorkflowRunEntity> resultPage = workflowRunRepository.searchVisibleActiveRuns(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            keyword == null ? "" : keyword.trim(),
            pageable
        );
        return PageResponse.from(resultPage.map(run -> toTaskRunRow(run, Map.of(), hasOpenTodo(run.getId()))));
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.TaskRunRow> listRuns(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), RUN_SORT);
        Page<WorkflowRunEntity> resultPage = workflowRunRepository.searchVisibleCompletedRuns(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            keyword == null ? "" : keyword.trim(),
            pageable
        );
        Set<UUID> userIds = resultPage.getContent().stream().map(WorkflowRunEntity::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserAccount> usersById = loadUsersById(userIds);
        return PageResponse.from(resultPage.map(run -> toTaskRunRow(run, usersById, false)));
    }

    @Transactional(readOnly = true)
    public WorkbenchApi.RunDetail getRunDetail(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = workflowRunRepository.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        assertCanReadRun(principal, run);
        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        List<WorkflowRunEventEntity> events = workflowRunEventRepository.findByRunIdOrderByEventTimeAsc(runId);
        WorkflowWaitingEventEntity openTodo = workflowWaitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(runId, "open")
            .stream()
            .findFirst()
            .orElse(null);
        return toRunDetail(run, nodes, events, openTodo, loadUsersById(run.getCreatedBy() == null ? Set.of() : Set.of(run.getCreatedBy())));
    }

    @Transactional
    public WorkbenchApi.RunDetail saveRun(UUID tenantId, CurrentUserPrincipal principal, UUID runId, WorkbenchApi.SaveRunRequest request) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if (run.isSaved()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_ALREADY_SAVED", "任务已保存，无需重复保存");
        }
        Instant now = clock.instant();
        if (request != null && request.title() != null && !request.title().isBlank()) {
            run.updateTitle(normalizeTitle(request.title(), run.getWorkflowName()), now);
        }
        run.markSaved(now);
        workflowRunRepository.save(run);
        String saveDescription = "completed".equals(run.getState())
            ? "任务已保存到任务记录，退出后仍可查看。"
            : "任务已保存到待办，退出后仍可继续处理。";
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            tenantId,
            "run_saved",
            "任务已保存",
            saveDescription,
            null,
            principal.userId(),
            Map.of("runNumber", run.getRunNumber(), "state", run.getState()),
            now
        ));
        log.info(
            "业务任务已保存 tenantId={} userId={} runId={} runNumber={} requestId={}",
            tenantId,
            principal.userId(),
            runId,
            run.getRunNumber(),
            RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    @Transactional
    public void deleteRun(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_DELETE_FORBIDDEN", "已完成任务只能查看，不能删除");
        }
        log.info(
            "业务任务删除 tenantId={} userId={} runId={} saved={} state={} requestId={}",
            tenantId,
            principal.userId(),
            runId,
            run.isSaved(),
            run.getState(),
            RequestIds.current()
        );
        workflowRunRepository.delete(run);
    }

    @Transactional
    public WorkbenchApi.RunDetail rollbackRun(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID runId,
        WorkbenchApi.RollbackRunRequest request
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if (!run.isSaved()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_NOT_SAVED", "请先保存任务后再执行回退");
        }
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_ROLLBACK_FORBIDDEN", "已完成任务不能回退，请从历史记录查看");
        }
        UUID nodeRunId = request == null ? null : request.nodeRunId();
        if (nodeRunId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_ROLLBACK_NODE_REQUIRED", "请选择要回退到的步骤");
        }
        // 回退是显式重做：执行中禁止回退，且需清除遗留取消信号。
        assertNoExecutionInFlight(runId);
        cancellationGuard.clearCancel(runId);
        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        WorkflowNodeRunEntity targetNode = nodes.stream()
            .filter(node -> node.getId().equals(nodeRunId))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"completed".equals(targetNode.getState()) && !"failed".equals(targetNode.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_ROLLBACK_NODE_INVALID", "只能回退到已执行过的步骤");
        }
        Instant now = clock.instant();
        int targetIndex = targetNode.getSortOrder();
        List<UUID> resetNodeIds = new ArrayList<>();
        for (WorkflowNodeRunEntity node : nodes) {
            if (node.getSortOrder() >= targetIndex) {
                node.resetToPending(now);
                resetNodeIds.add(node.getId());
            }
        }
        workflowNodeRunRepository.saveAll(nodes);
        resolveOpenTodos(run.getId(), principal.userId(), now);
        if (!resetNodeIds.isEmpty()) {
            workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), resetNodeIds);
            // 回退节点的子智能体落库结果一并清空，重做时从头执行。
            clusterAgentRunRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), resetNodeIds);
        }
        int completedBefore = (int) nodes.stream()
            .filter(node -> node.getSortOrder() < targetIndex && "completed".equals(node.getState()))
            .count();
        run.markRunning(targetNode.getNodeKey(), targetNode.getName(), targetNode.getNodeType(), completedBefore, now);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            tenantId,
            "run_rollback",
            "流程已回退",
            "已回退到「" + targetNode.getName() + "」并从此步骤重新开始。",
            targetNode.getNodeKey(),
            principal.userId(),
            Map.of("nodeRunId", nodeRunId.toString(), "sortOrder", targetIndex),
            now
        ));
        WorkflowWaitingEventEntity openTodo = advanceUntilPause(run, nodes, targetIndex, principal.userId(), now, new ArrayList<>());
        workflowRunRepository.save(run);
        log.info(
            "业务任务回退 tenantId={} userId={} runId={} nodeRunId={} sortOrder={} requestId={}",
            tenantId,
            principal.userId(),
            runId,
            nodeRunId,
            targetIndex,
            RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 用户主动中断当前正在执行的步骤。
     *
     * <p>语义约束（与被动失败恢复严格区分）：节点置为 canceled 并清空该节点全部运行数据
     * （输出快照、变量快照、子智能体落库结果），之后只能通过「重新执行」从头重跑整个节点；
     * 取消信号写入 Redis，执行 Worker 在模型轮次与流式回调间隙感知后协作式退出。</p>
     */
    @Transactional
    public WorkbenchApi.RunDetail interruptRun(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_INTERRUPT_FORBIDDEN", "已完成任务无需中断");
        }

        cancellationGuard.requestCancel(runId);
        Instant now = clock.instant();

        // 终态化在途作业：Worker 退出时据此识别「已被中断」，不再覆盖节点状态。
        for (WorkflowRunExecutionJobEntity job : jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        )) {
            job.markCanceled(now);
            jobRepository.save(job);
        }

        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        WorkflowNodeRunEntity runningNode = nodes.stream()
            .filter(node -> "running".equals(node.getState()))
            .findFirst()
            .orElse(null);

        if (runningNode != null) {
            // 中断即放弃：清空该节点全部运行中数据（含已完成子智能体结果），只保留 run_events 审计。
            runningNode.cancel(now);
            workflowNodeRunRepository.save(runningNode);
            workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(runningNode.getId()));
            clusterAgentRunRepository.deleteByNodeRunId(runningNode.getId());
            int completedBefore = (int) nodes.stream()
                .filter(node -> node.getSortOrder() < runningNode.getSortOrder() && "completed".equals(node.getState()))
                .count();
            run.pauseAt(runningNode.getNodeKey(), runningNode.getName(), runningNode.getNodeType(), completedBefore, now);
            workflowRunRepository.save(run);
            workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                run.getId(),
                tenantId,
                "run_interrupted",
                "步骤已中断",
                "用户已中断「" + runningNode.getName() + "」，该步骤数据已清空，可点击重新执行从头重跑。",
                runningNode.getNodeKey(),
                principal.userId(),
                Map.of("nodeRunId", runningNode.getId().toString()),
                now
            ));
            log.info(
                "用户中断任务步骤 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
                tenantId,
                principal.userId(),
                runId,
                runningNode.getId(),
                RequestIds.current()
            );
        }

        // 通知所有已连接的 SSE 中继收尾，前端按节点 canceled 状态展示「重新执行」。
        Map<String, Object> pausedPayload = new LinkedHashMap<>();
        pausedPayload.put("runId", runId.toString());
        pausedPayload.put("timestamp", now.toString());
        pausedPayload.put("reason", "用户已中断当前步骤，可点击重新执行从头重跑");
        if (runningNode != null) {
            pausedPayload.put("nodeRunId", runningNode.getId().toString());
        }
        streamWriter.append(runId, "run_paused", pausedPayload);
        streamWriter.append(runId, "message", "[DONE]");

        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 推进执行下一节点：等待类节点同步落待办；智能体/集群/交付节点创建执行作业并投递 MQ（202 语义），
     * 真实执行由 Worker 完成，进度通过 Redis Stream + SSE 中继回放。
     */
    public WorkbenchApi.RunDetail advanceRun(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if (!"completed".equals(run.getState())) {
            assertNoExecutionInFlight(runId);
            cancellationGuard.clearCancel(runId);
            NextNodeResult next = prepareNextNode(tenantId, runId, principal.userId());
            if (next.hasNext() && !next.paused() && requiresManualAdvance(next.nodeType())) {
                enqueueExecution(tenantId, runId, next.nodeRunId(), next.nodeType(), principal.userId());
            }
        }
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 主动「重新执行」：清空节点全部数据（含已成功子智能体结果）后从头重跑整个节点。
     * 用于用户主动中断（canceled）后的整步重做，优先级高于被动恢复。
     */
    public WorkbenchApi.RunDetail restartNode(UUID tenantId, CurrentUserPrincipal principal, UUID runId, UUID nodeRunId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能重新执行");
        }
        assertNoExecutionInFlight(runId);
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!isRestartableState(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_NODE_RESTART_INVALID", "当前步骤状态不支持重新执行");
        }
        prepareNodeReExecution(run, node, true, principal.userId(), "run_node_restarted", "步骤重新执行",
            "已清空「" + node.getName() + "」全部执行数据，开始从头重新执行。");
        enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        log.info(
            "用户重新执行节点 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 被动「恢复进度」：保留已成功子智能体的落库结果，仅重跑失败/未完成部分，损失最小。
     * 若节点是用户主动中断（canceled），数据已被清空，自动降级为整步重新执行。
     */
    public WorkbenchApi.RunDetail recoverNode(UUID tenantId, CurrentUserPrincipal principal, UUID runId, UUID nodeRunId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能恢复执行");
        }
        assertNoExecutionInFlight(runId);
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!isRecoverableState(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_NODE_RECOVER_INVALID", "当前步骤状态不支持恢复进度");
        }
        boolean fullRestart = "canceled".equals(node.getState());
        prepareNodeReExecution(run, node, fullRestart, principal.userId(), "run_node_recovered", "步骤恢复执行",
            fullRestart
                ? "该步骤曾被主动中断，数据已清空，已降级为从头重新执行。"
                : "已保留「" + node.getName() + "」已成功的子智能体结果，仅重跑失败或未完成部分。");
        enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        log.info(
            "用户恢复节点执行 tenantId={} userId={} runId={} nodeRunId={} fullRestart={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, fullRestart, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 重新执行/恢复进度共用的节点复位逻辑。
     *
     * @param fullRestart true 时清空全部数据（含已成功子智能体结果）；false 时仅清理失败/未完成的子智能体行
     */
    private void prepareNodeReExecution(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        boolean fullRestart,
        UUID operatorUserId,
        String eventType,
        String eventTitle,
        String eventDescription
    ) {
        Instant now = clock.instant();
        cancellationGuard.clearCancel(run.getId());
        if (fullRestart) {
            clusterAgentRunRepository.deleteByNodeRunId(node.getId());
        } else {
            clusterAgentRunRepository.deleteByNodeRunIdAndStatusNot(node.getId(), WorkflowClusterAgentRunEntity.STATUS_SUCCEEDED);
        }
        workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(node.getId()));
        node.resetToPending(now);
        node.start(now);
        workflowNodeRunRepository.save(node);
        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId());
        int completedBefore = (int) nodes.stream()
            .filter(other -> other.getSortOrder() < node.getSortOrder() && "completed".equals(other.getState()))
            .count();
        run.markRunning(node.getNodeKey(), node.getName(), node.getNodeType(), completedBefore, now);
        workflowRunRepository.save(run);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            eventType,
            eventTitle,
            eventDescription,
            node.getNodeKey(),
            operatorUserId,
            Map.of("nodeRunId", node.getId().toString(), "fullRestart", fullRestart),
            now
        ));
    }

    /**
     * 创建执行作业并投递 MQ。先清空进度 Stream 保证回放内容只属于本次尝试，再发布命令。
     */
    private void enqueueExecution(UUID tenantId, UUID runId, UUID nodeRunId, String nodeType, UUID operatorUserId) {
        Instant now = clock.instant();
        int attempt = jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(nodeRunId)
            .map(previous -> previous.getAttempt() + 1)
            .orElse(1);
        WorkflowRunExecutionJobEntity job = WorkflowRunExecutionJobEntity.queued(
            tenantId,
            runId,
            nodeRunId,
            attempt,
            operatorUserId,
            RequestIds.current(),
            now.plusSeconds(runtimeProperties.getExecution().getNodeTimeoutSeconds()),
            now
        );
        jobRepository.save(job);
        streamWriter.reset(runId);
        commandPublisher.publish(NodeExecuteCommand.of(
            job.getId(),
            tenantId,
            runId,
            nodeRunId,
            nodeType,
            operatorUserId,
            RequestIds.current(),
            attempt,
            now
        ));
    }

    /** 同一任务同一时刻只允许一个在途执行作业，防止重复推进导致子智能体双开。 */
    private void assertNoExecutionInFlight(UUID runId) {
        boolean inFlight = !jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        ).isEmpty();
        if (inFlight) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "WORKBENCH_ADVANCE_ALREADY_IN_FLIGHT",
                "当前任务已有步骤正在执行，请等待完成或先中断后再操作"
            );
        }
    }

    /**
     * 仅当节点仍处于活跃状态时标记失败（Worker 失败路径与回收器共用），
     * 避免与中断清理、并发终态写入互相覆盖。
     */
    @Transactional
    public boolean failNodeIfActive(UUID runId, UUID nodeRunId, String errorCode, String errorMessage) {
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findById(nodeRunId).orElse(null);
        if (node == null || (!"running".equals(node.getState()) && !"pending".equals(node.getState()))) {
            return false;
        }
        WorkflowRunEntity run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            return false;
        }
        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        int completed = (int) nodes.stream().filter(n -> "completed".equals(n.getState())).count();
        failNode(run, node, completed, null, clock.instant(), errorCode, errorMessage, new ArrayList<>());
        workflowRunRepository.save(run);
        return true;
    }

    private static boolean isRestartableState(String state) {
        return "canceled".equals(state) || "failed".equals(state) || "pending".equals(state);
    }

    private static boolean isRecoverableState(String state) {
        // running 允许恢复用于僵死兜底：作业已终态但节点仍停留 running 的极端情况。
        return "failed".equals(state) || "canceled".equals(state) || "pending".equals(state) || "running".equals(state);
    }

    @Transactional
    public WorkbenchApi.RunDetail completeTodo(UUID tenantId, CurrentUserPrincipal principal, UUID todoId, WorkbenchApi.CompleteTodoRequest request) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowWaitingEventEntity todo = workflowWaitingEventRepository.findByIdAndTenantIdAndStatus(todoId, tenantId, "open")
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_TODO_NOT_FOUND", "待办不存在或已处理"));
        if (!isTenantManager(principal) && (!"user".equals(todo.getWaitingForType()) || !principal.userId().equals(todo.getWaitingForId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_TODO_HANDLE_FORBIDDEN", "当前账号不能处理该待办");
        }
        WorkflowRunEntity run = workflowRunRepository.findByIdAndTenantId(todo.getRunId(), tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId());
        WorkflowNodeRunEntity nodeRun = workflowNodeRunRepository.findByIdAndRunId(todo.getNodeRunId(), run.getId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        Instant now = clock.instant();
        Map<String, Object> output = new HashMap<>(request == null || request.payload() == null ? Map.of() : request.payload());
        if (request != null && request.comment() != null && !request.comment().isBlank()) {
            output.put("comment", request.comment().trim());
        }
        nodeRun.complete(output, now);
        todo.resolve(principal.userId(), now);
        workflowWaitingEventRepository.save(todo);
        workflowNodeRunRepository.save(nodeRun);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            tenantId,
            "todo_resolved",
            "待办已处理",
            request == null || request.comment() == null || request.comment().isBlank() ? "处理人提交后流程继续推进。" : request.comment().trim(),
            todo.getNodeKey(),
            principal.userId(),
            output,
            now
        ));
        persistVariableSnapshots(run, nodeRun, output, now);
        int nextIndex = Math.max(0, nodeRun.getSortOrder() + 1);
        if (nextIndex < nodes.size()) {
            WorkflowNodeRunEntity nextNode = nodes.get(nextIndex);
            run.pauseAt(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), nextIndex, now);
        } else {
            run.complete(nodes.size(), now);
            workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                run.getId(),
                run.getTenantId(),
                "run_completed",
                "任务已完成",
                "全部节点已完成，任务进入历史完成记录。",
                null,
                principal.userId(),
                Map.of(),
                now
            ));
        }
        workflowRunRepository.save(run);
        return getRunDetail(tenantId, principal, run.getId());
    }

    @Transactional(readOnly = true)
    public long countVisibleOpenTodos(UUID tenantId, CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        return workflowRunRepository.countVisibleActiveRuns(tenantId, principal.userId(), isTenantManager(principal));
    }

    @Transactional(readOnly = true)
    public long countVisibleRunningRuns(UUID tenantId, CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        return workflowRunRepository.countVisibleByStateIn(tenantId, principal.userId(), isTenantManager(principal), List.of("running"));
    }

    @Transactional(readOnly = true)
    public List<WorkbenchApi.PendingTodoRow> listPendingTodos(UUID tenantId, CurrentUserPrincipal principal, int limit) {
        ensureAuthenticated(principal);
        Page<WorkflowRunEntity> page = workflowRunRepository.searchVisibleActiveRuns(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            "",
            PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "updatedAt"))
        );
        return page.getContent().stream().map(this::toActiveTaskRow).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkbenchApi.RecentRunRow> listRecentRuns(UUID tenantId, CurrentUserPrincipal principal, int limit) {
        ensureAuthenticated(principal);
        Page<WorkflowRunEntity> page = workflowRunRepository.searchVisibleCompletedRuns(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            "",
            PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "updatedAt"))
        );
        Map<UUID, UserAccount> usersById = loadUsersById(page.getContent().stream().map(WorkflowRunEntity::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.getContent().stream().map(run -> toRecentRunRow(run, usersById)).toList();
    }

    private WorkflowWaitingEventEntity advanceUntilPause(
        WorkflowRunEntity run,
        List<WorkflowNodeRunEntity> nodeRuns,
        int startIndex,
        UUID operatorUserId,
        Instant now,
        List<WorkflowRunEventEntity> inMemoryEvents
    ) {
        int completed = (int) nodeRuns.stream().filter(node -> "completed".equals(node.getState())).count();
        for (int index = startIndex; index < nodeRuns.size(); index++) {
            WorkflowNodeRunEntity node = nodeRuns.get(index);
            if ("completed".equals(node.getState())) {
                continue;
            }
            if (isWaitable(node.getNodeType())) {
                node.waitForInput(now);
                workflowNodeRunRepository.save(node);
                run.pauseAt(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
                WorkflowWaitingEventEntity todo = WorkflowWaitingEventEntity.openForUser(
                    run.getId(),
                    node.getId(),
                    run.getTenantId(),
                    run.getWorkflowId(),
                    node.getNodeKey(),
                    node.getName(),
                    waitingReason(node.getNodeType()),
                    operatorUserId,
                    actionLabel(node.getNodeType()),
                    Map.of("nodeType", node.getNodeType()),
                    now
                );
                workflowWaitingEventRepository.save(todo);
                WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
                    run.getId(),
                    run.getTenantId(),
                    "node_waiting",
                    "节点等待处理",
                    node.getName() + "需要" + actionLabel(node.getNodeType()) + "后继续。",
                    node.getNodeKey(),
                    operatorUserId,
                    Map.of("nodeType", node.getNodeType()),
                    now
                );
                workflowRunEventRepository.save(event);
                inMemoryEvents.add(event);
                return todo;
            }

            // 智能体节点统一走 SSE 步进，不在自动推进链路里同步调用模型。
            if (requiresManualAdvance(node.getNodeType())) {
                run.pauseAt(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
                WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
                    run.getId(),
                    run.getTenantId(),
                    "node_waiting",
                    "节点等待推进",
                    node.getName() + "等待用户点击「执行下一步」后继续。",
                    node.getNodeKey(),
                    operatorUserId,
                    Map.of("nodeType", node.getNodeType(), "manualAdvance", true),
                    now
                );
                workflowRunEventRepository.save(event);
                inMemoryEvents.add(event);
                return null;
            }

            run.markRunning(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
            try {
                Map<String, Object> output = workflowRuntimeExecutor.execute(new WorkflowRuntimeExecutor.ExecutionRequest(
                    run,
                    node,
                    currentVariables(nodeRuns),
                    operatorUserId
                )).outputs();
                node.complete(output, now);
                completed++;
                workflowNodeRunRepository.save(node);
                persistVariableSnapshots(run, node, output, now);
                WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
                    run.getId(),
                    run.getTenantId(),
                    "node_completed",
                    "节点已完成",
                    executionEventDescription(node, output),
                    node.getNodeKey(),
                    operatorUserId,
                    Map.of("nodeType", node.getNodeType()),
                    now
                );
                workflowRunEventRepository.save(event);
                inMemoryEvents.add(event);
            } catch (ApiException exception) {
                return failNode(run, node, completed, operatorUserId, now, exception.getCode(), exception.getMessage(), inMemoryEvents);
            } catch (RuntimeException exception) {
                log.error(
                    "工作流节点执行异常 tenantId={} runId={} nodeRunId={} nodeType={} requestId={}",
                    run.getTenantId(),
                    run.getId(),
                    node.getId(),
                    node.getNodeType(),
                    RequestIds.current(),
                    exception
                );
                return failNode(run, node, completed, operatorUserId, now, "WORKBENCH_NODE_EXECUTION_FAILED", "节点执行失败，请联系管理员查看运行日志", inMemoryEvents);
            }
        }
        run.complete(completed, now);
        WorkflowRunEventEntity completedEvent = WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "run_completed",
            "任务已完成",
            "全部节点已完成，任务进入历史完成记录。",
            null,
            operatorUserId,
            Map.of(),
            now
        );
        workflowRunEventRepository.save(completedEvent);
        inMemoryEvents.add(completedEvent);
        return null;
    }

    private WorkflowWaitingEventEntity failNode(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        int completed,
        UUID operatorUserId,
        Instant now,
        String errorCode,
        String errorMessage,
        List<WorkflowRunEventEntity> inMemoryEvents
    ) {
        Map<String, Object> failureOutput = Map.of(
            "errorCode", errorCode == null ? "WORKBENCH_NODE_EXECUTION_FAILED" : errorCode,
            "errorMessage", errorMessage == null ? "节点执行失败" : errorMessage,
            "summary", errorMessage == null ? "节点执行失败，流程已停止。" : errorMessage
        );
        node.fail(failureOutput, now);
        workflowNodeRunRepository.save(node);
        run.failAt(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
        WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "node_failed",
            "节点执行失败",
            errorMessage == null ? "节点执行失败，流程已停止。" : errorMessage,
            node.getNodeKey(),
            operatorUserId,
            Map.of("nodeType", node.getNodeType(), "errorCode", failureOutput.get("errorCode")),
            now
        );
        workflowRunEventRepository.save(event);
        inMemoryEvents.add(event);
        log.warn(
            "工作流节点执行失败 tenantId={} runId={} nodeRunId={} nodeType={} errorCode={} userId={} requestId={}",
            run.getTenantId(),
            run.getId(),
            node.getId(),
            node.getNodeType(),
            failureOutput.get("errorCode"),
            operatorUserId,
            RequestIds.current()
        );
        return null;
    }

    private WorkbenchApi.AvailableWorkflowRow toAvailableWorkflow(
        WorkflowDefinitionEntity definition,
        WorkflowVersionEntity latestVersion,
        Map<UUID, UserAccount> ownersById,
        AccessLevel access,
        boolean tenantManager
    ) {
        UserAccount owner = definition.getCreatedBy() == null ? null : ownersById.get(definition.getCreatedBy());
        boolean canLaunch = tenantManager || access.canRead();
        String visibility = tenantManager ? "manager" : switch (access) {
            case OWNER -> "owner";
            case EDIT, READ -> "open";
            case NONE -> "locked";
        };
        return new WorkbenchApi.AvailableWorkflowRow(
            definition.getId(),
            definition.getName(),
            definition.getDescription() == null ? "" : definition.getDescription(),
            definition.getNodeCount(),
            latestVersion == null ? 0 : latestVersion.getVersionNumber(),
            latestVersion == null ? definition.getUpdatedAt() : latestVersion.getPublishedAt(),
            definition.getCreatedBy(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            visibility,
            canLaunch,
            canLaunch ? "" : "当前账号没有该流程的读取或发起权限"
        );
    }

    private WorkbenchApi.RunDetail toRunDetail(
        WorkflowRunEntity run,
        List<WorkflowNodeRunEntity> nodes,
        List<WorkflowRunEventEntity> events,
        WorkflowWaitingEventEntity openTodo,
        Map<UUID, UserAccount> usersById
    ) {
        UserAccount owner = run.getCreatedBy() == null ? null : usersById.get(run.getCreatedBy());
        boolean readOnly = "completed".equals(run.getState());
        return new WorkbenchApi.RunDetail(
            run.getId(),
            run.getTitle(),
            run.getRunNumber(),
            run.isSaved(),
            readOnly,
            run.getWorkflowId(),
            run.getWorkflowName(),
            run.getWorkflowVersionNumber(),
            run.getState(),
            stateLabel(run.getState()),
            run.getProgressPercent(),
            run.getCurrentNodeKey(),
            run.getCurrentNodeName(),
            run.getCurrentNodeType(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            run.getStartedAt(),
            run.getUpdatedAt(),
            nodes.stream().map(this::toNodeRunRow).toList(),
            events.stream().map(this::toRunEventRow).toList(),
            openTodo == null ? null : toOpenTodoRow(openTodo, run),
            activeJobInfo(run.getId())
        );
    }

    /**
     * 当前在途执行作业摘要：前端据此判定「执行中」并触发 SSE 回放，而不是依赖本地内存状态。
     */
    private WorkbenchApi.ActiveJobInfo activeJobInfo(UUID runId) {
        return jobRepository.findByRunIdAndStatusIn(
                runId,
                List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
            ).stream()
            .max(Comparator.comparing(WorkflowRunExecutionJobEntity::getEnqueuedAt))
            .map(job -> new WorkbenchApi.ActiveJobInfo(
                job.getId(),
                job.getStatus(),
                job.getNodeRunId(),
                job.getAttempt(),
                job.getEnqueuedAt(),
                job.getStartedAt()
            ))
            .orElse(null);
    }

    private WorkbenchApi.TaskRunRow toTaskRunRow(WorkflowRunEntity run, Map<UUID, UserAccount> usersById, boolean hasOpenTodo) {
        UserAccount owner = run.getCreatedBy() == null ? null : usersById.get(run.getCreatedBy());
        return new WorkbenchApi.TaskRunRow(
            run.getId(),
            run.getTitle(),
            run.getRunNumber(),
            run.getWorkflowName(),
            run.getWorkflowVersionNumber(),
            run.getState(),
            stateLabel(run.getState()),
            run.getCurrentNodeName() == null ? "已结束" : run.getCurrentNodeName(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            run.getCompletedNodeCount(),
            run.getTotalNodeCount(),
            run.getProgressPercent(),
            hasOpenTodo,
            run.getUpdatedAt()
        );
    }

    private WorkbenchApi.PendingTodoRow toActiveTaskRow(WorkflowRunEntity run) {
        WorkflowWaitingEventEntity openTodo = workflowWaitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(run.getId(), "open")
            .stream()
            .findFirst()
            .orElse(null);
        return new WorkbenchApi.PendingTodoRow(
            run.getId(),
            run.getId(),
            openTodo == null ? null : openTodo.getId(),
            run.getTitle(),
            run.getRunNumber(),
            run.getWorkflowName(),
            run.getCurrentNodeName() == null ? "处理中" : run.getCurrentNodeName(),
            run.getState(),
            stateLabel(run.getState()),
            openTodo == null ? stateLabel(run.getState()) : openTodo.getWaitingReason(),
            openTodo == null ? "继续处理" : actionLabelFromType(openTodo.getActionType()),
            openTodo != null,
            run.getProgressPercent(),
            run.getCompletedNodeCount(),
            run.getTotalNodeCount(),
            run.getUpdatedAt()
        );
    }

    private WorkbenchApi.PendingTodoRow toOpenTodoRow(WorkflowWaitingEventEntity todo, WorkflowRunEntity run) {
        return new WorkbenchApi.PendingTodoRow(
            run.getId(),
            run.getId(),
            todo.getId(),
            run.getTitle(),
            run.getRunNumber(),
            run.getWorkflowName(),
            todo.getTitle(),
            run.getState(),
            stateLabel(run.getState()),
            todo.getWaitingReason(),
            actionLabelFromType(todo.getActionType()),
            true,
            run.getProgressPercent(),
            run.getCompletedNodeCount(),
            run.getTotalNodeCount(),
            todo.getCreatedAt()
        );
    }

    private WorkbenchApi.RecentRunRow toRecentRunRow(WorkflowRunEntity run, Map<UUID, UserAccount> usersById) {
        UserAccount owner = run.getCreatedBy() == null ? null : usersById.get(run.getCreatedBy());
        return new WorkbenchApi.RecentRunRow(
            run.getId(),
            run.getTitle(),
            run.getRunNumber(),
            run.getWorkflowName(),
            run.getState(),
            stateLabel(run.getState()),
            run.getCurrentNodeName() == null ? "已结束" : run.getCurrentNodeName(),
            owner == null ? "未知用户" : owner.getDisplayName(),
            run.getCompletedNodeCount(),
            run.getTotalNodeCount(),
            run.getUpdatedAt()
        );
    }

    private WorkbenchApi.NodeRunRow toNodeRunRow(WorkflowNodeRunEntity node) {
        return new WorkbenchApi.NodeRunRow(
            node.getId(),
            node.getNodeKey(),
            node.getNodeType(),
            node.getName(),
            node.getState(),
            node.getStateLabel(),
            node.getInputSnapshot(),
            enrichOutputsWithClusterRuns(node),
            node.getConfigSnapshot(),
            node.getSortOrder()
        );
    }

    /**
     * 集群节点输出补全：节点未成功（运行中/失败/中断恢复前）时输出快照里没有 clusterAgents，
     * 从子智能体落库结果合成，保证刷新后前端仍能展示每个子智能体的真实状态与已完成内容。
     */
    private Map<String, Object> enrichOutputsWithClusterRuns(WorkflowNodeRunEntity node) {
        Map<String, Object> outputs = node.getOutputSnapshot() == null ? Map.of() : node.getOutputSnapshot();
        if (!"parallel_group".equals(node.getNodeType()) || outputs.containsKey("clusterAgents")) {
            return outputs;
        }
        List<WorkflowClusterAgentRunEntity> rows = clusterAgentRunRepository.findByNodeRunIdOrderByAgentIndexAsc(node.getId());
        if (rows.isEmpty()) {
            return outputs;
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (WorkflowClusterAgentRunEntity row : rows) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("agentIndex", row.getAgentIndex());
            summary.put("name", row.getName());
            summary.put("status", row.isSucceeded() ? "completed" : row.getStatus());
            if (row.isSucceeded()) {
                Object finalAnswer = row.getOutput() == null ? null : row.getOutput().get("final_answer");
                summary.put("final_answer", finalAnswer == null ? "" : finalAnswer.toString());
                Object rowSummary = row.getOutput() == null ? null : row.getOutput().get("summary");
                summary.put("summary", rowSummary == null ? "已完成" : rowSummary.toString());
            } else {
                summary.put("errorCode", row.getErrorCode() == null ? "" : row.getErrorCode());
                summary.put("errorMessage", row.getErrorMessage() == null ? "" : row.getErrorMessage());
                summary.put("summary", row.getErrorMessage() == null ? "" : row.getErrorMessage());
            }
            summaries.add(summary);
        }
        Map<String, Object> enriched = new LinkedHashMap<>(outputs);
        enriched.put("clusterAgents", summaries);
        return enriched;
    }

    private WorkbenchApi.RunEventRow toRunEventRow(WorkflowRunEventEntity event) {
        return new WorkbenchApi.RunEventRow(
            event.getId(),
            event.getEventType(),
            event.getTitle(),
            event.getDescription(),
            event.getNodeKey(),
            event.getEventTime()
        );
    }

    private void assertCanReadRun(CurrentUserPrincipal principal, WorkflowRunEntity run) {
        if (isTenantManager(principal)) {
            return;
        }
        if (run.getCreatedBy() != null && run.getCreatedBy().equals(principal.userId())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_RUN_READ_FORBIDDEN", "当前账号没有查看该任务的权限");
    }

    private AccessLevel resolveAccess(WorkflowDefinitionEntity definition, UUID operatorUserId, List<WorkflowAccessGrantEntity> grants) {
        Set<UUID> readUserIds = grants.stream()
            .filter(grant -> "read".equals(grant.getAccessLevel()))
            .map(WorkflowAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        Set<UUID> editUserIds = grants.stream()
            .filter(grant -> "edit".equals(grant.getAccessLevel()))
            .map(WorkflowAccessGrantEntity::getGranteeUserId)
            .collect(Collectors.toSet());
        return collaborationAccessPolicy.resolve(
            definition.getCreatedBy(),
            operatorUserId,
            definition.getReadScope(),
            readUserIds,
            definition.getEditScope(),
            editUserIds
        );
    }

    private VersionSnapshot readSnapshot(WorkflowVersionEntity version) {
        try {
            return objectMapper.readValue(version.getDefinitionSnapshot(), VersionSnapshot.class);
        } catch (JsonProcessingException exception) {
            log.error("工作流发布快照解析失败 workflowId={} version={} requestId={}", version.getWorkflowId(), version.getVersionNumber(), RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKFLOW_VERSION_SNAPSHOT_INVALID", "流程发布版本快照无法解析");
        }
    }

    private Map<String, Object> snapshotVariables(List<String> variables, String value) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        variables.forEach(variable -> result.put(variable, value));
        return result;
    }

    private String nodeSummary(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "";
        }
        Object summary = config.get("summary");
        if (summary instanceof String summaryText && !summaryText.isBlank()) {
            return summaryText.trim();
        }
        Object placeholder = config.get("placeholder");
        if (placeholder instanceof String placeholderText && !placeholderText.isBlank()) {
            return placeholderText.trim();
        }
        return "";
    }

    private boolean isWaitable(String nodeType) {
        return "user_input".equals(nodeType) || "human_review".equals(nodeType);
    }

    private boolean requiresManualAdvance(String nodeType) {
        return "agent".equals(nodeType) || "parallel_group".equals(nodeType) || "delivery".equals(nodeType);
    }

    private String waitingReason(String nodeType) {
        return switch (nodeType) {
            case "user_input" -> "等待业务人员补充输入资料";
            case "human_review" -> "等待人工审核后继续";
            case "delivery" -> "等待交付确认";
            default -> "等待处理";
        };
    }

    private String actionLabel(String nodeType) {
        return switch (nodeType) {
            case "user_input" -> "提交输入";
            case "human_review" -> "提交审核";
            case "delivery" -> "确认交付";
            default -> "继续处理";
        };
    }

    private String actionLabelFromType(String actionType) {
        return switch (actionType) {
            case "提交输入", "提交审核", "确认交付" -> actionType;
            default -> "继续处理";
        };
    }

    private String executionEventDescription(WorkflowNodeRunEntity node, Map<String, Object> output) {
        if ("trigger".equals(node.getNodeType())) {
            return "手动触发节点已完成，流程进入业务节点。";
        }
        Object summary = output == null ? null : output.get("summary");
        if (summary != null && !summary.toString().isBlank()) {
            return summary.toString();
        }
        return node.getName() + "已由运行执行器完成。";
    }

    private Map<String, Object> currentVariables(List<WorkflowNodeRunEntity> nodeRuns) {
        Map<String, Object> variables = new HashMap<>();
        for (WorkflowNodeRunEntity nodeRun : nodeRuns) {
            if ("completed".equals(nodeRun.getState())) {
                variables.putAll(nodeRun.getOutputSnapshot());
            }
        }
        return variables;
    }

    private void persistVariableSnapshots(WorkflowRunEntity run, WorkflowNodeRunEntity node, Map<String, Object> output, Instant now) {
        if (output == null || output.isEmpty()) {
            return;
        }
        List<WorkflowVariableSnapshotEntity> snapshots = output.entrySet().stream()
            .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
            .filter(entry -> !"errorCode".equals(entry.getKey()) && !"errorMessage".equals(entry.getKey()))
            .map(entry -> {
                boolean sensitive = isSensitiveVariable(entry.getKey());
                return WorkflowVariableSnapshotEntity.create(
                    run,
                    node,
                    entry.getKey(),
                    sensitive ? "***" : entry.getValue(),
                    sensitive,
                    !sensitive && "delivery".equals(node.getNodeType()),
                    now
                );
            })
            .toList();
        if (!snapshots.isEmpty()) {
            workflowVariableSnapshotRepository.saveAll(snapshots);
        }
    }

    private boolean isSensitiveVariable(String variableName) {
        String normalized = variableName == null ? "" : variableName.toLowerCase();
        return normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("credential")
            || normalized.contains("凭证")
            || normalized.contains("密钥");
    }

    private String normalizeTitle(String title, String workflowName) {
        String normalized = title == null ? "" : title.trim();
        if (!normalized.isBlank()) {
            return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
        }
        return workflowName;
    }

    private String generateRunNumber(Instant now) {
        String datePrefix = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai")).format(now);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return datePrefix + "-" + suffix;
    }

    private WorkflowRunEntity requireOwnedRun(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        if (!isTenantManager(principal) && (run.getCreatedBy() == null || !run.getCreatedBy().equals(principal.userId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_RUN_WRITE_FORBIDDEN", "当前账号不能操作该任务");
        }
        return run;
    }

    private boolean hasOpenTodo(UUID runId) {
        return !workflowWaitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(runId, "open").isEmpty();
    }

    private void resolveOpenTodos(UUID runId, UUID operatorUserId, Instant now) {
        List<WorkflowWaitingEventEntity> openTodos = workflowWaitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(runId, "open");
        for (WorkflowWaitingEventEntity todo : openTodos) {
            todo.resolve(operatorUserId, now);
            workflowWaitingEventRepository.save(todo);
        }
    }

    private String stateLabel(String state) {
        return switch (state) {
            case "running" -> "运行中";
            case "paused" -> "已暂停";
            case "completed" -> "已完成";
            case "failed" -> "已失败";
            case "canceled" -> "已取消";
            default -> state;
        };
    }

    private Map<UUID, UserAccount> loadUsersById(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private void ensureActiveTenant(UUID tenantId) {
        tenantRepository.findByIdAndStatus(tenantId, ACTIVE_STATUS)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "租户不存在或已停用"));
    }

    private void ensureAuthenticated(CurrentUserPrincipal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }
    }

    private boolean isTenantManager(CurrentUserPrincipal principal) {
        return principal != null && ("tenant_admin".equals(principal.role()) || "system_admin".equals(principal.role()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VersionSnapshot(
        String name,
        String description,
        List<SnapshotNode> nodes,
        List<Map<String, Object>> edges,
        List<Map<String, Object>> variables
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SnapshotNode(
        String nodeId,
        String nodeType,
        String name,
        List<String> inputVariables,
        List<String> outputVariables,
        Map<String, Object> config
    ) {

        SnapshotNode {
            inputVariables = inputVariables == null ? List.of() : List.copyOf(inputVariables);
            outputVariables = outputVariables == null ? List.of() : List.copyOf(outputVariables);
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    public record NextNodeResult(boolean hasNext, UUID nodeRunId, String nodeType, String nodeName, boolean paused) {}

    @Transactional
    public NextNodeResult prepareNextNode(UUID tenantId, UUID runId, UUID operatorUserId) {
        Instant now = clock.instant();
        WorkflowRunEntity run = workflowRunRepository.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        
        if ("completed".equals(run.getState()) || "failed".equals(run.getState())) {
            return new NextNodeResult(false, null, null, null, false);
        }

        List<WorkflowNodeRunEntity> nodeRuns = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        WorkflowNodeRunEntity nextNode = null;
        int completed = 0;
        for (WorkflowNodeRunEntity node : nodeRuns) {
            if ("completed".equals(node.getState())) {
                completed++;
            } else if (nextNode == null) {
                nextNode = node;
            }
        }

        if (nextNode == null) {
            // 用户确认最后一步后：标记 completed，未保存过的任务自动保存到任务记录。
            finalizeRunCompletion(run, completed, operatorUserId, now);
            return new NextNodeResult(false, null, null, null, false);
        }

        if (isWaitable(nextNode.getNodeType())) {
            if (!"waiting".equals(nextNode.getState())) {
                nextNode.waitForInput(now);
                workflowNodeRunRepository.save(nextNode);
            }
            run.pauseAt(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
            workflowRunRepository.save(run);

            resolveOpenTodos(run.getId(), operatorUserId, now);

            WorkflowWaitingEventEntity todo = WorkflowWaitingEventEntity.openForUser(
                run.getId(),
                nextNode.getId(),
                run.getTenantId(),
                run.getWorkflowId(),
                nextNode.getNodeKey(),
                nextNode.getName(),
                waitingReason(nextNode.getNodeType()),
                operatorUserId,
                actionLabel(nextNode.getNodeType()),
                Map.of("nodeType", nextNode.getNodeType()),
                now
            );
            workflowWaitingEventRepository.save(todo);

            WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
                run.getId(),
                run.getTenantId(),
                "node_waiting",
                "节点等待处理",
                nextNode.getName() + "需要" + actionLabel(nextNode.getNodeType()) + "后继续。",
                nextNode.getNodeKey(),
                operatorUserId,
                Map.of("nodeType", nextNode.getNodeType()),
                now
            );
            workflowRunEventRepository.save(event);
            return new NextNodeResult(true, nextNode.getId(), nextNode.getNodeType(), nextNode.getName(), true);
        }

        if ("trigger".equals(nextNode.getNodeType()) || "condition".equals(nextNode.getNodeType()) || "merge".equals(nextNode.getNodeType())) {
            run.markRunning(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
            workflowRunRepository.save(run);
            try {
                Map<String, Object> output = workflowRuntimeExecutor.execute(new WorkflowRuntimeExecutor.ExecutionRequest(
                    run,
                    nextNode,
                    currentVariables(nodeRuns),
                    operatorUserId
                )).outputs();
                nextNode.complete(output, now);
                workflowNodeRunRepository.save(nextNode);
                persistVariableSnapshots(run, nextNode, output, now);

                WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
                    run.getId(),
                    run.getTenantId(),
                    "node_completed",
                    "节点已完成",
                    executionEventDescription(nextNode, output),
                    nextNode.getNodeKey(),
                    operatorUserId,
                    Map.of("nodeType", nextNode.getNodeType()),
                    now
                );
                workflowRunEventRepository.save(event);
                return prepareNextNode(tenantId, runId, operatorUserId);
            } catch (ApiException exception) {
                failNode(run, nextNode, completed, operatorUserId, now, exception.getCode(), exception.getMessage(), new ArrayList<>());
                return new NextNodeResult(true, nextNode.getId(), nextNode.getNodeType(), nextNode.getName(), false);
            } catch (Exception exception) {
                failNode(run, nextNode, completed, operatorUserId, now, "WORKBENCH_NODE_EXECUTION_FAILED", "节点执行失败", new ArrayList<>());
                return new NextNodeResult(true, nextNode.getId(), nextNode.getNodeType(), nextNode.getName(), false);
            }
        }

        // 中断（canceled）/失败（failed）节点重新推进，或待执行节点残留输出快照时，整步重做前必须复位：
        // 清空输出与变量快照，并清理非成功的子智能体行（已成功结果保留供恢复进度复用）。
        boolean needsReset = "canceled".equals(nextNode.getState())
            || "failed".equals(nextNode.getState())
            || ("pending".equals(nextNode.getState())
                && nextNode.getOutputSnapshot() != null
                && !nextNode.getOutputSnapshot().isEmpty());
        if (needsReset) {
            nextNode.resetToPending(now);
            clusterAgentRunRepository.deleteByNodeRunIdAndStatusNot(nextNode.getId(), WorkflowClusterAgentRunEntity.STATUS_SUCCEEDED);
            workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(nextNode.getId()));
        }
        nextNode.start(now);
        workflowNodeRunRepository.save(nextNode);
        run.markRunning(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
        workflowRunRepository.save(run);
        return new NextNodeResult(true, nextNode.getId(), nextNode.getNodeType(), nextNode.getName(), false);
    }

    /**
     * Worker 节点执行成功后的状态落库。
     *
     * @return 若全部节点已完成并进入任务终态，返回 true（调用方应发 run_completed 而非 run_paused）
     */
    @Transactional
    public boolean saveNodeSuccess(UUID runId, UUID nodeRunId, Map<String, Object> outputs, UUID operatorUserId) {
        Instant now = clock.instant();
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findById(nodeRunId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));

        node.complete(outputs, now);
        workflowNodeRunRepository.save(node);
        persistVariableSnapshots(run, node, outputs, now);

        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        int completed = (int) nodes.stream().filter(n -> "completed".equals(n.getState())).count();

        // 智能体/多智能体/交付（含最后一步）执行完成后均停在当前节点，等待用户点击确认后再推进或完结。
        if (requiresManualAdvance(node.getNodeType())) {
            run.pauseAt(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
            workflowRunRepository.save(run);
        } else {
            int nextIndex = node.getSortOrder() + 1;
            if (nextIndex < nodes.size()) {
                WorkflowNodeRunEntity nextNode = nodes.get(nextIndex);
                run.pauseAt(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
            } else {
                finalizeRunCompletion(run, completed, operatorUserId, now);
            }
            workflowRunRepository.save(run);
        }

        WorkflowRunEventEntity event = WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "node_completed",
            "节点已完成",
            executionEventDescription(node, outputs),
            node.getNodeKey(),
            operatorUserId,
            Map.of("nodeType", node.getNodeType()),
            now
        );
        workflowRunEventRepository.save(event);
        return false;
    }

    /**
     * 全部节点执行完成后的统一收尾：标记 completed，未保存过的任务自动保存到任务记录。
     */
    private void finalizeRunCompletion(WorkflowRunEntity run, int completed, UUID operatorUserId, Instant now) {
        run.complete(completed, now);
        workflowRunRepository.save(run);
        if (!run.isSaved()) {
            run.markSaved(now);
            workflowRunRepository.save(run);
            workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                run.getId(),
                run.getTenantId(),
                "run_saved",
                "任务已保存",
                "任务已全部完成，已自动保存到任务记录。",
                null,
                operatorUserId,
                Map.of("runNumber", run.getRunNumber(), "state", run.getState(), "autoSaved", true),
                now
            ));
        }
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "run_completed",
            "任务已完成",
            "全部节点已完成，任务进入历史完成记录。",
            null,
            operatorUserId,
            Map.of(),
            now
        ));
    }

}
