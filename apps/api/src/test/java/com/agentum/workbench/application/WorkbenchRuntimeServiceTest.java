package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workbench.interfaces.WorkbenchApi;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
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
import com.agentum.delivery.application.DeliveryRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

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
    private final AgentRuntimeService agentRuntimeService = mock(AgentRuntimeService.class);
    private final DeliveryRuntimeService deliveryRuntimeService = mock(DeliveryRuntimeService.class);

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

    private WorkbenchRuntimeService newService() {
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
            agentRuntimeService,
            deliveryRuntimeService
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
            0,
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
                  "config": {"deliveryMode": "docx"}
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
                  "config": {"deliveryMode": "direct"}
                }
              ],
              "edges": [],
              "variables": []
            }
            """;
    }
}
