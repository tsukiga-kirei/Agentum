package com.agentum.workbench.application;

import com.agentum.agent.application.PromptContentResolver;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.CollaborationAccessPolicy.AccessLevel;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
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
import com.agentum.workflow.application.WorkflowRuntimeSystemVariables;
import java.util.HashSet;
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
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    /** 与前端看门狗（60s 无心跳）对齐：超过该时长仍未启动的 queued 作业视为僵死。 */
    private static final long RECOVER_STALE_JOB_SECONDS = 60;
    /** queued 且从未 markRunning，超过该窗口仍占租约/无消费，判定为僵尸作业。 */
    private static final long QUEUED_ZOMBIE_JOB_SECONDS = 30;

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
    private final PromptContentResolver promptContentResolver;
    private final RunExecutionLeaseService leaseService;
    private final TransactionTemplate transactionTemplate;

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
        RuntimeExecutionProperties runtimeProperties,
        PromptContentResolver promptContentResolver,
        RunExecutionLeaseService leaseService,
        PlatformTransactionManager transactionManager
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
        this.promptContentResolver = promptContentResolver;
        this.leaseService = leaseService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
            Map<String, Object> configSnapshot = promptContentResolver.enrichConfigSnapshot(
                tenantId,
                node.nodeType(),
                enrichRuntimeNodeConfig(node)
            );
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
                configSnapshot,
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

    /**
     * 定时任务触发运行实例。
     *
     * <p>与手工发起不同，定时任务创建后立即保存，并通过 triggerPayload 固化个人配置快照。
     * 后续推进由后端自动完成：输入节点使用预置 payload，智能体/交付节点直接入队，不再依赖前端点击。</p>
     */
    @Transactional
    public WorkbenchApi.RunDetail createScheduledRun(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID workflowId,
        UUID scheduleId,
        String scheduleName,
        Map<String, Object> inputPayload,
        Map<String, Object> scheduleSnapshot
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKBENCH_WORKFLOW_LAUNCH_FORBIDDEN", "当前账号没有该流程的读取或发起权限");
        }

        VersionSnapshot snapshot = readSnapshot(version);
        List<SnapshotNode> snapshotNodes = snapshot.nodes() == null ? List.of() : snapshot.nodes();
        Instant now = clock.instant();
        String title = normalizeTitle((scheduleName == null || scheduleName.isBlank() ? definition.getName() : scheduleName) + "（定时执行）", definition.getName());
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
        Map<String, Object> triggerPayload = new LinkedHashMap<>(scheduleSnapshot == null ? Map.of() : scheduleSnapshot);
        triggerPayload.put("inputPayload", inputPayload == null ? Map.of() : new LinkedHashMap<>(inputPayload));
        run.markScheduledTrigger(scheduleId, triggerPayload, now);
        workflowRunRepository.save(run);

        List<WorkflowNodeRunEntity> nodeRuns = new ArrayList<>();
        for (int index = 0; index < snapshotNodes.size(); index++) {
            SnapshotNode node = snapshotNodes.get(index);
            Map<String, Object> configSnapshot = promptContentResolver.enrichConfigSnapshot(
                tenantId,
                node.nodeType(),
                enrichRuntimeNodeConfig(node)
            );
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
                configSnapshot,
                index,
                now
            ));
        }
        workflowNodeRunRepository.saveAll(nodeRuns);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            tenantId,
            "schedule_run_created",
            "定时任务已触发",
            "系统根据定时任务「" + (scheduleName == null ? "未命名定时任务" : scheduleName) + "」创建运行实例。",
            null,
            principal.userId(),
            Map.of("workflowId", definition.getId().toString(), "version", version.getVersionNumber(), "scheduleId", scheduleId.toString()),
            now
        ));

        NextNodeResult next = prepareNextNode(tenantId, run.getId(), principal.userId());
        if (next.hasNext() && !next.paused() && requiresManualAdvance(next.nodeType())) {
            enqueueExecution(tenantId, run.getId(), next.nodeRunId(), next.nodeType(), principal.userId());
        }
        return getRunDetail(tenantId, principal, run.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.TaskRunRow> listActiveRuns(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        String state,
        String triggerSource,
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
            normalizeActiveRunStateFilter(state),
            normalizeTriggerSourceFilter(triggerSource),
            pageable
        );
        return PageResponse.from(resultPage.map(run -> toTaskRunRow(run, Map.of(), hasOpenTodo(run.getId()))));
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkbenchApi.TaskRunRow> listRuns(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        String triggerSource,
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
            normalizeTriggerSourceFilter(triggerSource),
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
                clearNodeConversationHistory(node, now);
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

        // 终态化在途作业：Worker 退出时据此识别「已被中断」，不再覆盖节点状态。
        abortLingeringExecution(runId);
        Instant now = clock.instant();

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
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!isCompletedAiNodeRegenerable(node) && !isRestartableState(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_NODE_RESTART_INVALID", "当前步骤状态不支持重新执行");
        }
        transactionTemplate.executeWithoutResult(status -> {
            terminateRecoverableStaleJobs(runId);
            abortLingeringExecution(runId);
            assertNoExecutionInFlight(runId);
            prepareNodeReExecution(run, node, true, principal.userId(), "run_node_restarted", "步骤重新执行",
                "已清空「" + node.getName() + "」全部执行数据，开始从头重新执行。");
            enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        });
        log.info(
            "用户重新执行节点 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 被动「恢复进度」：保留已成功子智能体的落库结果，仅重跑失败/未完成部分，损失最小。
     * 用户主动中断后的 canceled 节点已清空运行数据，必须走「重新执行」而不是恢复进度。
     */
    /**
     * 单智能体追问：节点已完成且开启「允许追问」时，追加用户消息并基于对话历史续跑。
     */
    public WorkbenchApi.RunDetail followUpNode(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID runId,
        UUID nodeRunId,
        String message
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        String followUpMessage = message == null ? "" : message.trim();
        if (followUpMessage.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FOLLOW_UP_EMPTY", "追问内容不能为空");
        }
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能追问");
        }
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"agent".equals(node.getNodeType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FOLLOW_UP_UNSUPPORTED", "当前节点类型不支持追问");
        }
        if (!"completed".equals(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FOLLOW_UP_INVALID", "仅已完成的智能体步骤可追问");
        }
        if (!isFollowUpAllowed(node.getConfigSnapshot())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FOLLOW_UP_FORBIDDEN", "流程未开启「允许追问」，无法继续对话");
        }

        transactionTemplate.executeWithoutResult(status -> {
            assertNoExecutionInFlight(runId);
            Instant now = clock.instant();
            cancellationGuard.clearCancel(run.getId());
            Map<String, Object> nextConfig = new LinkedHashMap<>(node.getConfigSnapshot());
            List<Map<String, Object>> conversationHistory = readChatMessagesForFollowUp(node.getOutputSnapshot(), nextConfig);
            conversationHistory.add(Map.of("role", "user", "content", followUpMessage));
            nextConfig.put("conversationHistory", conversationHistory);
            node.prepareForFollowUp(nextConfig, now);
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
                "run_node_follow_up",
                "智能体追问",
                "已向「" + node.getName() + "」追加追问并继续对话。",
                node.getNodeKey(),
                principal.userId(),
                Map.of("nodeRunId", node.getId().toString()),
                now
            ));
            enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        });
        log.info(
            "用户追问智能体 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    public WorkbenchApi.RunDetail followUpClusterAgent(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID runId,
        UUID nodeRunId,
        int agentIndex,
        String message
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        String followUpMessage = message == null ? "" : message.trim();
        if (followUpMessage.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_FOLLOW_UP_EMPTY", "追问内容不能为空");
        }
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能追问");
        }
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"parallel_group".equals(node.getNodeType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_FOLLOW_UP_UNSUPPORTED", "当前节点不是智能体集群节点");
        }
        if (!"completed".equals(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_FOLLOW_UP_INVALID", "仅已完成的子智能体结果可追问");
        }
        if (!isClusterAgentFollowUpAllowed(node.getConfigSnapshot(), agentIndex)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_FOLLOW_UP_FORBIDDEN", "该子智能体未开启「允许追问」");
        }

        transactionTemplate.executeWithoutResult(status -> {
            assertNoExecutionInFlight(runId);
            Instant now = clock.instant();
            cancellationGuard.clearCancel(run.getId());
            WorkflowClusterAgentRunEntity clusterAgent = clusterAgentRunRepository.findByNodeRunIdAndAgentIndex(node.getId(), agentIndex)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_CLUSTER_AGENT_RUN_NOT_FOUND", "子智能体运行记录不存在"));
            if (!clusterAgent.isSucceeded()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_FOLLOW_UP_INVALID", "仅已完成的子智能体结果可追问");
            }

            Map<String, Object> nextConfig = new LinkedHashMap<>(node.getConfigSnapshot());
            Map<String, Object> nextAgentConfig = appendClusterAgentFollowUp(nextConfig, agentIndex, clusterAgent, followUpMessage);
            nextConfig.put("clusterAgents", replaceClusterAgentConfig(nextConfig.get("clusterAgents"), agentIndex, nextAgentConfig));

            String executionMode = stringValue(nextConfig.get("executionMode"));
            for (WorkflowClusterAgentRunEntity row : clusterAgentRunRepository.findByNodeRunIdOrderByAgentIndexAsc(node.getId())) {
                boolean shouldDelete = "relay".equals(executionMode) ? row.getAgentIndex() >= agentIndex : row.getAgentIndex() == agentIndex;
                if (shouldDelete) {
                    clusterAgentRunRepository.delete(row);
                }
            }

            node.prepareForFollowUp(nextConfig, now);
            workflowNodeRunRepository.save(node);

            List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId());
            int completedBefore = (int) nodes.stream()
                .filter(other -> other.getSortOrder() < node.getSortOrder() && "completed".equals(other.getState()))
                .count();
            run.markRunning(node.getNodeKey(), node.getName(), node.getNodeType(), completedBefore, now);
            workflowRunRepository.save(run);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nodeRunId", node.getId().toString());
            metadata.put("agentIndex", agentIndex);
            workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                run.getId(),
                run.getTenantId(),
                "run_cluster_agent_follow_up",
                "子智能体追问",
                "已向「" + clusterAgent.getName() + "」追加追问并继续对话。",
                node.getNodeKey(),
                principal.userId(),
                metadata,
                now
            ));
            enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        });
        log.info(
            "用户追问子智能体 tenantId={} userId={} runId={} nodeRunId={} agentIndex={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, agentIndex, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    /**
     * 用户手动修改最终答案：仅更新输出快照与变量，不触发模型重新生成。
     */
    public WorkbenchApi.RunDetail updateFinalAnswer(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID runId,
        UUID nodeRunId,
        String content
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        String answer = content == null ? "" : content.trim();
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FINAL_ANSWER_EMPTY", "最终答案不能为空");
        }
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能修改答案");
        }
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"agent".equals(node.getNodeType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FINAL_ANSWER_UNSUPPORTED", "当前节点类型不支持修改最终答案");
        }
        if (!"completed".equals(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FINAL_ANSWER_INVALID", "仅已完成的智能体步骤可修改最终答案");
        }
        if (!isUserEditAllowed(node.getConfigSnapshot())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_FINAL_ANSWER_FORBIDDEN", "流程未开启「允许修改」，无法保存最终答案");
        }

        transactionTemplate.executeWithoutResult(status -> applyFinalAnswerUpdate(run, node, answer, principal.userId()));
        log.info(
            "用户修改最终答案 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    public WorkbenchApi.RunDetail updateClusterAgentFinalAnswer(
        UUID tenantId,
        CurrentUserPrincipal principal,
        UUID runId,
        UUID nodeRunId,
        int agentIndex,
        String content
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        String answer = content == null ? "" : content.trim();
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_ANSWER_EMPTY", "子智能体最终答案不能为空");
        }
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能修改答案");
        }
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"parallel_group".equals(node.getNodeType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_ANSWER_UNSUPPORTED", "当前节点不是智能体集群节点");
        }
        if (!"completed".equals(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_ANSWER_INVALID", "仅已完成的子智能体结果可修改");
        }
        if (!isClusterAgentEditAllowed(node.getConfigSnapshot(), agentIndex)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_ANSWER_FORBIDDEN", "该子智能体未开启「允许修改」");
        }

        transactionTemplate.executeWithoutResult(status -> applyClusterAgentAnswerUpdate(run, node, agentIndex, answer, principal.userId()));
        log.info(
            "用户修改子智能体最终答案 tenantId={} userId={} runId={} nodeRunId={} agentIndex={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, agentIndex, RequestIds.current()
        );
        return getRunDetail(tenantId, principal, runId);
    }

    private void applyFinalAnswerUpdate(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        String answer,
        UUID operatorUserId
    ) {
        Instant now = clock.instant();
        Map<String, Object> config = node.getConfigSnapshot() == null ? Map.of() : node.getConfigSnapshot();
        Map<String, Object> outputs = new LinkedHashMap<>(node.getOutputSnapshot() == null ? Map.of() : node.getOutputSnapshot());
        String outputName = firstNonBlank(
            stringValue(config.get("output")),
            stringValue(config.get("outputVariable")),
            "agent_response"
        );
        outputs.put(outputName, answer);
        outputs.put("final_answer", answer);
        outputs.put("summary", summarizeAnswer(answer));
        outputs.put("chatMessages", updateChatMessagesWithAnswer(outputs.get("chatMessages"), answer));
        node.patchOutputSnapshot(outputs, now);
        workflowNodeRunRepository.save(node);
        workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(node.getId()));
        persistVariableSnapshots(run, node, outputs, now);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "run_node_answer_updated",
            "修改最终答案",
            "已保存「" + node.getName() + "」的最终答案。",
            node.getNodeKey(),
            operatorUserId,
            Map.of("nodeRunId", node.getId().toString()),
            now
        ));
    }

    private void applyClusterAgentAnswerUpdate(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        int agentIndex,
        String answer,
        UUID operatorUserId
    ) {
        Instant now = clock.instant();
        WorkflowClusterAgentRunEntity clusterAgent = clusterAgentRunRepository.findByNodeRunIdAndAgentIndex(node.getId(), agentIndex)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_CLUSTER_AGENT_RUN_NOT_FOUND", "子智能体运行记录不存在"));
        if (!clusterAgent.isSucceeded()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_ANSWER_INVALID", "仅已完成的子智能体结果可修改");
        }

        Map<String, Object> agentOutput = new LinkedHashMap<>(clusterAgent.getOutput() == null ? Map.of() : clusterAgent.getOutput());
        agentOutput.put("final_answer", answer);
        agentOutput.put("agent_response", answer);
        agentOutput.put("summary", summarizeAnswer(answer));
        clusterAgent.patchOutput(agentOutput, now);
        clusterAgentRunRepository.save(clusterAgent);

        List<WorkflowClusterAgentRunEntity> rows = clusterAgentRunRepository.findByNodeRunIdOrderByAgentIndexAsc(node.getId());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (WorkflowClusterAgentRunEntity row : rows) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("agentIndex", row.getAgentIndex());
            summary.put("name", row.getName());
            summary.put("outputVariable", clusterAgentOutputVariable(node.getConfigSnapshot(), row.getAgentIndex()));
            summary.put("status", row.isSucceeded() ? "completed" : row.getStatus());
            if (row.isSucceeded()) {
                String body = row.getOutput() == null ? "" : stringValue(row.getOutput().get("final_answer"));
                summary.put("final_answer", body);
                summary.put("summary", summarizeAnswer(body));
                summary.put("tokenUsage", row.getOutput() == null ? Map.of() : row.getOutput().getOrDefault("tokenUsage", Map.of()));
                summary.put("chatMessages", row.getOutput() == null ? List.of() : row.getOutput().getOrDefault("chatMessages", List.of()));
            } else {
                summary.put("errorCode", row.getErrorCode() == null ? "" : row.getErrorCode());
                summary.put("errorMessage", row.getErrorMessage() == null ? "" : row.getErrorMessage());
                summary.put("summary", row.getErrorMessage() == null ? "" : row.getErrorMessage());
            }
            summaries.add(summary);
        }

        Map<String, Object> outputs = new LinkedHashMap<>(node.getOutputSnapshot() == null ? Map.of() : node.getOutputSnapshot());
        String finalAnswer = ClusterOutputSupport.finalAnswer(node.getConfigSnapshot(), outputs, summaries);
        outputs.put("clusterAgents", summaries);
        outputs.put("final_answer", finalAnswer);
        outputs.put("agent_response", finalAnswer);
        outputs.put(ClusterOutputSupport.outputVariable(node.getConfigSnapshot()), finalAnswer);
        outputs.put(ClusterIntentRoutingSupport.DEFAULT_INTENT_OUTPUT_VARIABLE, finalAnswer);
        outputs.put("summary", "智能体集群已完成 " + summaries.stream().filter(item -> "completed".equals(item.get("status"))).count() + " 个子智能体。");
        node.patchOutputSnapshot(outputs, now);
        workflowNodeRunRepository.save(node);
        workflowVariableSnapshotRepository.deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(node.getId()));
        persistVariableSnapshots(run, node, outputs, now);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeRunId", node.getId().toString());
        metadata.put("agentIndex", agentIndex);
        workflowRunEventRepository.save(WorkflowRunEventEntity.create(
            run.getId(),
            run.getTenantId(),
            "run_cluster_agent_answer_updated",
            "修改子智能体答案",
            "已保存「" + clusterAgent.getName() + "」的最终答案。",
            node.getNodeKey(),
            operatorUserId,
            metadata,
            now
        ));
    }

    public WorkbenchApi.RunDetail recoverNode(UUID tenantId, CurrentUserPrincipal principal, UUID runId, UUID nodeRunId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowRunEntity run = requireOwnedRun(tenantId, principal, runId);
        if ("completed".equals(run.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_RUN_READONLY", "已完成任务只能查看，不能恢复执行");
        }
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if ("canceled".equals(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_NODE_RECOVER_INTERRUPTED", "当前步骤已被主动中断，请使用重新执行从头重跑");
        }
        if (!isRecoverableState(node.getState())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_NODE_RECOVER_INVALID", "当前步骤状态不支持恢复进度");
        }
        transactionTemplate.executeWithoutResult(status -> {
            terminateRecoverableStaleJobs(runId);
            abortLingeringExecution(runId);
            assertNoExecutionInFlight(runId);
            prepareNodeReExecution(run, node, false, principal.userId(), "run_node_recovered", "步骤恢复执行",
                "已保留「" + node.getName() + "」已成功的子智能体结果，仅重跑失败或未完成部分。");
            enqueueExecution(tenantId, runId, node.getId(), node.getNodeType(), principal.userId());
        });
        log.info(
            "用户恢复节点执行 tenantId={} userId={} runId={} nodeRunId={} requestId={}",
            tenantId, principal.userId(), runId, nodeRunId, RequestIds.current()
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
            clearNodeConversationHistory(node, now);
        }
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
     *
     * <p>MQ 必须在事务提交后再发布，否则 Worker 可能先于作业落库消费，出现「命令已失效」；
     * 入队前清理孤儿租约，避免 DB 无在途作业时 Redis 仍占锁导致「租约被占用」循环。</p>
     */
    private void enqueueExecution(UUID tenantId, UUID runId, UUID nodeRunId, String nodeType, UUID operatorUserId) {
        releaseOrphanedExecutionLeaseIfIdle(runId);
        Instant now = clock.instant();
        int attempt = jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(nodeRunId)
            .map(previous -> previous.getAttempt() + 1)
            .orElse(1);
        String idempotencyKey = runId + ":" + nodeRunId + ":" + attempt;
        Optional<WorkflowRunExecutionJobEntity> existingJob = jobRepository.findByIdempotencyKey(idempotencyKey);
        if (existingJob.isPresent() && isExecutionJobInFlight(existingJob.get())) {
            log.info(
                "执行作业已存在，跳过重复入队 tenantId={} runId={} nodeRunId={} attempt={} jobId={} requestId={}",
                tenantId,
                runId,
                nodeRunId,
                attempt,
                existingJob.get().getId(),
                RequestIds.current()
            );
            return;
        }
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
        try {
            jobRepository.save(job);
        } catch (DataIntegrityViolationException exception) {
            // 并发 advance（例如回退后自动启动与手动重新生成同时触发）可能撞上同一 attempt 的幂等键。
            Optional<WorkflowRunExecutionJobEntity> racedJob = jobRepository.findByIdempotencyKey(idempotencyKey);
            if (racedJob.isPresent() && isExecutionJobInFlight(racedJob.get())) {
                log.info(
                    "并发入队冲突已幂等收敛 tenantId={} runId={} nodeRunId={} attempt={} jobId={} requestId={}",
                    tenantId,
                    runId,
                    nodeRunId,
                    attempt,
                    racedJob.get().getId(),
                    RequestIds.current()
                );
                return;
            }
            throw exception;
        }
        streamWriter.reset(runId);
        publishNodeExecuteCommandAfterCommit(NodeExecuteCommand.of(
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

    /**
     * DB 已无 queued/running 作业却仍占 Redis 租约时，说明上次 Worker 未正常 release。
     * 重新执行/恢复进度入队前必须清理，否则新命令会被 Worker 以「租约被占用」永久跳过。
     */
    private void releaseOrphanedExecutionLeaseIfIdle(UUID runId) {
        boolean inFlight = !jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        ).isEmpty();
        if (!inFlight && leaseService.hasActiveLease(runId)) {
            log.warn(
                "检测到孤儿执行租约，入队前强制释放 runId={} requestId={}",
                runId,
                RequestIds.current()
            );
            leaseService.forceRelease(runId);
        }
    }

    /** 事务提交后再投递 MQ，避免 Worker 读取尚未落库的作业。 */
    private void publishNodeExecuteCommandAfterCommit(NodeExecuteCommand command) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    commandPublisher.publish(command);
                }
            });
            return;
        }
        commandPublisher.publish(command);
    }

    private static boolean isExecutionJobInFlight(WorkflowRunExecutionJobEntity job) {
        return WorkflowRunExecutionJobEntity.STATUS_QUEUED.equals(job.getStatus())
            || WorkflowRunExecutionJobEntity.STATUS_RUNNING.equals(job.getStatus());
    }

    /**
     * 整步重做时移除追问累积的 conversationHistory。
     *
     * <p>单智能体历史在节点顶层配置；集群子智能体历史挂在 clusterAgents[] 内。
     * 回退、主动中断后的重新执行都表示用户要重新载入该节点，不能继续沿用旧追问上下文。</p>
     */
    @SuppressWarnings("unchecked")
    private static void clearNodeConversationHistory(WorkflowNodeRunEntity node, Instant now) {
        Map<String, Object> config = node.getConfigSnapshot();
        if (config == null || config.isEmpty()) {
            return;
        }
        boolean changed = false;
        Map<String, Object> nextConfig = new LinkedHashMap<>(config);
        if (nextConfig.remove("conversationHistory") != null) {
            changed = true;
        }

        Object rawAgents = nextConfig.get("clusterAgents");
        if (rawAgents instanceof List<?> agents) {
            List<Map<String, Object>> nextAgents = new ArrayList<>();
            for (Object agent : agents) {
                if (agent instanceof Map<?, ?> rawAgent) {
                    Map<String, Object> nextAgent = new LinkedHashMap<>((Map<String, Object>) rawAgent);
                    if (nextAgent.remove("conversationHistory") != null) {
                        changed = true;
                    }
                    nextAgents.add(nextAgent);
                }
            }
            if (changed) {
                nextConfig.put("clusterAgents", nextAgents);
            }
        }

        if (changed) {
            node.replaceConfigSnapshot(nextConfig, now);
        }
    }

    /**
     * 恢复/重新执行前终止僵死作业：queued 长期未启动、或 running 无租约。
     * 与前端「超过 1 分钟无心跳可恢复进度」语义对齐，避免 409 ALREADY_IN_FLIGHT。
     */
    private void terminateRecoverableStaleJobs(UUID runId) {
        Instant now = clock.instant();
        List<WorkflowRunExecutionJobEntity> inFlight = jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        );
        for (WorkflowRunExecutionJobEntity job : inFlight) {
            if (!isRecoverablyStaleJob(job, now)) {
                continue;
            }
            cancellationGuard.requestCancel(runId);
            if (leaseService.hasActiveLease(runId)) {
                leaseService.forceRelease(runId);
            }
            job.markFailed(
                "WORKBENCH_NODE_EXECUTION_STALE",
                "执行作业长时间无进展，已终止以允许恢复进度",
                now
            );
            jobRepository.save(job);
            log.warn(
                "僵死执行作业已终止 runId={} jobId={} jobStatus={} enqueuedAt={} requestId={}",
                runId,
                job.getId(),
                job.getStatus(),
                job.getEnqueuedAt(),
                RequestIds.current()
            );
        }
    }

    private boolean isRecoverablyStaleJob(WorkflowRunExecutionJobEntity job, Instant now) {
        if (WorkflowRunExecutionJobEntity.STATUS_QUEUED.equals(job.getStatus())) {
            if (job.getEnqueuedAt().isBefore(now.minusSeconds(RECOVER_STALE_JOB_SECONDS))) {
                return true;
            }
            return job.getStartedAt() == null
                && job.getEnqueuedAt().isBefore(now.minusSeconds(QUEUED_ZOMBIE_JOB_SECONDS));
        }
        if (WorkflowRunExecutionJobEntity.STATUS_RUNNING.equals(job.getStatus())) {
            return !leaseService.hasActiveLease(job.getRunId())
                && job.getStartedAt() != null
                && job.getStartedAt().isBefore(now.minusSeconds(runtimeProperties.getRedis().getStaleNodeThresholdSeconds()));
        }
        return false;
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
     * 中断/重新执行/恢复进度前：写入取消信号、释放 Redis 租约、终态化 queued/running 作业。
     * 旧 Worker 即使模型调用尚未返回，也会在落库前因 job 非 running 而丢弃结果。
     */
    private void abortLingeringExecution(UUID runId) {
        cancellationGuard.requestCancel(runId);
        if (leaseService.hasActiveLease(runId)) {
            leaseService.forceRelease(runId);
            log.warn(
                "已释放遗留执行租约 runId={} requestId={}",
                runId,
                RequestIds.current()
            );
        }
        Instant now = clock.instant();
        for (WorkflowRunExecutionJobEntity job : jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        )) {
            job.markCanceled(now);
            jobRepository.save(job);
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

    /** 已完成的 AI 生成步骤允许「重新执行」：清空全部对话、输出和子智能体结果后从头重跑。 */
    private static boolean isCompletedAiNodeRegenerable(WorkflowNodeRunEntity node) {
        return ("agent".equals(node.getNodeType()) || "parallel_group".equals(node.getNodeType()))
            && "completed".equals(node.getState());
    }

    private static boolean isRecoverableState(String state) {
        // running 允许恢复用于僵死兜底：作业已终态但节点仍停留 running 的极端情况。
        return "failed".equals(state) || "pending".equals(state) || "running".equals(state);
    }

    private static boolean isUserEditAllowed(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        Object allowUserEdit = config.get("allowUserEdit");
        if (Boolean.TRUE.equals(allowUserEdit) || "true".equals(String.valueOf(allowUserEdit))) {
            return true;
        }
        return "追问确认".equals(String.valueOf(config.get("outputMode")));
    }

    private static boolean isClusterAgentEditAllowed(Map<String, Object> config, int agentIndex) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        Object rawAgents = config.get("clusterAgents");
        if (rawAgents instanceof List<?> agents && agentIndex >= 0 && agentIndex < agents.size()) {
            Object rawAgent = agents.get(agentIndex);
            if (rawAgent instanceof Map<?, ?> agentConfig) {
                if (agentConfig.containsKey("allowUserEdit") || agentConfig.containsKey("outputMode")) {
                    Object allowUserEdit = agentConfig.get("allowUserEdit");
                    if (Boolean.TRUE.equals(allowUserEdit) || "true".equals(String.valueOf(allowUserEdit))) {
                        return true;
                    }
                    return "追问确认".equals(String.valueOf(agentConfig.get("outputMode")));
                }
            }
        }
        return isUserEditAllowed(config);
    }

    private static boolean isClusterAgentFollowUpAllowed(Map<String, Object> config, int agentIndex) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        Object rawAgents = config.get("clusterAgents");
        if (rawAgents instanceof List<?> agents && agentIndex >= 0 && agentIndex < agents.size()) {
            Object rawAgent = agents.get(agentIndex);
            if (rawAgent instanceof Map<?, ?> agentConfig) {
                if (agentConfig.containsKey("allowQuestion") || agentConfig.containsKey("outputMode")) {
                    Object allowQuestion = agentConfig.get("allowQuestion");
                    if (Boolean.TRUE.equals(allowQuestion) || "true".equals(String.valueOf(allowQuestion))) {
                        return true;
                    }
                    return "追问确认".equals(String.valueOf(agentConfig.get("outputMode")));
                }
            }
        }
        return isFollowUpAllowed(config);
    }

    private static boolean isFollowUpAllowed(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        Object allowQuestion = config.get("allowQuestion");
        if (Boolean.TRUE.equals(allowQuestion) || "true".equals(String.valueOf(allowQuestion))) {
            return true;
        }
        return "追问确认".equals(String.valueOf(config.get("outputMode")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appendClusterAgentFollowUp(
        Map<String, Object> config,
        int agentIndex,
        WorkflowClusterAgentRunEntity clusterAgent,
        String followUpMessage
    ) {
        Object rawAgents = config.get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agentIndex < 0 || agentIndex >= agents.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_NOT_FOUND", "子智能体配置不存在");
        }
        Object rawAgent = agents.get(agentIndex);
        if (!(rawAgent instanceof Map<?, ?> rawAgentConfig)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_NOT_FOUND", "子智能体配置不存在");
        }
        Map<String, Object> agentConfig = new LinkedHashMap<>((Map<String, Object>) rawAgentConfig);
        List<Map<String, Object>> history = readClusterAgentHistory(agentConfig, clusterAgent.getOutput());
        history.add(Map.of("role", "user", "content", followUpMessage));
        agentConfig.put("conversationHistory", history);
        return agentConfig;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readClusterAgentHistory(Map<String, Object> agentConfig, Map<String, Object> output) {
        Object rawHistory = agentConfig.get("conversationHistory");
        List<Map<String, Object>> history = new ArrayList<>();
        if (rawHistory instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> rawMap) {
                    String role = stringValue(rawMap.get("role"));
                    String content = stringValue(rawMap.get("content"));
                    if (("user".equals(role) || "assistant".equals(role)) && !content.isBlank()) {
                        history.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
                    }
                }
            }
        }
        if (!history.isEmpty()) {
            return history;
        }
        String initialPrompt = firstNonBlank(stringValue(agentConfig.get("userPrompt")), stringValue(agentConfig.get("prompt")));
        if (!initialPrompt.isBlank()) {
            history.add(Map.of("role", "user", "content", initialPrompt));
        }
        String previousAnswer = output == null ? "" : firstNonBlank(
            stringValue(output.get("final_answer")),
            stringValue(output.get("agent_response")),
            stringValue(output.get("summary"))
        );
        if (!previousAnswer.isBlank()) {
            history.add(Map.of("role", "assistant", "content", previousAnswer));
        }
        return history;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> replaceClusterAgentConfig(Object rawAgents, int agentIndex, Map<String, Object> nextAgentConfig) {
        if (!(rawAgents instanceof List<?> agents)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_CLUSTER_AGENT_NOT_FOUND", "子智能体配置不存在");
        }
        List<Map<String, Object>> nextAgents = new ArrayList<>();
        for (int index = 0; index < agents.size(); index++) {
            Object rawAgent = agents.get(index);
            if (index == agentIndex) {
                nextAgents.add(nextAgentConfig);
            } else if (rawAgent instanceof Map<?, ?> rawMap) {
                nextAgents.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
            }
        }
        return nextAgents;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readChatMessagesForFollowUp(
        Map<String, Object> outputs,
        Map<String, Object> config
    ) {
        List<Map<String, Object>> history = new ArrayList<>();
        Object rawMessages = outputs == null ? null : outputs.get("chatMessages");
        if (rawMessages instanceof List<?> messages) {
            for (Object item : messages) {
                if (item instanceof Map<?, ?> rawMap) {
                    String role = String.valueOf(rawMap.get("role")).trim();
                    String content = String.valueOf(rawMap.get("content")).trim();
                    if (("user".equals(role) || "assistant".equals(role)) && !content.isBlank()) {
                        Map<String, Object> message = new LinkedHashMap<>();
                        message.put("role", role);
                        message.put("content", content);
                        if (rawMap.get("processSteps") instanceof List<?> processSteps) {
                            message.put("processSteps", processSteps);
                        }
                        history.add(message);
                    }
                }
            }
        }
        if (!history.isEmpty()) {
            return history;
        }
        String previousAnswer = "";
        if (outputs != null) {
            previousAnswer = firstNonBlank(
                stringValue(outputs.get("final_answer")),
                stringValue(outputs.get("agent_response")),
                stringValue(outputs.get("summary"))
            );
        }
        if (!previousAnswer.isBlank()) {
            String initialUserPrompt = stringValue(config.get("userPrompt"));
            if (!initialUserPrompt.isBlank()) {
                history.add(Map.of("role", "user", "content", initialUserPrompt));
            }
            history.add(Map.of("role", "assistant", "content", previousAnswer));
        }
        return history;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String summarizeAnswer(String answer) {
        String normalized = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157) + "...";
    }

    private static String clusterAgentOutputVariable(Map<String, Object> config, int agentIndex) {
        if (config == null) {
            return "";
        }
        Object rawAgents = config.get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agentIndex < 0 || agentIndex >= agents.size()) {
            return "";
        }
        Object rawAgent = agents.get(agentIndex);
        if (!(rawAgent instanceof Map<?, ?> agent)) {
            return "";
        }
        return stringValue(agent.get("output"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> updateChatMessagesWithAnswer(Object rawMessages, String answer) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (rawMessages instanceof List<?> history) {
            for (Object item : history) {
                if (item instanceof Map<?, ?> rawMap) {
                    messages.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
                }
            }
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("assistant".equals(String.valueOf(messages.get(index).get("role")))) {
                messages.get(index).put("content", answer);
                return messages;
            }
        }
        messages.add(Map.of("role", "assistant", "content", answer));
        return messages;
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
        Map<String, Object> requestPayload = request == null || request.payload() == null ? Map.of() : request.payload();
        validateRequiredInputFields(nodeRun, requestPayload);
        Instant now = clock.instant();
        Map<String, Object> output = new HashMap<>(requestPayload);
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

    /**
     * 输入节点的必填约束必须由后端基于发布版本快照复核，不能只依赖可被绕过的前端表单校验。
     * 历史快照未保存 required 字段时沿用原有“全部必填”语义，避免旧流程静默放宽约束。
     */
    static void validateRequiredInputFields(WorkflowNodeRunEntity nodeRun, Map<String, Object> payload) {
        if (!"user_input".equals(nodeRun.getNodeType())) {
            return;
        }
        Object rawFields = nodeRun.getConfigSnapshot().get("inputFields");
        if (!(rawFields instanceof List<?> fields)) {
            return;
        }
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> field) || Boolean.FALSE.equals(field.get("required"))) {
                continue;
            }
            String variable = String.valueOf(field.get("variable") == null ? "" : field.get("variable")).trim();
            Object value = payload.get(variable);
            if (variable.isBlank() || value == null || (value instanceof String text && text.isBlank())) {
                String label = String.valueOf(field.get("label") == null ? variable : field.get("label")).trim();
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "WORKBENCH_INPUT_REQUIRED",
                    "请填写「" + (label.isBlank() ? variable : label) + "」"
                );
            }
        }
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
            "",
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
                    currentVariables(run, nodeRuns),
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
            run.getTriggerSource(),
            run.getTriggerScheduleId(),
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
            run.getTriggerSource(),
            run.getTriggerScheduleId(),
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
                summary.put("tokenUsage", row.getOutput() == null ? Map.of() : row.getOutput().getOrDefault("tokenUsage", Map.of()));
                summary.put("chatMessages", row.getOutput() == null ? List.of() : row.getOutput().getOrDefault("chatMessages", List.of()));
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

    private Map<String, Object> currentVariables(WorkflowRunEntity run, List<WorkflowNodeRunEntity> nodeRuns) {
        Map<String, Object> variables = new LinkedHashMap<>(WorkflowRuntimeSystemVariables.from(run, clock));
        for (WorkflowNodeRunEntity nodeRun : nodeRuns) {
            if ("completed".equals(nodeRun.getState())) {
                variables.putAll(nodeRun.getOutputSnapshot());
            }
        }
        return variables;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scheduledInputPayload(WorkflowRunEntity run) {
        Map<String, Object> triggerPayload = run.getTriggerPayload();
        Object rawInputPayload = triggerPayload.get("inputPayload");
        if (rawInputPayload instanceof Map<?, ?> rawMap) {
            return new LinkedHashMap<>((Map<String, Object>) rawMap);
        }
        return Map.of();
    }

    private void persistVariableSnapshots(WorkflowRunEntity run, WorkflowNodeRunEntity node, Map<String, Object> output, Instant now) {
        if (output == null || output.isEmpty()) {
            return;
        }

        Set<String> customSensitiveVariables = new HashSet<>();
        WorkflowVersionEntity version = workflowVersionRepository.findById(run.getWorkflowVersionId()).orElse(null);
        if (version != null) {
            VersionSnapshot snapshot = readSnapshot(version);
            if (snapshot != null && snapshot.variables() != null) {
                for (Map<String, Object> varMap : snapshot.variables()) {
                    Object nameObj = varMap.get("name");
                    Object sensitiveObj = varMap.get("sensitive");
                    if (nameObj instanceof String varName && sensitiveObj instanceof Boolean sensitive && sensitive) {
                        customSensitiveVariables.add(varName);
                    }
                }
            }
        }

        List<WorkflowVariableSnapshotEntity> snapshots = output.entrySet().stream()
            .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
            .filter(entry -> !"errorCode".equals(entry.getKey()) && !"errorMessage".equals(entry.getKey()))
            .map(entry -> {
                boolean sensitive = isSensitiveVariable(entry.getKey()) || customSensitiveVariables.contains(entry.getKey());
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
        // tokenUsage 表示模型输入、输出和总 Token 数量，不包含访问凭证；不能因名称含 token 被误判为敏感信息。
        // 若流程设计者确实将同名自定义变量标记为敏感，customSensitiveVariables 仍会优先触发遮蔽。
        if ("tokenusage".equals(normalized) || "token_usage".equals(normalized)) {
            return false;
        }
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

    /** 任务（workflow_runs）级状态中文标签；节点中断 canceled 不在此映射，任务中断后 state 为 paused。 */
    private String stateLabel(String state) {
        return switch (state) {
            case "running" -> "运行中";
            case "paused" -> "已暂停";
            case "completed" -> "已完成";
            case "failed" -> "已失败";
            default -> state;
        };
    }

    /** 待办列表只允许筛选未完成任务常见状态，空字符串表示不过滤。 */
    private String normalizeActiveRunStateFilter(String state) {
        if (state == null || state.isBlank()) {
            return "";
        }
        return switch (state.trim()) {
            case "running", "paused", "failed" -> state.trim();
            default -> "";
        };
    }

    private String normalizeTriggerSourceFilter(String triggerSource) {
        if (triggerSource == null || triggerSource.isBlank()) {
            return "";
        }
        return switch (triggerSource.trim()) {
            case "manual", "schedule" -> triggerSource.trim();
            default -> "";
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

    private Map<String, Object> enrichRuntimeNodeConfig(SnapshotNode node) {
        Map<String, Object> config = new LinkedHashMap<>(node.config());
        if ("agent".equals(node.nodeType()) && !node.outputVariables().isEmpty()) {
            String outputName = node.outputVariables().get(0);
            if (outputName != null && !outputName.isBlank()) {
                config.putIfAbsent("output", outputName.trim());
                config.putIfAbsent("outputVariable", outputName.trim());
            }
        }
        return config;
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
            if (run.isScheduledTrigger() && "user_input".equals(nextNode.getNodeType())) {
                Map<String, Object> scheduledInput = scheduledInputPayload(run);
                validateRequiredInputFields(nextNode, scheduledInput);
                nextNode.complete(scheduledInput, now);
                workflowNodeRunRepository.save(nextNode);
                persistVariableSnapshots(run, nextNode, scheduledInput, now);
                workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                    run.getId(),
                    run.getTenantId(),
                    "schedule_input_applied",
                    "定时任务输入已填充",
                    "系统已使用定时任务配置填充「" + nextNode.getName() + "」并继续执行。",
                    nextNode.getNodeKey(),
                    operatorUserId,
                    Map.of("nodeType", nextNode.getNodeType(), "scheduleId", String.valueOf(run.getTriggerScheduleId())),
                    now
                ));
                return prepareNextNode(tenantId, runId, operatorUserId);
            }
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
                    currentVariables(run, nodeRuns),
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

        // 中断（canceled）/失败（failed）节点重新推进，或待执行节点残留输出快照时，整步重做前必须复位。
        // canceled 是用户主动放弃当前轮次，必须清空全部子智能体；failed 才允许保留成功子智能体供恢复进度复用。
        boolean needsReset = "canceled".equals(nextNode.getState())
            || "failed".equals(nextNode.getState())
            || ("pending".equals(nextNode.getState())
                && nextNode.getOutputSnapshot() != null
                && !nextNode.getOutputSnapshot().isEmpty());
        if (needsReset) {
            boolean fullRestart = "canceled".equals(nextNode.getState());
            if (fullRestart) {
                clearNodeConversationHistory(nextNode, now);
                clusterAgentRunRepository.deleteByNodeRunId(nextNode.getId());
            } else {
                clusterAgentRunRepository.deleteByNodeRunIdAndStatusNot(nextNode.getId(), WorkflowClusterAgentRunEntity.STATUS_SUCCEEDED);
            }
            nextNode.resetToPending(now);
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

        if (!"running".equals(node.getState()) && !"pending".equals(node.getState())) {
            log.info(
                "节点已非活跃，跳过成功落库 runId={} nodeRunId={} state={} requestId={}",
                runId,
                nodeRunId,
                node.getState(),
                RequestIds.current()
            );
            return false;
        }

        node.complete(outputs, now);
        workflowNodeRunRepository.save(node);
        persistVariableSnapshots(run, node, outputs, now);

        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        int completed = (int) nodes.stream().filter(n -> "completed".equals(n.getState())).count();

        if (run.isScheduledTrigger()) {
            workflowRunEventRepository.save(WorkflowRunEventEntity.create(
                run.getId(),
                run.getTenantId(),
                "node_completed",
                "节点已完成",
                executionEventDescription(node, outputs),
                node.getNodeKey(),
                operatorUserId,
                Map.of("nodeType", node.getNodeType(), "triggerSource", "schedule"),
                now
            ));
            return false;
        }

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

    @Transactional(readOnly = true)
    public boolean isScheduledRun(UUID runId) {
        return workflowRunRepository.findById(runId).map(WorkflowRunEntity::isScheduledTrigger).orElse(false);
    }

    @Transactional
    public void continueScheduledRunAfterJob(UUID runId, UUID operatorUserId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null || !run.isScheduledTrigger() || "completed".equals(run.getState()) || "failed".equals(run.getState())) {
            return;
        }
        NextNodeResult next = prepareNextNode(run.getTenantId(), runId, operatorUserId);
        if (next.hasNext() && !next.paused() && requiresManualAdvance(next.nodeType())) {
            enqueueExecution(run.getTenantId(), runId, next.nodeRunId(), next.nodeType(), operatorUserId);
        }
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
