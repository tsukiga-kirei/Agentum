package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.agent.application.PromptContentResolver;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowClusterAgentRunEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
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
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.agentum.runtime.messaging.NodeExecuteCommand;
import com.agentum.runtime.messaging.NodeExecuteCommandPublisher;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WorkbenchRuntimeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DESIGNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final Instant NOW = Instant.parse("2026-06-05T09:30:00Z");

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final WorkflowDefinitionRepository workflowDefinitionRepository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
    private final WorkflowAccessGrantRepository workflowAccessGrantRepository = mock(WorkflowAccessGrantRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final WorkflowNodeRunRepository workflowNodeRunRepository = mock(WorkflowNodeRunRepository.class);
    private final WorkflowWaitingEventRepository workflowWaitingEventRepository = mock(WorkflowWaitingEventRepository.class);
    private final WorkflowRunEventRepository workflowRunEventRepository = mock(WorkflowRunEventRepository.class);
    private final WorkflowVariableSnapshotRepository workflowVariableSnapshotRepository = mock(WorkflowVariableSnapshotRepository.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final WorkflowRuntimeExecutor workflowRuntimeExecutor = mock(WorkflowRuntimeExecutor.class);
    private final WorkflowRunExecutionJobRepository jobRepository = mock(WorkflowRunExecutionJobRepository.class);
    private final WorkflowClusterAgentRunRepository clusterAgentRunRepository = mock(WorkflowClusterAgentRunRepository.class);
    private final NodeExecuteCommandPublisher commandPublisher = mock(NodeExecuteCommandPublisher.class);
    private final RunProgressStreamWriter streamWriter = mock(RunProgressStreamWriter.class);
    private final RunCancellationGuard cancellationGuard = mock(RunCancellationGuard.class);
    private final RunExecutionLeaseService leaseService = mock(RunExecutionLeaseService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Test
    void shouldRejectEmptyRequiredInputAndKeepLegacyFieldsRequired() {
        WorkflowRunEntity run = ownedRun(1);
        WorkflowNodeRunEntity node = inputNode(run, List.of(
            Map.of("id", "field_1", "label", "企业名称", "variable", "company_name", "required", true),
            Map.of("id", "field_2", "label", "补充说明", "variable", "remark")
        ));

        assertThatThrownBy(() -> WorkbenchRuntimeService.validateRequiredInputFields(
            node,
            Map.of("company_name", "云程科技", "remark", " ")
        ))
            .extracting("code")
            .isEqualTo("WORKBENCH_INPUT_REQUIRED");
    }

    @Test
    void shouldAllowOptionalInputFieldToBeEmpty() {
        WorkflowRunEntity run = ownedRun(1);
        WorkflowNodeRunEntity node = inputNode(run, List.of(
            Map.of("id", "field_1", "label", "企业名称", "variable", "company_name", "required", true),
            Map.of("id", "field_2", "label", "补充说明", "variable", "remark", "required", false)
        ));

        WorkbenchRuntimeService.validateRequiredInputFields(node, Map.of("company_name", "云程科技", "remark", ""));
    }

    @Test
    void shouldListAllPublishedWorkflowsAndMarkLockedRows() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity open = publishedDefinition("对全员开放流程", DESIGNER_ID, "all");
        WorkflowDefinitionEntity locked = publishedDefinition("未开放流程", DESIGNER_ID, "self");
        WorkflowVersionEntity openVersion = version(open, 2, snapshotJson());
        WorkflowVersionEntity lockedVersion = version(locked, 1, snapshotJson());
        UserAccount designer = mock(UserAccount.class);
        when(designer.getId()).thenReturn(DESIGNER_ID);
        when(designer.getDisplayName()).thenReturn("流程设计者");

        stubTenant();
        when(workflowDefinitionRepository.searchAllLaunchableWorkflows(eq(TENANT_ID), eq(""), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(open, locked)));
        when(workflowVersionRepository.findLatestByWorkflowIds(any())).thenReturn(List.of(openVersion, lockedVersion));
        when(workflowAccessGrantRepository.findByWorkflowIdIn(any())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(designer));

        var page = service.listLaunchableWorkflows(TENANT_ID, businessPrincipal(), "", 1, 10, "updatedAt,desc");

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).canLaunch()).isTrue();
        assertThat(page.items().get(0).visibility()).isEqualTo("open");
        assertThat(page.items().get(1).canLaunch()).isFalse();
        assertThat(page.items().get(1).visibility()).isEqualTo("locked");
        assertThat(page.items().get(1).launchBlockedReason()).isEqualTo("当前账号没有该流程的读取或发起权限");
    }

    @Test
    void shouldPreviewPublishedWorkflowNodesWithoutTrigger() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity open = publishedDefinition("授信报告流程", DESIGNER_ID, "all");
        WorkflowVersionEntity openVersion = version(open, 3, snapshotJson());

        stubTenant();
        when(workflowDefinitionRepository.findByIdAndTenantId(open.getId(), TENANT_ID)).thenReturn(Optional.of(open));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(open.getId())).thenReturn(Optional.of(openVersion));

        WorkbenchApi.AvailableWorkflowPreview preview = service.getAvailableWorkflowPreview(
            TENANT_ID,
            businessPrincipal(),
            open.getId()
        );

        assertThat(preview.versionNumber()).isEqualTo(3);
        assertThat(preview.nodes()).extracting(WorkbenchApi.AvailableWorkflowNodeRow::nodeType)
            .containsExactly("user_input", "agent", "delivery");
        assertThat(preview.nodes()).extracting(WorkbenchApi.AvailableWorkflowNodeRow::name)
            .containsExactly("补充授信资料", "智能体分析", "交付报告");
    }

    @Test
    void shouldRejectLockedWorkflowWhenCreatingRun() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity locked = publishedDefinition("未开放流程", DESIGNER_ID, "self");
        stubTenant();
        when(workflowDefinitionRepository.findByIdAndTenantId(locked.getId(), TENANT_ID)).thenReturn(Optional.of(locked));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(locked.getId()))
            .thenReturn(Optional.of(version(locked, 1, snapshotJson())));
        when(workflowAccessGrantRepository.findByWorkflowId(locked.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.createRun(
            TENANT_ID,
            businessPrincipal(),
            new WorkbenchApi.CreateRunRequest(locked.getId(), "尝试发起未开放流程")
        ))
            .extracting("code")
            .isEqualTo("WORKBENCH_WORKFLOW_LAUNCH_FORBIDDEN");
    }

    @Test
    void shouldCreateRunWithNodeChainAndOpenTodo() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity open = publishedDefinition("授信报告流程", DESIGNER_ID, "all");
        WorkflowVersionEntity version = version(open, 3, snapshotJson());
        UserAccount operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(OPERATOR_ID);
        when(operator.getDisplayName()).thenReturn("业务用户");

        stubTenant();
        when(workflowDefinitionRepository.findByIdAndTenantId(open.getId(), TENANT_ID)).thenReturn(Optional.of(open));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(open.getId())).thenReturn(Optional.of(version));
        when(workflowAccessGrantRepository.findByWorkflowId(open.getId())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(operator));
        when(workflowRuntimeExecutor.execute(any()))
            .thenReturn(new WorkflowRuntimeExecutor.ExecutionResult(Map.of("summary", "手动触发节点已完成")));

        WorkbenchApi.RunDetail detail = service.createRun(
            TENANT_ID,
            businessPrincipal(),
            new WorkbenchApi.CreateRunRequest(open.getId(), "云程科技年度授信复核")
        );

        assertThat(detail.title()).isEqualTo("云程科技年度授信复核");
        assertThat(detail.state()).isEqualTo("paused");
        assertThat(detail.currentNodeName()).isEqualTo("补充授信资料");
        assertThat(detail.nodes()).extracting(WorkbenchApi.NodeRunRow::nodeType)
            .containsExactly("trigger", "user_input", "agent", "delivery");
        assertThat(detail.nodes()).extracting(WorkbenchApi.NodeRunRow::state)
            .containsExactly("completed", "waiting", "pending", "pending");
        assertThat(detail.openTodo()).isNotNull();
        assertThat(detail.openTodo().action()).isEqualTo("提交输入");
        verify(workflowRunRepository, atLeastOnce()).save(any(WorkflowRunEntity.class));
        verify(workflowNodeRunRepository).saveAll(any());
        verify(workflowWaitingEventRepository).save(any(WorkflowWaitingEventEntity.class));
        verify(workflowRunEventRepository, atLeastOnce()).save(any());
    }

    @Test
    void shouldPauseAtAgentNodeForManualSseAdvance() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity open = publishedDefinition("自动执行流程", DESIGNER_ID, "all");
        WorkflowVersionEntity version = version(open, 4, autoSnapshotJson());
        UserAccount operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(OPERATOR_ID);
        when(operator.getDisplayName()).thenReturn("业务用户");

        stubTenant();
        when(workflowDefinitionRepository.findByIdAndTenantId(open.getId(), TENANT_ID)).thenReturn(Optional.of(open));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(open.getId())).thenReturn(Optional.of(version));
        when(workflowAccessGrantRepository.findByWorkflowId(open.getId())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(operator));
        when(workflowRuntimeExecutor.execute(any()))
            .thenReturn(new WorkflowRuntimeExecutor.ExecutionResult(Map.of("summary", "手动触发节点已完成")));

        WorkbenchApi.RunDetail detail = service.createRun(
            TENANT_ID,
            businessPrincipal(),
            new WorkbenchApi.CreateRunRequest(open.getId(), "自动执行任务")
        );

        assertThat(detail.state()).isEqualTo("paused");
        assertThat(detail.currentNodeName()).isEqualTo("智能体分析");
        assertThat(detail.openTodo()).isNull();
        assertThat(detail.nodes()).extracting(WorkbenchApi.NodeRunRow::state)
            .containsExactly("completed", "pending", "pending");
        verify(workflowRuntimeExecutor, times(1)).execute(any());
    }

    @Test
    void shouldPauseAtAgentNodeWhenCreateRunWithoutAutoExecution() {
        WorkbenchRuntimeService service = newService();
        WorkflowDefinitionEntity open = publishedDefinition("失败留痕流程", DESIGNER_ID, "all");
        WorkflowVersionEntity version = version(open, 5, autoSnapshotJson());
        UserAccount operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(OPERATOR_ID);
        when(operator.getDisplayName()).thenReturn("业务用户");

        stubTenant();
        when(workflowDefinitionRepository.findByIdAndTenantId(open.getId(), TENANT_ID)).thenReturn(Optional.of(open));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(open.getId())).thenReturn(Optional.of(version));
        when(workflowAccessGrantRepository.findByWorkflowId(open.getId())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of(operator));
        when(workflowRuntimeExecutor.execute(any()))
            .thenReturn(new WorkflowRuntimeExecutor.ExecutionResult(Map.of("summary", "手动触发节点已完成")));

        WorkbenchApi.RunDetail detail = service.createRun(
            TENANT_ID,
            businessPrincipal(),
            new WorkbenchApi.CreateRunRequest(open.getId(), "失败留痕任务")
        );

        assertThat(detail.state()).isEqualTo("paused");
        assertThat(detail.currentNodeName()).isEqualTo("智能体分析");
        assertThat(detail.nodes()).extracting(WorkbenchApi.NodeRunRow::state)
            .containsExactly("completed", "pending", "pending");
        verify(workflowRuntimeExecutor, times(1)).execute(any());
    }

    @Test
    void shouldInterruptRunningNodeClearDataAndCancelInFlightJobs() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.start(NOW);
        WorkflowRunExecutionJobEntity job = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, run.getId(), node.getId(), 1, OPERATOR_ID, "req-1", NOW.plusSeconds(1800), NOW
        );
        job.markRunning("worker-a", NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any()))
            .thenReturn(List.of(job))
            .thenReturn(List.of());
        when(leaseService.hasActiveLease(run.getId())).thenReturn(true);

        service.interruptRun(TENANT_ID, businessPrincipal(), run.getId());

        // 中断语义：作业终态化、取消信号写入、节点 canceled 且数据清空，SSE 收尾。
        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_CANCELED);
        assertThat(node.getState()).isEqualTo("canceled");
        assertThat(node.getOutputSnapshot()).isEmpty();
        assertThat(run.getState()).isEqualTo("paused");
        verify(cancellationGuard).requestCancel(run.getId());
        verify(leaseService).forceRelease(run.getId());
        verify(workflowVariableSnapshotRepository).deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(node.getId()));
        verify(clusterAgentRunRepository).deleteByNodeRunId(node.getId());
        verify(streamWriter).append(eq(run.getId()), eq("run_paused"), any());
        verify(streamWriter).append(run.getId(), "message", "[DONE]");
    }

    @Test
    void shouldRegenerateCompletedAgentNodeFromScratch() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        run.markRunning("agent_review", "智能体分析", "agent", 0, NOW);
        Map<String, Object> agentConfig = new LinkedHashMap<>(Map.of(
            "userPrompt", "首轮问题",
            "conversationHistory", List.of(
                Map.of("role", "user", "content", "首轮问题"),
                Map.of("role", "assistant", "content", "首轮回答"),
                Map.of("role", "user", "content", "追问")
            )
        ));
        WorkflowNodeRunEntity agentNode = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of(
                "final_answer", "追问后的答案",
                "chatMessages", List.of(
                    Map.of("role", "user", "content", "首轮问题"),
                    Map.of("role", "assistant", "content", "首轮回答"),
                    Map.of("role", "user", "content", "追问"),
                    Map.of("role", "assistant", "content", "追问后的答案")
                )
            ),
            agentConfig,
            1,
            NOW
        );
        agentNode.complete(agentNode.getOutputSnapshot(), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(agentNode.getId(), run.getId())).thenReturn(Optional.of(agentNode));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(agentNode));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(agentNode.getId())).thenReturn(Optional.empty());

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), agentNode.getId());

        assertThat(agentNode.getConfigSnapshot()).doesNotContainKey("conversationHistory");
        assertThat(agentNode.getOutputSnapshot()).isEmpty();
        assertThat(agentNode.getState()).isEqualTo("running");
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldClearConversationHistoryWhenRestartingAgentNode() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        Map<String, Object> agentConfig = new LinkedHashMap<>(Map.of(
            "allowQuestion", true,
            "conversationHistory", List.of(
                Map.of("role", "user", "content", "第一轮"),
                Map.of("role", "assistant", "content", "第一轮回答"),
                Map.of("role", "user", "content", "追问内容")
            )
        ));
        WorkflowNodeRunEntity agentNode = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of("final_answer", "追问后的答案"),
            agentConfig,
            1,
            NOW
        );
        agentNode.cancel(NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(agentNode.getId(), run.getId())).thenReturn(Optional.of(agentNode));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(agentNode));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(agentNode.getId())).thenReturn(Optional.empty());

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), agentNode.getId());

        assertThat(agentNode.getConfigSnapshot()).doesNotContainKey("conversationHistory");
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldRestartCanceledNodeWithFullCleanupAndEnqueueNextAttempt() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.cancel(NOW);
        WorkflowRunExecutionJobEntity previousJob = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, run.getId(), node.getId(), 2, OPERATOR_ID, "req-1", NOW.plusSeconds(1800), NOW
        );
        previousJob.markCanceled(NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.of(previousJob));

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        // 重新执行语义：清空全部子智能体结果（含已成功），重置 Stream，attempt 递增后入队。
        assertThat(node.getState()).isEqualTo("running");
        assertThat(run.getState()).isEqualTo("running");
        verify(cancellationGuard).clearCancel(run.getId());
        verify(clusterAgentRunRepository).deleteByNodeRunId(node.getId());
        verify(streamWriter).reset(run.getId());
        ArgumentCaptor<NodeExecuteCommand> commandCaptor = ArgumentCaptor.forClass(NodeExecuteCommand.class);
        verify(commandPublisher).publish(commandCaptor.capture());
        assertThat(commandCaptor.getValue().attempt()).isEqualTo(3);
        assertThat(commandCaptor.getValue().nodeRunId()).isEqualTo(node.getId());
    }

    @Test
    void shouldClearClusterAgentConversationHistoryWhenRestartingCanceledClusterNode() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.replaceConfigSnapshot(clusterConfigWithFollowUpHistory(), NOW);
        node.cancel(NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.empty());

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) node.getConfigSnapshot().get("clusterAgents");
        assertThat(agents).hasSize(2);
        assertThat(agents).allSatisfy(agent -> assertThat(agent).doesNotContainKey("conversationHistory"));
        assertThat(node.getOutputSnapshot()).isEmpty();
        assertThat(node.getState()).isEqualTo("running");
        verify(clusterAgentRunRepository).deleteByNodeRunId(node.getId());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldRegenerateCompletedClusterNodeFromScratch() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.replaceConfigSnapshot(clusterConfigWithFollowUpHistory(), NOW);
        node.complete(Map.of(
            "clusterAgents", List.of(Map.of("name", "子智能体 1", "final_answer", "旧答案")),
            "final_answer", "旧集群答案"
        ), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.empty());

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) node.getConfigSnapshot().get("clusterAgents");
        assertThat(agents).allSatisfy(agent -> assertThat(agent).doesNotContainKey("conversationHistory"));
        assertThat(node.getState()).isEqualTo("running");
        assertThat(node.getOutputSnapshot()).isEmpty();
        verify(clusterAgentRunRepository).deleteByNodeRunId(node.getId());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldRollbackToClusterNodeFromFreshStateWithoutFollowUpHistory() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(4);
        run.markSaved(NOW);
        run.pauseAt("delivery_report", "交付结果", "delivery", 3, NOW);

        WorkflowNodeRunEntity triggerNode = nodeAt(run, "trigger_start", "trigger", "手动触发", 0, NOW);
        triggerNode.complete(Map.of("summary", "已触发"), NOW);
        WorkflowNodeRunEntity inputNode = nodeAt(run, "input_company", "user_input", "补充资料", 1, NOW);
        inputNode.complete(Map.of("company", "云程科技"), NOW);
        WorkflowNodeRunEntity clusterNode = clusterNode(run);
        clusterNode.replaceConfigSnapshot(clusterConfigWithFollowUpHistory(), NOW);
        clusterNode.complete(Map.of(
            "clusterAgents", List.of(Map.of("name", "子智能体 1", "final_answer", "旧答案")),
            "final_answer", "旧集群答案"
        ), NOW);
        WorkflowNodeRunEntity deliveryNode = nodeAt(run, "delivery_report", "delivery", "交付结果", 3, NOW);
        deliveryNode.complete(Map.of("summary", "旧交付"), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(triggerNode, inputNode, clusterNode, deliveryNode));
        when(workflowRunEventRepository.findByRunIdOrderByEventTimeAsc(run.getId())).thenReturn(List.of());
        when(workflowWaitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(run.getId(), "open"))
            .thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());

        service.rollbackRun(
            TENANT_ID,
            businessPrincipal(),
            run.getId(),
            new WorkbenchApi.RollbackRunRequest(clusterNode.getId())
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) clusterNode.getConfigSnapshot().get("clusterAgents");
        assertThat(agents).allSatisfy(agent -> assertThat(agent).doesNotContainKey("conversationHistory"));
        assertThat(clusterNode.getState()).isEqualTo("pending");
        assertThat(clusterNode.getOutputSnapshot()).isEmpty();
        assertThat(deliveryNode.getState()).isEqualTo("pending");
        assertThat(deliveryNode.getOutputSnapshot()).isEmpty();
        assertThat(run.getState()).isEqualTo("paused");
        assertThat(run.getCurrentNodeName()).isEqualTo("智能体集群分析");

        ArgumentCaptor<List<UUID>> resetNodeIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(clusterAgentRunRepository).deleteByRunIdAndNodeRunIdIn(eq(run.getId()), resetNodeIdsCaptor.capture());
        assertThat(resetNodeIdsCaptor.getValue()).containsExactly(clusterNode.getId(), deliveryNode.getId());
    }

    @Test
    void shouldRecoverWhenStaleQueuedJobBlocksExecution() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.start(NOW);

        WorkflowRunExecutionJobEntity staleJob = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID,
            run.getId(),
            node.getId(),
            15,
            OPERATOR_ID,
            "req-stale",
            NOW.plusSeconds(1800),
            NOW.minusSeconds(120)
        );

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any()))
            .thenReturn(List.of(staleJob))
            .thenReturn(List.of());
        when(leaseService.hasActiveLease(run.getId())).thenReturn(true);
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.of(staleJob));

        service.recoverNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        assertThat(staleJob.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_FAILED);
        // 僵死作业终止、abort 遗留租约与入队前孤儿租约清理均可能释放租约。
        verify(leaseService, org.mockito.Mockito.atLeast(2)).forceRelease(run.getId());
        verify(cancellationGuard, org.mockito.Mockito.atLeastOnce()).requestCancel(run.getId());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
        assertThat(node.getState()).isEqualTo("running");
    }

    @Test
    void shouldReleaseOrphanLeaseBeforeEnqueueOnRestart() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.fail(Map.of("errorCode", "WORKBENCH_NODE_EXECUTION_STALE"), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.empty());
        when(leaseService.hasActiveLease(run.getId())).thenReturn(true);

        service.restartNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        verify(leaseService, org.mockito.Mockito.atLeastOnce()).forceRelease(run.getId());
        verify(cancellationGuard).requestCancel(run.getId());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldRecoverFailedNodePreservingSucceededClusterAgents() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.fail(Map.of("errorCode", "CLUSTER_AGENT_FAILED"), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(node.getId())).thenReturn(Optional.empty());

        service.recoverNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId());

        // 恢复进度语义：只删除非 succeeded 子智能体行，已成功结果保留供 Worker 复用。
        verify(clusterAgentRunRepository)
            .deleteByNodeRunIdAndStatusNot(node.getId(), WorkflowClusterAgentRunEntity.STATUS_SUCCEEDED);
        verify(clusterAgentRunRepository, never()).deleteByNodeRunId(any());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
        assertThat(node.getState()).isEqualTo("running");
    }

    @Test
    void shouldRejectRecoverForCanceledNodeBecauseItMustRestart() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.cancel(NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(node.getId(), run.getId())).thenReturn(Optional.of(node));

        assertThatThrownBy(() -> service.recoverNode(TENANT_ID, businessPrincipal(), run.getId(), node.getId()))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKBENCH_NODE_RECOVER_INTERRUPTED");
        verify(commandPublisher, never()).publish(any());
    }

    @Test
    void shouldTreatCanceledClusterAdvanceAsFullRestartCleanup() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        node.replaceConfigSnapshot(clusterConfigWithFollowUpHistory(), NOW);
        node.cancel(NOW);

        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(node));

        WorkbenchRuntimeService.NextNodeResult result = service.prepareNextNode(TENANT_ID, run.getId(), OPERATOR_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) node.getConfigSnapshot().get("clusterAgents");
        assertThat(result.nodeRunId()).isEqualTo(node.getId());
        assertThat(node.getState()).isEqualTo("running");
        assertThat(agents).allSatisfy(agent -> assertThat(agent).doesNotContainKey("conversationHistory"));
        verify(clusterAgentRunRepository).deleteByNodeRunId(node.getId());
        verify(clusterAgentRunRepository, never()).deleteByNodeRunIdAndStatusNot(any(), any());
    }

    @Test
    void shouldUpdateFinalAnswerWithoutReExecutingAgent() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        run.markRunning("agent_review", "智能体分析", "agent", 0, NOW);
        Map<String, Object> agentConfig = new LinkedHashMap<>(Map.of("allowUserEdit", true));
        WorkflowNodeRunEntity agentNode = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of(),
            agentConfig,
            1,
            NOW
        );
        agentNode.complete(Map.of(
            "final_answer", "原始答案",
            "chatMessages", List.of(
                Map.of("role", "user", "content", "测试问题"),
                Map.of("role", "assistant", "content", "原始答案")
            )
        ), NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(agentNode.getId(), run.getId())).thenReturn(Optional.of(agentNode));

        service.updateFinalAnswer(TENANT_ID, businessPrincipal(), run.getId(), agentNode.getId(), "修改后的答案");

        assertThat(agentNode.getOutputSnapshot().get("final_answer")).isEqualTo("修改后的答案");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chatMessages = (List<Map<String, Object>>) agentNode.getOutputSnapshot().get("chatMessages");
        assertThat(chatMessages.get(1).get("content")).isEqualTo("修改后的答案");
        verify(workflowVariableSnapshotRepository).deleteByRunIdAndNodeRunIdIn(run.getId(), List.of(agentNode.getId()));
        verify(commandPublisher, never()).publish(any());
    }

    @Test
    void shouldAppendFollowUpMessageToConversationHistory() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        run.markRunning("agent_review", "智能体分析", "agent", 0, NOW);
        Map<String, Object> agentConfig = new LinkedHashMap<>(Map.of(
            "allowQuestion", true,
            "userPrompt", "Spring Boot 自动装配原理是什么？"
        ));
        WorkflowNodeRunEntity agentNode = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "agent_review",
            "agent",
            "智能体分析",
            Map.of(),
            Map.of(),
            agentConfig,
            1,
            NOW
        );
        agentNode.complete(Map.of(
            "final_answer", "Spring Boot 通过 @EnableAutoConfiguration...",
            "chatMessages", List.of(
                Map.of("role", "user", "content", "Spring Boot 自动装配原理是什么？"),
                Map.of("role", "assistant", "content", "Spring Boot 通过 @EnableAutoConfiguration...")
            )
        ), NOW);
        WorkflowNodeRunEntity deliveryNode = nodeAt(run, "delivery_report", "delivery", "交付结果", 2, NOW);

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByIdAndRunId(agentNode.getId(), run.getId())).thenReturn(Optional.of(agentNode));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(agentNode, deliveryNode));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of());
        when(jobRepository.findFirstByNodeRunIdOrderByAttemptDesc(agentNode.getId())).thenReturn(Optional.empty());

        service.followUpNode(
            TENANT_ID,
            businessPrincipal(),
            run.getId(),
            agentNode.getId(),
            "那它和 Spring IOC 有什么关系？"
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) agentNode.getConfigSnapshot().get("conversationHistory");
        assertThat(history).hasSize(3);
        assertThat(history)
            .extracting(message -> message.get("role"))
            .containsExactly("user", "assistant", "user");
        assertThat(history.get(2).get("content")).isEqualTo("那它和 Spring IOC 有什么关系？");
        assertThat(agentNode.getState()).isEqualTo("running");
        assertThat(agentNode.getOutputSnapshot()).isEmpty();
        verify(cancellationGuard).clearCancel(run.getId());
        verify(commandPublisher).publish(any(NodeExecuteCommand.class));
    }

    @Test
    void shouldPauseAtLastDeliveryNodeUntilUserConfirms() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        run.markRunning("agent_review", "智能体分析", "agent", 1, NOW);
        WorkflowNodeRunEntity agentNode = nodeAt(run, "agent_review", "agent", "智能体分析", 1, NOW);
        agentNode.complete(Map.of("summary", "分析完成"), NOW);
        WorkflowNodeRunEntity deliveryNode = nodeAt(run, "delivery_report", "delivery", "交付结果", 2, NOW);
        deliveryNode.start(NOW);

        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findById(deliveryNode.getId())).thenReturn(Optional.of(deliveryNode));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(agentNode, deliveryNode));

        boolean completed = service.saveNodeSuccess(
            run.getId(),
            deliveryNode.getId(),
            Map.of("summary", "交付完成", "deliveryPayload", Map.of("body", "月报正文")),
            OPERATOR_ID
        );

        assertThat(completed).isFalse();
        assertThat(run.getState()).isEqualTo("paused");
        assertThat(run.isSaved()).isFalse();
        assertThat(run.getProgressPercent()).isEqualTo(100);
        assertThat(deliveryNode.getState()).isEqualTo("completed");
    }

    @Test
    void shouldCompleteAndAutoSaveWhenUserConfirmsAfterAllNodesDone() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        WorkflowNodeRunEntity agentNode = nodeAt(run, "agent_review", "agent", "智能体分析", 1, NOW);
        agentNode.complete(Map.of("summary", "分析完成"), NOW);
        WorkflowNodeRunEntity deliveryNode = nodeAt(run, "delivery_report", "delivery", "交付结果", 2, NOW);
        deliveryNode.complete(Map.of("summary", "交付完成"), NOW);
        run.pauseAt("delivery_report", "交付结果", "delivery", 2, NOW);

        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(agentNode, deliveryNode));

        WorkbenchRuntimeService.NextNodeResult result = service.prepareNextNode(TENANT_ID, run.getId(), OPERATOR_ID);

        assertThat(result.hasNext()).isFalse();
        assertThat(run.getState()).isEqualTo("completed");
        assertThat(run.isSaved()).isTrue();
        verify(workflowRunEventRepository, atLeastOnce()).save(any());
    }

    @Test
    void shouldSkipSaveNodeSuccessWhenNodeNotActive() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        WorkflowNodeRunEntity agentNode = nodeAt(run, "agent_review", "agent", "智能体分析", 1, NOW);
        agentNode.cancel(NOW);

        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findById(agentNode.getId())).thenReturn(Optional.of(agentNode));

        boolean completed = service.saveNodeSuccess(
            run.getId(),
            agentNode.getId(),
            Map.of("summary", "不应落库"),
            OPERATOR_ID
        );

        assertThat(completed).isFalse();
        assertThat(agentNode.getState()).isEqualTo("canceled");
        assertThat(agentNode.getOutputSnapshot()).isEmpty();
        verify(workflowNodeRunRepository, never()).save(agentNode);
    }

    @Test
    void shouldPauseAtAgentNodeWhenMoreStepsRemain() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        WorkflowNodeRunEntity agentNode = nodeAt(run, "agent_review", "agent", "智能体分析", 1, NOW);
        agentNode.start(NOW);
        WorkflowNodeRunEntity deliveryNode = nodeAt(run, "delivery_report", "delivery", "交付结果", 2, NOW);

        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findById(agentNode.getId())).thenReturn(Optional.of(agentNode));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(agentNode, deliveryNode));

        boolean completed = service.saveNodeSuccess(
            run.getId(),
            agentNode.getId(),
            Map.of("summary", "分析完成"),
            OPERATOR_ID
        );

        assertThat(completed).isFalse();
        assertThat(run.getState()).isEqualTo("paused");
        assertThat(run.isSaved()).isFalse();
        assertThat(run.getCurrentNodeName()).isEqualTo("智能体分析");
    }

    @Test
    void shouldRejectAdvanceWhenExecutionAlreadyInFlight() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun();
        WorkflowNodeRunEntity node = clusterNode(run);
        WorkflowRunExecutionJobEntity inFlight = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, run.getId(), node.getId(), 1, OPERATOR_ID, "req-1", NOW.plusSeconds(1800), NOW
        );

        stubTenant();
        when(workflowRunRepository.findByIdAndTenantId(run.getId(), TENANT_ID)).thenReturn(Optional.of(run));
        when(jobRepository.findByRunIdAndStatusIn(eq(run.getId()), any())).thenReturn(List.of(inFlight));

        assertThatThrownBy(() -> service.advanceRun(TENANT_ID, businessPrincipal(), run.getId()))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("code", "WORKBENCH_ADVANCE_ALREADY_IN_FLIGHT");
        verify(commandPublisher, never()).publish(any());
    }

    @Test
    void shouldMaskCustomSensitiveVariablesWhenPersistingSnapshots() {
        WorkbenchRuntimeService service = newService();
        WorkflowRunEntity run = ownedRun(2);
        WorkflowNodeRunEntity agentNode = nodeAt(run, "agent_review", "agent", "智能体分析", 1, NOW);
        agentNode.start(NOW);

        String customSnapshot = """
            {
              "name": "自定义敏感变量流程",
              "variables": [
                {
                  "name": "custom_sensitive_data",
                  "type": "string",
                  "sensitive": true
                },
                {
                  "name": "normal_data",
                  "type": "string",
                  "sensitive": false
                }
              ]
            }
            """;
        WorkflowVersionEntity version = WorkflowVersionEntity.create(
            run.getWorkflowId(),
            TENANT_ID,
            1,
            customSnapshot,
            1,
            DESIGNER_ID,
            NOW
        );

        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findById(agentNode.getId())).thenReturn(Optional.of(agentNode));
        when(workflowVersionRepository.findById(run.getWorkflowVersionId())).thenReturn(Optional.of(version));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId()))
            .thenReturn(List.of(agentNode));

        Map<String, Object> outputs = Map.of(
            "custom_sensitive_data", "secret-value",
            "normal_data", "public-value",
            "password_field", "plaintext-pass"
        );

        service.saveNodeSuccess(run.getId(), agentNode.getId(), outputs, OPERATOR_ID);

        ArgumentCaptor<List<WorkflowVariableSnapshotEntity>> snapshotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(workflowVariableSnapshotRepository).saveAll(snapshotsCaptor.capture());

        List<WorkflowVariableSnapshotEntity> snapshots = snapshotsCaptor.getValue();
        assertThat(snapshots).hasSize(3);

        Map<String, Object> snapshotValues = new HashMap<>();
        snapshots.forEach(s -> snapshotValues.put(s.getVariableName(), s.getValueSnapshot().get("value")));

        assertThat(snapshotValues.get("custom_sensitive_data")).isEqualTo("***");
        assertThat(snapshotValues.get("password_field")).isEqualTo("***");
        assertThat(snapshotValues.get("normal_data")).isEqualTo("public-value");
    }

    private WorkflowRunEntity ownedRun() {
        return ownedRun(3);
    }

    private WorkflowRunEntity ownedRun(int totalNodeCount) {
        return WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "授信复核",
            "授信报告流程",
            OPERATOR_ID,
            totalNodeCount,
            "20260610-TEST",
            NOW
        );
    }

    private WorkflowNodeRunEntity inputNode(WorkflowRunEntity run, List<Map<String, Object>> inputFields) {
        return WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "input_company",
            "user_input",
            "补充资料",
            Map.of(),
            Map.of(),
            Map.of("inputFields", inputFields),
            0,
            NOW
        );
    }

    private WorkflowNodeRunEntity clusterNode(WorkflowRunEntity run) {
        return nodeAt(run, "cluster_analysis", "parallel_group", "智能体集群分析", 2, NOW);
    }

    private Map<String, Object> clusterConfigWithFollowUpHistory() {
        return new LinkedHashMap<>(Map.of(
            "executionMode", "parallel",
            "clusterAgents", List.of(
                new LinkedHashMap<>(Map.of(
                    "name", "子智能体 1",
                    "outputMode", "追问确认",
                    "conversationHistory", List.of(
                        Map.of("role", "user", "content", "首轮问题"),
                        Map.of("role", "assistant", "content", "首轮回答"),
                        Map.of("role", "user", "content", "追问内容")
                    )
                )),
                new LinkedHashMap<>(Map.of(
                    "name", "子智能体 2",
                    "outputMode", "追问确认",
                    "conversationHistory", List.of(
                        Map.of("role", "user", "content", "另一个首轮问题"),
                        Map.of("role", "assistant", "content", "另一个回答")
                    )
                ))
            )
        ));
    }

    private WorkflowNodeRunEntity nodeAt(
        WorkflowRunEntity run,
        String nodeKey,
        String nodeType,
        String name,
        int sortOrder,
        Instant now
    ) {
        return WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            nodeKey,
            nodeType,
            name,
            Map.of(),
            Map.of(),
            nodeType.equals("parallel_group") ? Map.of("executionMode", "parallel") : Map.of(),
            sortOrder,
            now
        );
    }

    private WorkbenchRuntimeService newService() {
        when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
            .thenReturn(new SimpleTransactionStatus());
        return new WorkbenchRuntimeService(
            tenantRepository,
            workflowDefinitionRepository,
            workflowVersionRepository,
            workflowAccessGrantRepository,
            workflowRunRepository,
            workflowNodeRunRepository,
            workflowWaitingEventRepository,
            workflowRunEventRepository,
            workflowVariableSnapshotRepository,
            userAccountRepository,
            new CollaborationAccessPolicy(),
            new ObjectMapper(),
            workflowRuntimeExecutor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            jobRepository,
            clusterAgentRunRepository,
            commandPublisher,
            streamWriter,
            cancellationGuard,
            new RuntimeExecutionProperties(),
            new PromptContentResolver(mock(SystemCapabilityRepository.class), mock(TenantAssetCapabilityRepository.class)),
            leaseService,
            transactionManager
        );
    }

    private void stubTenant() {
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active"))
            .thenReturn(Optional.of(TenantEntity.create("云程科技", "YUNCHENG", NOW)));
    }

    private static WorkflowDefinitionEntity publishedDefinition(String name, UUID createdBy, String readScope) {
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.create(TENANT_ID, name, "流程说明", createdBy, NOW);
        definition.updateAccess(readScope, "self", createdBy, NOW);
        definition.markPublished(createdBy, NOW);
        return definition;
    }

    private static WorkflowVersionEntity version(WorkflowDefinitionEntity definition, int versionNumber, String snapshot) {
        return WorkflowVersionEntity.create(
            definition.getId(),
            TENANT_ID,
            versionNumber,
            snapshot,
            3,
            DESIGNER_ID,
            NOW
        );
    }

    private static CurrentUserPrincipal businessPrincipal() {
        return new CurrentUserPrincipal(OPERATOR_ID, "operator", TENANT_ID, "business", "business", ROLE_ASSIGNMENT_ID);
    }

    private static String snapshotJson() {
        return """
            {
              "name": "授信报告流程",
              "description": "生成授信报告",
              "nodes": [
                {
                  "nodeId": "trigger_manual",
                  "nodeType": "trigger",
                  "name": "创建任务",
                  "positionX": 0,
                  "positionY": 0,
                  "inputVariables": [],
                  "outputVariables": ["starter"],
                  "config": {"summary": "手动发起"}
                },
                {
                  "nodeId": "input_company",
                  "nodeType": "user_input",
                  "name": "补充授信资料",
                  "positionX": 0,
                  "positionY": 120,
                  "inputVariables": ["starter"],
                  "outputVariables": ["company_profile"],
                  "config": {"placeholder": "请输入授信主体、用途和材料清单"}
                },
                {
                  "nodeId": "agent_review",
                  "nodeType": "agent",
                  "name": "智能体分析",
                  "positionX": 0,
                  "positionY": 240,
                  "inputVariables": ["company_profile"],
                  "outputVariables": ["risk_summary"],
                  "config": {"summary": "生成风险摘要", "allowQuestion": true}
                },
                {
                  "nodeId": "delivery_report",
                  "nodeType": "delivery",
                  "name": "交付报告",
                  "positionX": 0,
                  "positionY": 360,
                  "inputVariables": ["risk_summary"],
                  "outputVariables": ["report_file"],
                  "config": {
                    "deliveryMode": "capability",
                    "deliveryCapabilityId": "00000000-0000-0000-0000-00000000d001",
                    "deliveryType": "word_document",
                    "documentKind": "word",
                    "markdownContent": "# 交付报告\\n\\n{{risk_summary}}"
                  }
                }
              ],
              "edges": [],
              "variables": []
            }
            """;
    }

    private static String autoSnapshotJson() {
        return """
            {
              "name": "自动执行流程",
              "description": "AI 分析后自动交付",
              "nodes": [
                {
                  "nodeId": "trigger_manual",
                  "nodeType": "trigger",
                  "name": "创建任务",
                  "inputVariables": [],
                  "outputVariables": ["starter"],
                  "config": {"summary": "手动发起"}
                },
                {
                  "nodeId": "agent_review",
                  "nodeType": "agent",
                  "name": "智能体分析",
                  "inputVariables": ["starter"],
                  "outputVariables": ["risk_summary"],
                  "config": {"summary": "生成风险摘要", "systemPrompt": "你是授信风险分析智能体"}
                },
                {
                  "nodeId": "delivery_report",
                  "nodeType": "delivery",
                  "name": "交付报告",
                  "inputVariables": ["risk_summary"],
                  "outputVariables": ["delivery_record"],
                  "config": {
                    "deliveryMode": "capability",
                    "deliveryCapabilityId": "00000000-0000-0000-0000-00000000d001",
                    "deliveryType": "word_document",
                    "documentKind": "word",
                    "markdownContent": "# 交付报告\\n\\n{{risk_summary}}"
                  }
                }
              ],
              "edges": [],
              "variables": []
            }
            """;
    }
}
