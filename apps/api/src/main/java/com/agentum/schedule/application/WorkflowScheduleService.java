package com.agentum.schedule.application;

import com.agentum.audit.application.AuditService;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.notification.application.NotificationService;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.permission.application.CollaborationAccessPolicy.AccessLevel;
import com.agentum.schedule.domain.WorkflowScheduleEntity;
import com.agentum.schedule.domain.WorkflowScheduleExecutionEntity;
import com.agentum.schedule.infrastructure.WorkflowScheduleExecutionRepository;
import com.agentum.schedule.infrastructure.WorkflowScheduleRepository;
import com.agentum.schedule.interfaces.WorkflowScheduleApi;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.platform.AgentumTimezones;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.application.WorkflowInputDefaultValueResolver;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowScheduleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduleService.class);
    private static final String ACTIVE_STATUS = "active";
    private static final SortWhitelist SCHEDULE_SORT = SortWhitelist.of("updatedAt", "nextRunAt", "lastRunAt", "name");
    private static final SortWhitelist EXECUTION_SORT = SortWhitelist.of("startedAt", "scheduledAt", "completedAt", "status");

    private final WorkflowScheduleRepository scheduleRepository;
    private final WorkflowScheduleExecutionRepository executionRepository;
    private final TenantRepository tenantRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowAccessGrantRepository workflowAccessGrantRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final UserAccountRepository userAccountRepository;
    private final CollaborationAccessPolicy collaborationAccessPolicy;
    private final WorkbenchRuntimeService workbenchRuntimeService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkflowScheduleService(
        WorkflowScheduleRepository scheduleRepository,
        WorkflowScheduleExecutionRepository executionRepository,
        TenantRepository tenantRepository,
        WorkflowDefinitionRepository workflowDefinitionRepository,
        WorkflowVersionRepository workflowVersionRepository,
        WorkflowAccessGrantRepository workflowAccessGrantRepository,
        WorkflowRunRepository workflowRunRepository,
        UserAccountRepository userAccountRepository,
        CollaborationAccessPolicy collaborationAccessPolicy,
        WorkbenchRuntimeService workbenchRuntimeService,
        NotificationService notificationService,
        AuditService auditService,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.scheduleRepository = scheduleRepository;
        this.executionRepository = executionRepository;
        this.tenantRepository = tenantRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.workflowAccessGrantRepository = workflowAccessGrantRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.userAccountRepository = userAccountRepository;
        this.collaborationAccessPolicy = collaborationAccessPolicy;
        this.workbenchRuntimeService = workbenchRuntimeService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowScheduleApi.ScheduleRow> list(UUID tenantId, CurrentUserPrincipal principal, String keyword, String status, int page, int size, String sort) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, sort), SCHEDULE_SORT);
        var result = scheduleRepository.searchVisible(
            tenantId,
            principal.userId(),
            isTenantManager(principal),
            keyword == null ? "" : keyword.trim(),
            normalizeStatusFilter(status),
            pageable
        );
        return PageResponse.from(result.map(this::toRow));
    }

    @Transactional(readOnly = true)
    public WorkflowScheduleApi.WorkflowInputFieldsResponse inputFields(UUID tenantId, CurrentUserPrincipal principal, UUID workflowId) {
        ScheduleWorkflowContext context = requireWorkflowContext(tenantId, principal, workflowId);
        return new WorkflowScheduleApi.WorkflowInputFieldsResponse(
            workflowId,
            context.workflowName(),
            context.version().getVersionNumber(),
            extractInputFields(readSnapshot(context.version()))
        );
    }

    @Transactional(readOnly = true)
    public WorkflowScheduleApi.CronPreviewResponse previewCron(
        UUID tenantId,
        CurrentUserPrincipal principal,
        WorkflowScheduleApi.CronPreviewRequest request
    ) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        CronExpression cron = parseCron(request == null ? null : request.cronExpression());
        return new WorkflowScheduleApi.CronPreviewResponse(
            nextRunAt(cron, clock.instant()),
            AgentumTimezones.businessZone().getId()
        );
    }

    @Transactional
    public WorkflowScheduleApi.ScheduleRow create(UUID tenantId, CurrentUserPrincipal principal, WorkflowScheduleApi.CreateScheduleRequest request) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        UUID workflowId = request == null ? null : request.workflowId();
        ScheduleWorkflowContext context = requireWorkflowContext(tenantId, principal, workflowId);
        String name = normalizeRequired(request == null ? null : request.name(), "SCHEDULE_NAME_REQUIRED", "请输入定时任务名称", 160);
        CronExpression cron = parseCron(request == null ? null : request.cronExpression());
        Map<String, Object> inputPayload = normalizeInputPayload(request == null ? null : request.inputPayload());
        validateRequiredInputs(context.version(), inputPayload);
        Instant now = clock.instant();
        WorkflowScheduleEntity schedule = WorkflowScheduleEntity.create(
            tenantId,
            workflowId,
            context.version().getId(),
            context.version().getVersionNumber(),
            principal.userId(),
            name,
            context.workflowName(),
            cron.toString(),
            normalizeOptional(request == null ? null : request.shortcutKey(), 40),
            normalizeOptional(request == null ? null : request.shortcutLabel(), 80),
            inputPayload,
            nextRunAt(cron, now),
            now
        );
        scheduleRepository.save(schedule);
        auditService.recordOperationLog(
            tenantId,
            principal.userId(),
            principal.username(),
            "CREATE_WORKFLOW_SCHEDULE",
            "WORKFLOW_SCHEDULE",
            schedule.getId().toString(),
            schedule.getName(),
            "创建定时任务「" + schedule.getName() + "」。",
            Map.of("workflowId", workflowId.toString(), "cronExpression", schedule.getCronExpression()),
            null
        );
        log.info("定时任务已创建 tenantId={} userId={} scheduleId={} workflowId={} requestId={}", tenantId, principal.userId(), schedule.getId(), workflowId, RequestIds.current());
        return toRow(schedule);
    }

    @Transactional
    public WorkflowScheduleApi.ScheduleRow update(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId, WorkflowScheduleApi.UpdateScheduleRequest request) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowScheduleEntity schedule = requireWritableSchedule(tenantId, principal, scheduleId);
        ScheduleWorkflowContext context = requireWorkflowContext(tenantId, principal, schedule.getWorkflowId());
        String name = normalizeRequired(request == null ? null : request.name(), "SCHEDULE_NAME_REQUIRED", "请输入定时任务名称", 160);
        CronExpression cron = parseCron(request == null ? null : request.cronExpression());
        Map<String, Object> inputPayload = normalizeInputPayload(request == null ? null : request.inputPayload());
        validateRequiredInputs(context.version(), inputPayload);
        Instant now = clock.instant();
        schedule.update(
            name,
            context.version().getId(),
            context.version().getVersionNumber(),
            context.workflowName(),
            cron.toString(),
            normalizeOptional(request == null ? null : request.shortcutKey(), 40),
            normalizeOptional(request == null ? null : request.shortcutLabel(), 80),
            inputPayload,
            nextRunAt(cron, now),
            now
        );
        if (request != null && request.status() != null && !request.status().isBlank()) {
            schedule.updateStatus(normalizeStatus(request.status()), nextRunAt(cron, now), now);
        }
        scheduleRepository.save(schedule);
        auditService.recordOperationLog(
            tenantId,
            principal.userId(),
            principal.username(),
            "UPDATE_WORKFLOW_SCHEDULE",
            "WORKFLOW_SCHEDULE",
            schedule.getId().toString(),
            schedule.getName(),
            "更新定时任务「" + schedule.getName() + "」。",
            Map.of("cronExpression", schedule.getCronExpression(), "status", schedule.getStatus()),
            null
        );
        return toRow(schedule);
    }

    @Transactional
    public WorkflowScheduleApi.ScheduleRow updateStatus(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId, String status) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowScheduleEntity schedule = requireWritableSchedule(tenantId, principal, scheduleId);
        CronExpression cron = parseCron(schedule.getCronExpression());
        Instant now = clock.instant();
        schedule.updateStatus(normalizeStatus(status), nextRunAt(cron, now), now);
        scheduleRepository.save(schedule);
        auditService.recordOperationLog(
            tenantId,
            principal.userId(),
            principal.username(),
            "UPDATE_WORKFLOW_SCHEDULE_STATUS",
            "WORKFLOW_SCHEDULE",
            schedule.getId().toString(),
            schedule.getName(),
            "调整定时任务状态为 " + schedule.getStatus() + "。",
            Map.of("status", schedule.getStatus()),
            null
        );
        return toRow(schedule);
    }

    @Transactional
    public void delete(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowScheduleEntity schedule = requireWritableSchedule(tenantId, principal, scheduleId);
        scheduleRepository.delete(schedule);
        auditService.recordOperationLog(
            tenantId,
            principal.userId(),
            principal.username(),
            "DELETE_WORKFLOW_SCHEDULE",
            "WORKFLOW_SCHEDULE",
            schedule.getId().toString(),
            schedule.getName(),
            "删除定时任务「" + schedule.getName() + "」。",
            Map.of("workflowId", schedule.getWorkflowId().toString()),
            null
        );
    }

    @Transactional
    public PageResponse<WorkflowScheduleApi.ScheduleExecutionRow> executions(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId, int page, int size) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowScheduleEntity schedule = requireReadableSchedule(tenantId, principal, scheduleId);
        reconcileScheduleExecutions(schedule.getId());
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, "startedAt,desc"), EXECUTION_SORT);
        return PageResponse.from(executionRepository.findByTenantIdAndScheduleIdOrderByStartedAtDesc(tenantId, schedule.getId(), pageable).map(this::toExecutionRow));
    }

    /**
     * 运行被删除后，关联的定时执行记录不能继续停留在“执行中”。
     */
    @Transactional
    public void abortExecutionsForDeletedRun(UUID runId) {
        if (runId == null) {
            return;
        }
        Instant now = clock.instant();
        for (WorkflowScheduleExecutionEntity execution : executionRepository.findByRunId(runId)) {
            if (!WorkflowScheduleExecutionEntity.STATUS_RUNNING.equals(execution.getStatus())) {
                continue;
            }
            execution.abort("关联运行已删除，执行记录已中止。", now);
            executionRepository.save(execution);
            markScheduleState(execution.getScheduleId(), "aborted", now);
        }
    }

    @Transactional
    public WorkflowScheduleApi.TriggerScheduleResponse triggerNow(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        WorkflowScheduleEntity schedule = requireWritableSchedule(tenantId, principal, scheduleId);
        Instant now = clock.instant();
        UUID runId = triggerOne(schedule, now, now).orElseThrow(() -> new ApiException(
            HttpStatus.BAD_REQUEST,
            "SCHEDULE_TRIGGER_FAILED",
            "定时任务触发失败，请检查流程权限和输入配置"
        ));
        WorkflowScheduleEntity refreshed = scheduleRepository.findByIdAndTenantId(scheduleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "定时任务不存在"));
        auditService.recordOperationLog(
            tenantId,
            principal.userId(),
            principal.username(),
            "TRIGGER_WORKFLOW_SCHEDULE",
            "WORKFLOW_SCHEDULE",
            refreshed.getId().toString(),
            refreshed.getName(),
            "手动触发定时任务「" + refreshed.getName() + "」。",
            Map.of("runId", runId.toString(), "triggerType", "manual"),
            null
        );
        log.info(
            "定时任务已手动触发 tenantId={} operatorUserId={} scheduleId={} runId={} requestId={}",
            tenantId,
            principal.userId(),
            scheduleId,
            runId,
            RequestIds.current()
        );
        return new WorkflowScheduleApi.TriggerScheduleResponse(runId, toRow(refreshed));
    }

    @Transactional
    public void triggerDueSchedules() {
        Instant now = clock.instant();
        List<WorkflowScheduleEntity> dueSchedules = scheduleRepository.findDueSchedules(now, PageRequest.of(0, 20));
        for (WorkflowScheduleEntity schedule : dueSchedules) {
            Instant scheduledAt = schedule.getNextRunAt() == null ? now : schedule.getNextRunAt();
            triggerOne(schedule, now, scheduledAt);
        }
    }

    @Transactional
    public void reconcileRunningExecutions() {
        Instant now = clock.instant();
        List<WorkflowScheduleExecutionEntity> executions = executionRepository.findByStatusOrderByUpdatedAtAsc(
            WorkflowScheduleExecutionEntity.STATUS_RUNNING,
            PageRequest.of(0, 50)
        );
        reconcileRunningExecutionBatch(executions, now);
    }

    private void reconcileScheduleExecutions(UUID scheduleId) {
        Instant now = clock.instant();
        reconcileRunningExecutionBatch(
            executionRepository.findByScheduleIdAndStatus(scheduleId, WorkflowScheduleExecutionEntity.STATUS_RUNNING),
            now
        );
    }

    private void reconcileRunningExecutionBatch(List<WorkflowScheduleExecutionEntity> executions, Instant now) {
        Set<UUID> runIds = executions.stream().map(WorkflowScheduleExecutionEntity::getRunId).filter(id -> id != null).collect(Collectors.toSet());
        Map<UUID, WorkflowRunEntity> runsById = workflowRunRepository.findAllById(runIds).stream()
            .collect(Collectors.toMap(WorkflowRunEntity::getId, Function.identity()));
        for (WorkflowScheduleExecutionEntity execution : executions) {
            WorkflowRunEntity run = execution.getRunId() == null ? null : runsById.get(execution.getRunId());
            if (run == null) {
                if (execution.getRunId() != null) {
                    execution.abort("关联运行已删除，执行记录已中止。", now);
                    executionRepository.save(execution);
                    markScheduleState(execution.getScheduleId(), "aborted", now);
                } else {
                    // 触发流程尚未绑定 run 或运行已被删除后遗留的脏记录，不能长期停留在“执行中”。
                    execution.abort("执行记录已失效，系统自动中止。", now);
                    executionRepository.save(execution);
                    markScheduleState(execution.getScheduleId(), "aborted", now);
                }
                continue;
            }
            if ("completed".equals(run.getState())) {
                execution.succeed("定时任务执行成功。", now);
                executionRepository.save(execution);
                markScheduleState(execution.getScheduleId(), "succeeded", now);
                notifyScheduleResult(execution, run, true, "定时任务执行成功", "流程已完成并进入任务记录。");
            } else if ("failed".equals(run.getState())) {
                execution.abort("定时任务执行中止，失败任务已保存到待办。", now);
                executionRepository.save(execution);
                markScheduleState(execution.getScheduleId(), "aborted", now);
                notifyScheduleResult(execution, run, false, "定时任务执行中止", "流程运行失败，已保存到任务中心待办，可进入后查看失败节点并恢复。");
            } else if ("paused".equals(run.getState()) && "human_review".equals(run.getCurrentNodeType())) {
                execution.abort("定时任务等待人工审核，已保存到待办。", now);
                executionRepository.save(execution);
                markScheduleState(execution.getScheduleId(), "aborted", now);
                notifyScheduleResult(execution, run, false, "定时任务等待人工处理", "流程已推进到人工审核节点，系统无法继续自动执行，已保存到任务中心待办。");
            }
        }
    }

    private java.util.Optional<UUID> triggerOne(WorkflowScheduleEntity schedule, Instant now, Instant executionScheduledAt) {
        WorkflowScheduleExecutionEntity execution = WorkflowScheduleExecutionEntity.running(schedule, executionScheduledAt, now);
        executionRepository.save(execution);
        CronExpression cron = parseCron(schedule.getCronExpression());
        Instant nextRunAt = nextRunAt(cron, now.plusSeconds(1));
        try {
            UserAccount owner = userAccountRepository.findById(schedule.getOwnerId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_OWNER_NOT_FOUND", "定时任务所属用户不存在"));
            CurrentUserPrincipal principal = new CurrentUserPrincipal(owner.getId(), owner.getUsername(), schedule.getTenantId(), "business", "business", null);
            Map<String, Object> scheduleSnapshot = new LinkedHashMap<>();
            scheduleSnapshot.put("scheduleId", schedule.getId().toString());
            scheduleSnapshot.put("scheduleName", schedule.getName());
            scheduleSnapshot.put("cronExpression", schedule.getCronExpression());
            scheduleSnapshot.put("shortcutLabel", schedule.getShortcutLabel() == null ? "" : schedule.getShortcutLabel());
            WorkbenchApi.RunDetail detail = workbenchRuntimeService.createScheduledRun(
                schedule.getTenantId(),
                principal,
                schedule.getWorkflowId(),
                schedule.getId(),
                schedule.getName(),
                schedule.getInputPayload(),
                scheduleSnapshot,
                executionScheduledAt
            );
            execution.bindRun(detail.id(), now);
            executionRepository.save(execution);
            schedule.markTriggered(detail.id(), now, nextRunAt, now);
            scheduleRepository.save(schedule);
            log.info("定时任务已触发 tenantId={} scheduleId={} runId={} requestId={}", schedule.getTenantId(), schedule.getId(), detail.id(), RequestIds.current());
            return java.util.Optional.of(detail.id());
        } catch (ApiException exception) {
            abortBeforeRun(schedule, execution, exception.getMessage(), nextRunAt, now);
            return java.util.Optional.empty();
        } catch (RuntimeException exception) {
            log.error("定时任务触发异常 tenantId={} scheduleId={} requestId={}", schedule.getTenantId(), schedule.getId(), RequestIds.current(), exception);
            abortBeforeRun(schedule, execution, "定时任务触发失败，请联系管理员查看日志。", nextRunAt, now);
            return java.util.Optional.empty();
        }
    }

    private void abortBeforeRun(WorkflowScheduleEntity schedule, WorkflowScheduleExecutionEntity execution, String message, Instant nextRunAt, Instant now) {
        execution.abort(message, now);
        executionRepository.save(execution);
        schedule.markTriggered(null, now, nextRunAt, now);
        schedule.markLastRunState("aborted", now);
        scheduleRepository.save(schedule);
        notificationService.publishScheduleResult(
            schedule.getTenantId(),
            schedule.getOwnerId(),
            "定时任务执行中止",
            "### " + schedule.getName() + "\n\n" + message,
            schedule.getId(),
            null
        );
    }

    private void markScheduleState(UUID scheduleId, String state, Instant now) {
        scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
            schedule.markLastRunState(state, now);
            scheduleRepository.save(schedule);
        });
    }

    private void notifyScheduleResult(WorkflowScheduleExecutionEntity execution, WorkflowRunEntity run, boolean succeeded, String title, String body) {
        scheduleRepository.findById(execution.getScheduleId()).ifPresent(schedule -> notificationService.publishScheduleResult(
            execution.getTenantId(),
            execution.getOwnerId(),
            title,
            "### " + schedule.getName()
                + "\n\n- 流程：" + schedule.getWorkflowName()
                + "\n- 运行编号：" + run.getRunNumber()
                + "\n- 执行结果：" + (succeeded ? "成功" : "中止")
                + "\n\n" + body,
            execution.getScheduleId(),
            execution.getOwnerId()
        ));
    }

    private ScheduleWorkflowContext requireWorkflowContext(UUID tenantId, CurrentUserPrincipal principal, UUID workflowId) {
        ensureActiveTenant(tenantId);
        ensureAuthenticated(principal);
        if (workflowId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_WORKFLOW_REQUIRED", "请选择要定时执行的流程");
        }
        WorkflowDefinitionEntity definition = workflowDefinitionRepository.findByIdAndTenantId(workflowId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_DRAFT_NOT_FOUND", "流程不存在"));
        if (!definition.isLaunchEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_WORKFLOW_RECALLED", "该流程入口已被收回，不能创建定时任务");
        }
        WorkflowVersionEntity version = workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_VERSION_REQUIRED", "流程尚未发布，不能创建定时任务"));
        AccessLevel access = resolveAccess(definition, principal.userId(), workflowAccessGrantRepository.findByWorkflowId(workflowId));
        if (!isTenantManager(principal) && !access.canRead()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCHEDULE_WORKFLOW_FORBIDDEN", "当前账号没有该流程的发起权限");
        }
        return new ScheduleWorkflowContext(definition.getName(), version);
    }

    private WorkflowScheduleEntity requireReadableSchedule(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId) {
        WorkflowScheduleEntity schedule = scheduleRepository.findByIdAndTenantId(scheduleId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "定时任务不存在"));
        if (!isTenantManager(principal) && !schedule.getOwnerId().equals(principal.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCHEDULE_READ_FORBIDDEN", "当前账号不能查看该定时任务");
        }
        return schedule;
    }

    private WorkflowScheduleEntity requireWritableSchedule(UUID tenantId, CurrentUserPrincipal principal, UUID scheduleId) {
        WorkflowScheduleEntity schedule = requireReadableSchedule(tenantId, principal, scheduleId);
        if (!schedule.getOwnerId().equals(principal.userId()) && !"tenant_admin".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCHEDULE_WRITE_FORBIDDEN", "当前账号不能修改该定时任务");
        }
        return schedule;
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

    private void validateRequiredInputs(WorkflowVersionEntity version, Map<String, Object> inputPayload) {
        for (WorkflowScheduleApi.InputFieldRow field : extractInputFields(readSnapshot(version))) {
            Object value = inputPayload.get(field.variable());
            if (value instanceof Map<?, ?> binding) {
                String bindingType = stringValue(binding.get(WorkflowInputDefaultValueResolver.SCHEDULE_VALUE_TYPE_KEY));
                if (WorkflowInputDefaultValueResolver.SCHEDULE_SYSTEM_VALUE_TYPE.equals(bindingType)) {
                    continue;
                }
                if (WorkflowInputDefaultValueResolver.SCHEDULE_FIXED_VALUE_TYPE.equals(bindingType)) {
                    value = binding.get(WorkflowInputDefaultValueResolver.SCHEDULE_FIXED_VALUE_KEY);
                }
            } else if (!inputPayload.containsKey(field.variable()) && "system".equals(field.defaultValueSource())) {
                continue;
            }
            if (value == null || (value instanceof String text && text.isBlank())) {
                if (field.required()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_INPUT_REQUIRED", "请配置输入字段「" + field.label() + "」");
                }
                continue;
            }
            String selectedValue = String.valueOf(value);
            if ("select".equals(field.fieldType())
                && field.options().stream().noneMatch(option -> option.value().equals(selectedValue))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_INPUT_OPTION_INVALID", "输入字段「" + field.label() + "」的选项无效");
            }
        }
    }

    private VersionSnapshot readSnapshot(WorkflowVersionEntity version) {
        try {
            return objectMapper.readValue(version.getDefinitionSnapshot(), VersionSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKFLOW_VERSION_SNAPSHOT_INVALID", "流程发布版本快照无法解析");
        }
    }

    private List<WorkflowScheduleApi.InputFieldRow> extractInputFields(VersionSnapshot snapshot) {
        List<WorkflowScheduleApi.InputFieldRow> fields = new ArrayList<>();
        if (snapshot.nodes() == null) {
            return fields;
        }
        for (SnapshotNode node : snapshot.nodes()) {
            if (!"user_input".equals(node.nodeType())) {
                continue;
            }
            Object rawFields = node.config().get("inputFields");
            if (!(rawFields instanceof List<?> inputFields)) {
                continue;
            }
            for (Object item : inputFields) {
                if (!(item instanceof Map<?, ?> rawField)) {
                    continue;
                }
                String variable = stringValue(rawField.get("variable"));
                if (variable.isBlank()) {
                    continue;
                }
                String label = stringValue(rawField.get("label"));
                fields.add(new WorkflowScheduleApi.InputFieldRow(
                    node.nodeId(),
                    node.name(),
                    variable,
                    label.isBlank() ? variable : label,
                    stringValue(rawField.get("placeholder")),
                    !Boolean.FALSE.equals(rawField.get("required")),
                    firstNonBlank(stringValue(rawField.get("valueType")), stringValue(rawField.get("type")), "text"),
                    firstNonBlank(stringValue(rawField.get("fieldType")), "text"),
                    extractInputFieldOptions(rawField.get("options")),
                    stringValue(rawField.get("defaultValue")),
                    firstNonBlank(
                        stringValue(rawField.get("defaultValueSource")),
                        stringValue(rawField.get("defaultValue")).isBlank() ? "none" : "fixed"
                    ),
                    firstNonBlank(stringValue(rawField.get("systemDefaultValue")), "current_date"),
                    firstNonBlank(stringValue(rawField.get("dateGranularity")), "day"),
                    !Boolean.FALSE.equals(rawField.get("allowManualOverride"))
                ));
            }
        }
        return fields;
    }

    private List<WorkflowScheduleApi.InputFieldOptionRow> extractInputFieldOptions(Object rawOptions) {
        if (!(rawOptions instanceof List<?> options)) {
            return List.of();
        }
        List<WorkflowScheduleApi.InputFieldOptionRow> result = new ArrayList<>();
        for (Object option : options) {
            if (!(option instanceof Map<?, ?> rawOption)) {
                continue;
            }
            String value = stringValue(rawOption.get("value"));
            String label = firstNonBlank(stringValue(rawOption.get("label")), value);
            if (!value.isBlank()) {
                result.add(new WorkflowScheduleApi.InputFieldOptionRow(value, label));
            }
        }
        return result;
    }

    private CronExpression parseCron(String cronExpression) {
        String normalized = normalizeRequired(cronExpression, "SCHEDULE_CRON_REQUIRED", "请输入 cron 表达式", 120);
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_CRON_INVALID", "cron 表达式格式不正确");
        }
    }

    private Instant nextRunAt(CronExpression cron, Instant base) {
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(base, AgentumTimezones.businessZone()));
        if (next == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_CRON_NO_NEXT", "cron 表达式无法计算下一次执行时间");
        }
        return next.toInstant();
    }

    private WorkflowScheduleApi.ScheduleRow toRow(WorkflowScheduleEntity schedule) {
        return new WorkflowScheduleApi.ScheduleRow(
            schedule.getId(),
            schedule.getWorkflowId(),
            schedule.getWorkflowName(),
            schedule.getWorkflowVersionNumber(),
            schedule.getName(),
            schedule.getCronExpression(),
            schedule.getShortcutKey(),
            schedule.getShortcutLabel(),
            schedule.getStatus(),
            schedule.getInputPayload(),
            schedule.getNextRunAt(),
            schedule.getLastRunAt(),
            schedule.getLastRunId(),
            schedule.getLastRunState(),
            schedule.getCreatedAt(),
            schedule.getUpdatedAt()
        );
    }

    private WorkflowScheduleApi.ScheduleExecutionRow toExecutionRow(WorkflowScheduleExecutionEntity execution) {
        return new WorkflowScheduleApi.ScheduleExecutionRow(
            execution.getId(),
            execution.getScheduleId(),
            execution.getRunId(),
            execution.getStatus(),
            execution.getScheduledAt(),
            execution.getStartedAt(),
            execution.getCompletedAt(),
            execution.getMessage()
        );
    }

    private String normalizeStatusFilter(String status) {
        if ("active".equals(status) || "paused".equals(status)) {
            return status;
        }
        return "";
    }

    private String normalizeStatus(String status) {
        if ("paused".equals(status)) {
            return WorkflowScheduleEntity.STATUS_PAUSED;
        }
        return WorkflowScheduleEntity.STATUS_ACTIVE;
    }

    private Map<String, Object> normalizeInputPayload(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    private String normalizeRequired(String value, String code, String message, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
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

    private record ScheduleWorkflowContext(String workflowName, WorkflowVersionEntity version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VersionSnapshot(List<SnapshotNode> nodes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SnapshotNode(String nodeId, String nodeType, String name, Map<String, Object> config) {
        SnapshotNode {
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }
}
