package com.agentum.workbench.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.CollaborationAccessPolicy.AccessLevel;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.ClientDisconnectSupport;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowRunEventEntity;
import com.agentum.workflow.domain.WorkflowVariableSnapshotEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.domain.WorkflowWaitingEventEntity;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunEventRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.agentum.workflow.infrastructure.WorkflowVariableSnapshotRepository;
import com.agentum.workflow.infrastructure.WorkflowWaitingEventRepository;
import com.agentum.agent.application.AgentRuntimeService;
import com.agentum.agent.application.AgentRuntimeRequest;
import com.agentum.delivery.application.DeliveryRuntimeService;
import com.agentum.delivery.application.DeliveryRuntimeRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.scheduling.annotation.Async;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AgentRuntimeService agentRuntimeService;
    private final DeliveryRuntimeService deliveryRuntimeService;
    private final RunStreamEmitterRegistry runStreamEmitterRegistry;
    /** 防止同一任务并发重复推进，导致子智能体双开。 */
    private final Set<UUID> advancingRuns = ConcurrentHashMap.newKeySet();

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
        AgentRuntimeService agentRuntimeService,
        DeliveryRuntimeService deliveryRuntimeService,
        RunStreamEmitterRegistry runStreamEmitterRegistry
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
        this.agentRuntimeService = agentRuntimeService;
        this.deliveryRuntimeService = deliveryRuntimeService;
        this.runStreamEmitterRegistry = runStreamEmitterRegistry;
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
        return workflowRunRepository.countVisibleByStateIn(tenantId, principal.userId(), isTenantManager(principal), List.of("running", "paused"));
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
            openTodo == null ? null : toOpenTodoRow(openTodo, run)
        );
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
            node.getOutputSnapshot(),
            node.getConfigSnapshot(),
            node.getSortOrder()
        );
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
            run.complete(completed, now);
            workflowRunRepository.save(run);
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

        nextNode.start(now);
        workflowNodeRunRepository.save(nextNode);
        run.markRunning(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
        workflowRunRepository.save(run);
        return new NextNodeResult(true, nextNode.getId(), nextNode.getNodeType(), nextNode.getName(), false);
    }

    @Transactional
    public void saveNodeSuccess(UUID runId, UUID nodeRunId, Map<String, Object> outputs, UUID operatorUserId) {
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

        // 智能体/多智能体/交付完成后停在当前节点，等待用户确认后再 prepareNextNode 推进下一步。
        if (requiresManualAdvance(node.getNodeType())) {
            run.pauseAt(node.getNodeKey(), node.getName(), node.getNodeType(), completed, now);
        } else {
            int nextIndex = node.getSortOrder() + 1;
            if (nextIndex < nodes.size()) {
                WorkflowNodeRunEntity nextNode = nodes.get(nextIndex);
                run.pauseAt(nextNode.getNodeKey(), nextNode.getName(), nextNode.getNodeType(), completed, now);
            } else {
                run.complete(completed, now);
            }
        }
        workflowRunRepository.save(run);

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
    }

    @Transactional
    public void saveNodeFailure(UUID runId, UUID nodeRunId, String errorCode, String errorMessage, UUID operatorUserId) {
        Instant now = clock.instant();
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findById(nodeRunId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));

        List<WorkflowNodeRunEntity> nodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        int completed = (int) nodes.stream().filter(n -> "completed".equals(n.getState())).count();

        failNode(run, node, completed, operatorUserId, now, errorCode, errorMessage, new ArrayList<>());
        workflowRunRepository.save(run);
    }

    @Async
    public void advanceSingleStep(UUID tenantId, CurrentUserPrincipal principal, UUID runId) {
        if (!advancingRuns.add(runId)) {
            // 刷新页面后前端可能再次 POST /advance；此时后台仍在执行，禁止向 SSE 发 [DONE]，
            // 否则会误断开用户刚建立的新连接，导致页面与真实执行进度脱节。
            log.info("任务正在推进中，跳过重复请求 runId={} userId={} requestId={}", runId, principal.userId(), RequestIds.current());
            return;
        }
        UUID nodeRunId = null;
        boolean nodeSucceeded = false;
        try {
            String nowStr = clock.instant().toString();
            sendSseEvent(runId, "connected", eventPayload(runId, null, nowStr, Map.of(
                "currentState", "connected"
            )));

            NextNodeResult nextNode = prepareNextNode(tenantId, runId, principal.userId());

            if (!nextNode.hasNext()) {
                sendSseEvent(runId, "run_completed", eventPayload(runId, null, clock.instant().toString(), Map.of(
                    "totalDurationMs", 0,
                    "completedNodeCount", 0
                )));
                sendSseEvent(runId, "message", "[DONE]");
                completeSseEmitter(runId);
                return;
            }

            if (nextNode.paused()) {
                sendSseEvent(runId, "run_paused", eventPayload(runId, null, clock.instant().toString(), Map.of(
                    "nextNodeRunId", nextNode.nodeRunId().toString(),
                    "nextNodeName", nextNode.nodeName(),
                    "nextNodeType", nextNode.nodeType(),
                    "reason", waitingReason(nextNode.nodeType())
                )));
                sendSseEvent(runId, "message", "[DONE]");
                completeSseEmitter(runId);
                return;
            }

            nodeRunId = nextNode.nodeRunId();
            String nodeType = nextNode.nodeType();
            String nodeName = nextNode.nodeName();

            sendSseEvent(runId, "node_started", eventPayload(runId, nodeRunId, clock.instant().toString(), Map.of(
                "nodeType", nodeType,
                "nodeName", nodeName
            )));

            WorkflowNodeRunEntity nodeRun = workflowNodeRunRepository.findById(nodeRunId).get();
            Map<String, Object> variables = getVariablesBeforeNode(runId, nodeRun.getSortOrder());

            Map<String, Object> outputs;
            if ("agent".equals(nodeType)) {
                outputs = executeStreamingAgent(runId, nodeRun, variables, principal.userId());
            } else if ("parallel_group".equals(nodeType)) {
                outputs = executeStreamingParallelGroup(runId, nodeRun, variables, principal.userId());
            } else if ("delivery".equals(nodeType)) {
                WorkflowRunEntity run = workflowRunRepository.findById(runId).get();
                outputs = deliveryRuntimeService.execute(new DeliveryRuntimeRequest(
                    run,
                    nodeRun,
                    nodeRun.getConfigSnapshot(),
                    variables,
                    principal.userId()
                )).outputs();
            } else {
                WorkflowRunEntity run = workflowRunRepository.findById(runId).get();
                outputs = workflowRuntimeExecutor.execute(new WorkflowRuntimeExecutor.ExecutionRequest(
                    run,
                    nodeRun,
                    variables,
                    principal.userId()
                )).outputs();
            }

            saveNodeSuccess(runId, nodeRunId, outputs, principal.userId());
            nodeSucceeded = true;

            sendSseEvent(runId, "node_completed", eventPayload(runId, nodeRunId, clock.instant().toString(), Map.of(
                "outputs", outputs
            )));

            List<WorkflowNodeRunEntity> allNodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
            WorkflowNodeRunEntity finishedNode = workflowNodeRunRepository.findById(nodeRunId).orElse(null);
            if (finishedNode != null && requiresManualAdvance(finishedNode.getNodeType())) {
                sendSseEvent(runId, "run_paused", eventPayload(runId, null, clock.instant().toString(), Map.of(
                    "nextNodeRunId", nodeRunId.toString(),
                    "nextNodeName", nodeName,
                    "nextNodeType", nodeType,
                    "reason", "等待用户确认后再执行下一步"
                )));
            } else {
                int nextIndex = finishedNode == null ? allNodes.size() : finishedNode.getSortOrder() + 1;
                if (nextIndex < allNodes.size()) {
                    WorkflowNodeRunEntity next = allNodes.get(nextIndex);
                    sendSseEvent(runId, "run_paused", eventPayload(runId, null, clock.instant().toString(), Map.of(
                        "nextNodeRunId", next.getId().toString(),
                        "nextNodeName", next.getName(),
                        "nextNodeType", next.getNodeType(),
                        "reason", "等待用户点击下一步"
                    )));
                } else {
                    sendSseEvent(runId, "run_completed", eventPayload(runId, null, clock.instant().toString(), Map.of(
                        "totalDurationMs", 0,
                        "completedNodeCount", allNodes.size()
                    )));
                }
            }

            sendSseEvent(runId, "message", "[DONE]");
            completeSseEmitter(runId);

        } catch (ApiException e) {
            log.warn("执行流式步骤 API 异常 runId={} code={} msg={}", runId, e.getCode(), e.getMessage());
            if (nodeRunId != null && !nodeSucceeded) {
                try {
                    saveNodeFailure(runId, nodeRunId, e.getCode(), e.getMessage(), principal.userId());
                } catch (Exception saveException) {
                    log.error("保存节点失败状态时出错 runId={} nodeRunId={} requestId={}", runId, nodeRunId, RequestIds.current(), saveException);
                }
            }
            sendSseEvent(runId, "node_failed", eventPayload(runId, nodeRunId, clock.instant().toString(), Map.of(
                "errorCode", e.getCode(),
                "errorMessage", e.getMessage()
            )));
            sendSseEvent(runId, "message", "[DONE]");
            completeSseEmitter(runId);
        } catch (Exception e) {
            log.error("执行流式步骤系统异常 runId={}", runId, e);
            if (nodeRunId != null && !nodeSucceeded) {
                try {
                    saveNodeFailure(runId, nodeRunId, "WORKBENCH_NODE_EXECUTION_FAILED", e.getMessage(), principal.userId());
                } catch (Exception saveException) {
                    log.error("保存节点失败状态时出错 runId={} nodeRunId={} requestId={}", runId, nodeRunId, RequestIds.current(), saveException);
                }
            }
            sendSseEvent(runId, "node_failed", eventPayload(runId, nodeRunId, clock.instant().toString(), Map.of(
                "errorCode", "WORKBENCH_NODE_EXECUTION_FAILED",
                "errorMessage", "执行异常: " + e.getMessage()
            )));
            sendSseEvent(runId, "message", "[DONE]");
            completeSseEmitter(runId);
        } finally {
            advancingRuns.remove(runId);
        }
    }

    private Map<String, Object> getVariablesBeforeNode(UUID runId, int sortOrder) {
        List<WorkflowNodeRunEntity> completedNodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId)
            .stream()
            .filter(n -> "completed".equals(n.getState()) && n.getSortOrder() < sortOrder)
            .toList();
        Map<String, Object> variables = new HashMap<>();
        for (WorkflowNodeRunEntity node : completedNodes) {
            variables.putAll(node.getOutputSnapshot());
        }
        return variables;
    }

    private Map<String, Object> executeStreamingAgent(UUID runId, WorkflowNodeRunEntity nodeRun, Map<String, Object> variables, UUID operatorUserId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId).get();
        sendSseEvent(runId, "agent_thinking", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
            "phase", "preparing",
            "message", "正在装配 Agent 工具箱与上下文..."
        )));

        AgentRuntimeRequest agentRequest = new AgentRuntimeRequest(
            run,
            nodeRun,
            nodeRun.getConfigSnapshot(),
            variables,
            Map.of(),
            operatorUserId
        );

        return new LinkedHashMap<>(agentRuntimeService.executeStreaming(agentRequest, new AgentRuntimeService.AgentRuntimeEventSink() {
            private final StringBuilder accumulated = new StringBuilder();

            @Override
            public void onPhase(String phase, String message) {
                sendSseEvent(runId, "agent_thinking", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "phase", phase,
                    "message", message
                )));
            }

            @Override
            public void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
                sendSseEvent(runId, "agent_tool_call", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "toolName", toolName,
                    "toolType", toolType,
                    "status", status,
                    "result", summarizeText(result),
                    "durationMs", durationMs
                )));
            }

            @Override
            public void onToken(String deltaContent, String accumulatedContent) {
                if (accumulatedContent != null && !accumulatedContent.isBlank()) {
                    accumulated.setLength(0);
                    accumulated.append(accumulatedContent);
                } else if (deltaContent != null && !deltaContent.isBlank()) {
                    accumulated.append(deltaContent);
                }
                sendSseEvent(runId, "agent_streaming", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "deltaContent", deltaContent == null ? "" : deltaContent,
                    "accumulatedContent", accumulated.toString()
                )));
            }

            @Override
            public void onCompleted(String answer) {
                accumulated.setLength(0);
                accumulated.append(answer == null ? "" : answer);
                sendSseEvent(runId, "agent_streaming", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "deltaContent", "",
                    "accumulatedContent", accumulated.toString()
                )));
                sendSseEvent(runId, "agent_thinking", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "phase", "completed",
                    "message", "智能体已完成 final_answer。"
                )));
            }

            @Override
            public void onFailed(String code, String message) {
                sendSseEvent(runId, "agent_thinking", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "phase", "failed",
                    "message", "智能体执行出错: " + message
                )));
            }
        }).outputs());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeStreamingParallelGroup(UUID runId, WorkflowNodeRunEntity nodeRun, Map<String, Object> variables, UUID operatorUserId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId).get();
        String nowStr = clock.instant().toString();

        Object rawAgents = nodeRun.getConfigSnapshot().get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agents.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>(variables);
            output.put("summary", "智能体集群未配置子智能体，已透传上游变量。");
            return output;
        }

        Map<String, Object> currentVars = new LinkedHashMap<>(variables);
        List<Map<String, Object>> summaries = new ArrayList<>();
        
        int agentIndex = 0;
        for (Object rawAgent : agents) {
            if (!(rawAgent instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> agentConfig = new LinkedHashMap<>((Map<String, Object>) rawMap);
            String agentName = stringValue(agentConfig.get("name"), "子智能体");

            sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), nowStr, Map.of(
                "agentIndex", agentIndex,
                "agentName", agentName,
                "eventType", "started"
            )));

            AgentRuntimeRequest agentRequest = new AgentRuntimeRequest(
                run,
                nodeRun,
                agentConfig,
                currentVars,
                Map.of(),
                operatorUserId
            );

            final int idx = agentIndex;
            final String name = agentName;

            Map<String, Object> agentOutput;
            try {
                agentOutput = agentRuntimeService.executeStreaming(agentRequest, new AgentRuntimeService.AgentRuntimeEventSink() {
                private final StringBuilder subAccumulated = new StringBuilder();

                @Override
                public void onPhase(String phase, String message) {
                    sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                        "agentIndex", idx,
                        "agentName", name,
                        "eventType", "phase",
                        "phase", phase,
                        "message", message
                    )));
                }

                @Override
                public void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
                    sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                        "agentIndex", idx,
                        "agentName", name,
                        "eventType", "tool_call",
                        "toolName", toolName,
                        "toolType", toolType,
                        "toolStatus", status,
                        "result", summarizeText(result),
                        "durationMs", durationMs
                    )));
                }

                @Override
                public void onToken(String deltaContent, String accumulatedContent) {
                    if (accumulatedContent != null && !accumulatedContent.isBlank()) {
                        subAccumulated.setLength(0);
                        subAccumulated.append(accumulatedContent);
                    } else if (deltaContent != null && !deltaContent.isBlank()) {
                        subAccumulated.append(deltaContent);
                    }
                    sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                        "agentIndex", idx,
                        "agentName", name,
                        "eventType", "streaming",
                        "deltaContent", deltaContent == null ? "" : deltaContent,
                        "accumulatedContent", subAccumulated.toString()
                    )));
                }

                @Override
                public void onCompleted(String answer) {
                    subAccumulated.setLength(0);
                    subAccumulated.append(answer == null ? "" : answer);
                    sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                        "agentIndex", idx,
                        "agentName", name,
                        "eventType", "streaming",
                        "deltaContent", "",
                        "accumulatedContent", subAccumulated.toString()
                    )));
                }

                @Override
                public void onFailed(String code, String message) {
                    sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                        "agentIndex", idx,
                        "agentName", name,
                        "eventType", "failed",
                        "errorCode", code,
                        "errorMessage", message
                    )));
                }
            }).outputs();
            } catch (Exception exception) {
                String errorCode = exception instanceof ApiException apiException
                    ? apiException.getCode()
                    : "CLUSTER_AGENT_FAILED";
                String errorMessage = exception instanceof ApiException apiException
                    ? apiException.getMessage()
                    : "子智能体执行失败，请稍后重试";
                log.warn(
                    "智能体集群子智能体执行失败 runId={} nodeRunId={} agentIndex={} agentName={} errorCode={} requestId={}",
                    runId,
                    nodeRun.getId(),
                    idx,
                    name,
                    errorCode,
                    RequestIds.current(),
                    exception
                );
                sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                    "agentIndex", idx,
                    "agentName", name,
                    "eventType", "failed",
                    "errorCode", errorCode,
                    "errorMessage", errorMessage
                )));
                summaries.add(Map.of(
                    "name", name,
                    "summary", errorMessage
                ));
                agentIndex++;
                continue;
            }

            currentVars.putAll(agentOutput);
            
            String summaryText = stringValue(agentOutput.get("summary"), "已完成");
            summaries.add(Map.of(
                "name", agentName,
                "summary", summaryText
            ));

            sendSseEvent(runId, "cluster_agent", eventPayload(runId, nodeRun.getId(), clock.instant().toString(), Map.of(
                "agentIndex", idx,
                "agentName", name,
                "eventType", "completed",
                "outputSummary", summaryText
            )));

            agentIndex++;
        }

        currentVars.put("clusterAgents", summaries);
        String finalAnswer = clusterFinalAnswer(summaries);
        currentVars.put("final_answer", finalAnswer);
        currentVars.put("agent_response", finalAnswer);
        currentVars.put("summary", "智能体集群已完成 " + summaries.size() + " 个子智能体。");
        return currentVars;
    }

    private void sendSseEvent(UUID runId, String eventType, Object data) {
        if (runId == null) {
            return;
        }
        SseEmitter emitter = runStreamEmitterRegistry.current(runId);
        if (emitter == null) {
            return;
        }
        AtomicBoolean open = runStreamEmitterRegistry.openState(emitter);
        if (!open.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                .name(eventType)
                .data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            open.set(false);
            runStreamEmitterRegistry.markClosed(emitter);
            if (ClientDisconnectSupport.isClientDisconnect(exception)) {
                log.debug("SSE 连接已关闭，跳过推送 eventType={} runId={}", eventType, runId);
                return;
            }
            log.warn("发送 SSE 事件失败 eventType={} runId={} error={}", eventType, runId, exception.getMessage());
        }
    }

    private void completeSseEmitter(UUID runId) {
        if (runId == null) {
            return;
        }
        SseEmitter emitter = runStreamEmitterRegistry.current(runId);
        if (emitter == null) {
            return;
        }
        AtomicBoolean open = runStreamEmitterRegistry.openState(emitter);
        open.set(false);
        runStreamEmitterRegistry.markClosed(emitter);
        try {
            emitter.complete();
        } catch (Exception exception) {
            if (!ClientDisconnectSupport.isClientDisconnect(exception)) {
                log.debug("关闭 SSE 连接失败 runId={} message={}", runId, exception.getMessage());
            }
        }
    }

    private Map<String, Object> eventPayload(UUID runId, UUID nodeRunId, String timestamp, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId.toString());
        if (nodeRunId != null) {
            payload.put("nodeRunId", nodeRunId.toString());
        }
        payload.put("timestamp", timestamp);
        if (extra != null) {
            payload.putAll(extra);
        }
        return payload;
    }

    private static String stringValue(Object value, String fallback) {
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static String summarizeText(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "智能体已完成模型调用。";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private static String clusterFinalAnswer(List<Map<String, Object>> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "智能体集群未生成子智能体结论。";
        }
        StringBuilder result = new StringBuilder("## 智能体集群结论\n");
        for (Map<String, Object> summary : summaries) {
            result.append("\n### ")
                .append(stringValue(summary.get("name"), "子智能体"))
                .append("\n")
                .append(stringValue(summary.get("summary"), "已完成"))
                .append("\n");
        }
        return result.toString();
    }
}
