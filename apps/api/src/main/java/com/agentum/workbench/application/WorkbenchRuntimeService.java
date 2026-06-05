package com.agentum.workbench.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.CollaborationAccessPolicy.AccessLevel;
import com.agentum.shared.api.ApiException;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
        Clock clock
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
    public PageResponse<WorkbenchApi.TaskRunRow> listRuns(
        UUID tenantId,
        CurrentUserPrincipal principal,
        String keyword,
        String state,
        int page,
        int size,
        String sort
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), RUN_SORT);
        Page<WorkflowRunEntity> resultPage = workflowRunRepository.searchVisibleRuns(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            keyword == null ? "" : keyword.trim(),
            state == null || "all".equals(state) ? "" : state.trim(),
            pageable
        );
        Set<UUID> userIds = resultPage.getContent().stream().map(WorkflowRunEntity::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserAccount> usersById = loadUsersById(userIds);
        Set<UUID> runIds = resultPage.getContent().stream().map(WorkflowRunEntity::getId).collect(Collectors.toSet());
        Set<UUID> runsWithOpenTodo = runIds.isEmpty()
            ? Set.of()
            : workflowWaitingEventRepository.findByRunIdInAndStatus(runIds, "open").stream()
                .map(WorkflowWaitingEventEntity::getRunId)
                .collect(Collectors.toSet());
        return PageResponse.from(resultPage.map(run -> toTaskRunRow(run, usersById, runsWithOpenTodo.contains(run.getId()))));
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
        int nextIndex = Math.max(0, nodeRun.getSortOrder() + 1);
        WorkflowWaitingEventEntity openTodo = advanceUntilPause(run, nodes, nextIndex, principal.userId(), now, new ArrayList<>());
        workflowRunRepository.save(run);
        return getRunDetail(tenantId, principal, run.getId());
    }

    @Transactional(readOnly = true)
    public long countVisibleOpenTodos(UUID tenantId, CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        return workflowWaitingEventRepository.countVisibleOpenTodos(tenantId, principal.userId(), isTenantManager(principal));
    }

    @Transactional(readOnly = true)
    public long countVisibleRunningRuns(UUID tenantId, CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        return workflowRunRepository.countVisibleByStateIn(tenantId, principal.userId(), isTenantManager(principal), List.of("running", "paused"));
    }

    @Transactional(readOnly = true)
    public List<WorkbenchApi.PendingTodoRow> listPendingTodos(UUID tenantId, CurrentUserPrincipal principal, int limit) {
        ensureAuthenticated(principal);
        List<WorkflowWaitingEventEntity> todos = workflowWaitingEventRepository.findVisibleOpenTodos(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            PageRequest.of(0, Math.max(1, limit))
        );
        if (todos.isEmpty()) {
            return List.of();
        }
        Map<UUID, WorkflowRunEntity> runsById = workflowRunRepository.findAllById(todos.stream().map(WorkflowWaitingEventEntity::getRunId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(WorkflowRunEntity::getId, Function.identity()));
        return todos.stream().map(todo -> toPendingTodoRow(todo, runsById.get(todo.getRunId()), null)).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkbenchApi.RecentRunRow> listRecentRuns(UUID tenantId, CurrentUserPrincipal principal, int limit) {
        ensureAuthenticated(principal);
        Page<WorkflowRunEntity> page = workflowRunRepository.searchVisibleRuns(
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
        return new WorkbenchApi.RunDetail(
            run.getId(),
            run.getTitle(),
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
            openTodo == null ? null : toPendingTodoRow(openTodo, run, null)
        );
    }

    private WorkbenchApi.TaskRunRow toTaskRunRow(WorkflowRunEntity run, Map<UUID, UserAccount> usersById, boolean hasOpenTodo) {
        UserAccount owner = run.getCreatedBy() == null ? null : usersById.get(run.getCreatedBy());
        return new WorkbenchApi.TaskRunRow(
            run.getId(),
            run.getTitle(),
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

    private WorkbenchApi.PendingTodoRow toPendingTodoRow(WorkflowWaitingEventEntity todo, WorkflowRunEntity run, WorkflowNodeRunEntity node) {
        return new WorkbenchApi.PendingTodoRow(
            todo.getId(),
            todo.getRunId(),
            todo.getNodeRunId(),
            todo.getTitle(),
            run == null ? "" : run.getWorkflowName(),
            node == null ? todo.getTitle() : node.getName(),
            todo.getWaitingReason(),
            "user".equals(todo.getWaitingForType()) ? "当前处理人" : todo.getWaitingForType(),
            actionLabelFromType(todo.getActionType()),
            todo.getCreatedAt()
        );
    }

    private WorkbenchApi.RecentRunRow toRecentRunRow(WorkflowRunEntity run, Map<UUID, UserAccount> usersById) {
        UserAccount owner = run.getCreatedBy() == null ? null : usersById.get(run.getCreatedBy());
        return new WorkbenchApi.RecentRunRow(
            run.getId(),
            run.getTitle(),
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
        return workflowName + "任务";
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
}
