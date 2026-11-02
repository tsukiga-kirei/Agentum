package com.agentum.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.audit.application.AuditService;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.notification.application.NotificationService;
import com.agentum.schedule.domain.WorkflowScheduleEntity;
import com.agentum.schedule.domain.WorkflowScheduleExecutionEntity;
import com.agentum.schedule.infrastructure.WorkflowScheduleExecutionRepository;
import com.agentum.schedule.infrastructure.WorkflowScheduleRepository;
import com.agentum.schedule.interfaces.WorkflowScheduleApi;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workflow.application.WorkflowAccessService;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowScheduleServiceTest {

    private final WorkflowScheduleRepository scheduleRepository = mock(WorkflowScheduleRepository.class);
    private final WorkflowScheduleExecutionRepository executionRepository = mock(WorkflowScheduleExecutionRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final WorkflowDefinitionRepository workflowDefinitionRepository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
    private final WorkflowAccessGrantRepository workflowAccessGrantRepository = mock(WorkflowAccessGrantRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final WorkflowAccessService workflowAccessService = mock(WorkflowAccessService.class);
    private final WorkbenchRuntimeService workbenchRuntimeService = mock(WorkbenchRuntimeService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-02T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"));

    private WorkflowScheduleService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowScheduleService(
            scheduleRepository,
            executionRepository,
            tenantRepository,
            workflowDefinitionRepository,
            workflowVersionRepository,
            workflowAccessGrantRepository,
            workflowRunRepository,
            userAccountRepository,
            workflowAccessService,
            workbenchRuntimeService,
            notificationService,
            auditService,
            objectMapper,
            clock
        );
    }

    @Test
    void shouldRejectScheduleWhenWorkflowContainsOptionalAttachmentField() {
        WorkflowScheduleApi.InputFieldRow attachmentField = new WorkflowScheduleApi.InputFieldRow(
            "input-node", "材料提交", "materials", "附件材料", "请选择附件", false,
            "file", "file", List.of(), "", "fixed", "", "date", true
        );

        assertThatThrownBy(() -> WorkflowScheduleService.validateScheduleInputCompatibility(List.of(attachmentField)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("附件字段")
            .satisfies(error -> assertThat(((ApiException) error).getCode())
                .isEqualTo("SCHEDULE_ATTACHMENT_INPUT_UNSUPPORTED"));
    }

    @Test
    void reconcileRunningExecutions_shouldAbortExecutionWhenRunPausedAtUserInput() {
        UUID tenantId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        WorkflowScheduleEntity schedule = WorkflowScheduleEntity.create(
            tenantId, workflowId, versionId, 1, ownerId, "财务月报", "财务月报生成",
            "0 0 9 1 * ?", null, null, Map.of(), now, now
        );
        WorkflowScheduleExecutionEntity execution = WorkflowScheduleExecutionEntity.running(schedule, now, now);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            tenantId, workflowId, versionId, 1, "财务月报（定时执行）", "财务月报生成",
            ownerId, 3, "RUN-20261102-0001", now
        );
        execution.bindRun(run.getId(), now);
        run.pauseAt("input_node", "补充数据", "user_input", 1, now);

        when(executionRepository.findByStatusOrderByUpdatedAtAsc(eq("running"), any()))
            .thenReturn(List.of(execution));
        when(workflowRunRepository.findAllById(anySet())).thenReturn(List.of(run));
        when(scheduleRepository.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        service.reconcileRunningExecutions();

        assertThat(execution.getStatus()).isEqualTo(WorkflowScheduleExecutionEntity.STATUS_ABORTED);
        assertThat(execution.getMessage()).isEqualTo("定时任务等待补充输入，已保存到待办。");
        verify(executionRepository).save(execution);
        verify(notificationService).publishScheduleResult(
            eq(tenantId),
            eq(ownerId),
            eq("定时任务等待补充输入"),
            anyString(),
            eq(schedule.getId()),
            eq(ownerId)
        );
    }

    @Test
    void reconcileRunningExecutions_shouldAbortExecutionWhenRunPausedAtDelivery() {
        UUID tenantId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        WorkflowScheduleEntity schedule = WorkflowScheduleEntity.create(
            tenantId, workflowId, versionId, 1, ownerId, "财务月报", "财务月报生成",
            "0 0 9 1 * ?", null, null, Map.of(), now, now
        );
        WorkflowScheduleExecutionEntity execution = WorkflowScheduleExecutionEntity.running(schedule, now, now);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            tenantId, workflowId, versionId, 1, "财务月报（定时执行）", "财务月报生成",
            ownerId, 3, "RUN-20261102-0002", now
        );
        execution.bindRun(run.getId(), now);
        run.pauseAt("delivery_node", "交付确认", "delivery", 2, now);

        when(executionRepository.findByStatusOrderByUpdatedAtAsc(eq("running"), any()))
            .thenReturn(List.of(execution));
        when(workflowRunRepository.findAllById(anySet())).thenReturn(List.of(run));
        when(scheduleRepository.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        service.reconcileRunningExecutions();

        assertThat(execution.getStatus()).isEqualTo(WorkflowScheduleExecutionEntity.STATUS_ABORTED);
        assertThat(execution.getMessage()).isEqualTo("定时任务等待交付确认，已保存到待办。");
        verify(executionRepository).save(execution);
        verify(notificationService).publishScheduleResult(
            eq(tenantId),
            eq(ownerId),
            eq("定时任务等待交付确认"),
            anyString(),
            eq(schedule.getId()),
            eq(ownerId)
        );
    }

    @Test
    void reconcileRunningExecutions_shouldAbortExecutionWhenRunPausedAtInterruptedAgent() {
        UUID tenantId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        WorkflowScheduleEntity schedule = WorkflowScheduleEntity.create(
            tenantId, workflowId, versionId, 1, ownerId, "财务月报", "财务月报生成",
            "0 0 9 1 * ?", null, null, Map.of(), now, now
        );
        WorkflowScheduleExecutionEntity execution = WorkflowScheduleExecutionEntity.running(schedule, now, now);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            tenantId, workflowId, versionId, 1, "财务月报（定时执行）", "财务月报生成",
            ownerId, 3, "RUN-20261102-0003", now
        );
        execution.bindRun(run.getId(), now);
        // 用户中断执行后，节点 canceled，run pauseAt 该 agent 节点
        run.pauseAt("agent_node", "研报智能体", "agent", 1, now);

        when(executionRepository.findByStatusOrderByUpdatedAtAsc(eq("running"), any()))
            .thenReturn(List.of(execution));
        when(workflowRunRepository.findAllById(anySet())).thenReturn(List.of(run));
        when(scheduleRepository.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        service.reconcileRunningExecutions();

        assertThat(execution.getStatus()).isEqualTo(WorkflowScheduleExecutionEntity.STATUS_ABORTED);
        assertThat(execution.getMessage()).isEqualTo("定时任务执行中止，已保存到任务记录。");
        verify(executionRepository).save(execution);
        verify(notificationService).publishScheduleResult(
            eq(tenantId),
            eq(ownerId),
            eq("定时任务执行中止"),
            anyString(),
            eq(schedule.getId()),
            eq(ownerId)
        );
    }
}
