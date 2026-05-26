package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowEdgeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVariableDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowVersionRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowDraftServicePublishTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock
    private WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository;
    @Mock
    private WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository;
    @Mock
    private WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository;
    @Mock
    private WorkflowVersionRepository workflowVersionRepository;
    @Mock
    private WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator;
    @Mock
    private WorkflowPublishValidator workflowPublishValidator;
    @Mock
    private WorkflowNodeConfigValidator workflowNodeConfigValidator;

    @Test
    void shouldCreateImmutableVersionAndMarkDraftPublished() {
        WorkflowDefinitionEntity definition = draft();
        stubDefinitionLookup(definition);
        when(workflowPublishValidator.validate(List.of(), List.of()))
            .thenReturn(new WorkflowDraftApi.WorkflowPublishValidationResult(true, 0, 0, List.of()));
        when(workflowVersionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(definition.getId())).thenReturn(Optional.empty());

        WorkflowDraftService service = service();
        WorkflowDraftApi.WorkflowPublishResult result = service.publish(TENANT_ID, USER_ID, definition.getId());

        ArgumentCaptor<WorkflowVersionEntity> versionCaptor = ArgumentCaptor.forClass(WorkflowVersionEntity.class);
        verify(workflowVersionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersionNumber()).isEqualTo(1);
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.publishedAt()).isEqualTo(NOW);
        assertThat(result.draft().status()).isEqualTo("published");
    }

    @Test
    void shouldRejectPublishWhenValidationStillFails() {
        WorkflowDefinitionEntity definition = draft();
        stubDefinitionLookup(definition);
        when(workflowPublishValidator.validate(List.of(), List.of()))
            .thenReturn(new WorkflowDraftApi.WorkflowPublishValidationResult(
                false,
                0,
                0,
                List.of(new WorkflowDraftApi.WorkflowValidationIssue("WORKFLOW_VALIDATION_GRAPH_EMPTY", "error", "工作流至少需要一个节点", "", ""))
            ));

        assertThatThrownBy(() -> service().publish(TENANT_ID, USER_ID, definition.getId()))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKFLOW_PUBLISH_VALIDATION_FAILED");
        verify(workflowVersionRepository, never()).save(any());
    }

    @Test
    void shouldKeepPausePointCountZeroWhenSavingSequentialGraph() {
        WorkflowDefinitionEntity definition = draft();
        stubDefinitionLookup(definition);
        WorkflowDraftApi.SaveWorkflowDraftGraphRequest request = new WorkflowDraftApi.SaveWorkflowDraftGraphRequest(
            List.of(
                node("trigger_manual", "trigger", List.of("starter")),
                node("input_1", "user_input", List.of("input_1")),
                node("agent_1", "agent", List.of("agent_response"))
            ),
            List.of(
                edge("e_trigger_input", "trigger_manual", "input_1"),
                edge("e_input_agent", "input_1", "agent_1")
            ),
            List.of(
                variable("starter", "string", "trigger_manual"),
                variable("input_1", "string", "input_1"),
                variable("agent_response", "object", "agent_1")
            )
        );

        WorkflowDraftApi.WorkflowDraftDetail detail = service().saveGraph(TENANT_ID, USER_ID, definition.getId(), request);

        assertThat(definition.getPausePointCount()).isZero();
        assertThat(detail.draft().pausePointCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistNodeJsonColumnsAsStructuredValues() {
        WorkflowDefinitionEntity definition = draft();
        stubDefinitionLookup(definition);
        WorkflowDraftApi.SaveWorkflowDraftGraphRequest request = new WorkflowDraftApi.SaveWorkflowDraftGraphRequest(
            List.of(new WorkflowDraftApi.WorkflowNodeDraft(
                "agent_1",
                "agent",
                "风险识别",
                0,
                0,
                List.of("company_name"),
                List.of("risk_report"),
                Map.of("summary", "识别授信风险", "toolCount", 2)
            )),
            List.of(),
            List.of(variable("risk_report", "object", "agent_1"))
        );

        service().saveGraph(TENANT_ID, USER_ID, definition.getId(), request);

        ArgumentCaptor<Iterable<WorkflowNodeDefinitionEntity>> nodeCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(workflowNodeDefinitionRepository).saveAll(nodeCaptor.capture());
        List<WorkflowNodeDefinitionEntity> savedNodes = new ArrayList<>();
        nodeCaptor.getValue().forEach(savedNodes::add);
        assertThat(savedNodes).hasSize(1);
        assertThat(savedNodes.get(0).getInputVariables()).containsExactly("company_name");
        assertThat(savedNodes.get(0).getOutputVariables()).containsExactly("risk_report");
        assertThat(savedNodes.get(0).getConfig()).containsEntry("summary", "识别授信风险").containsEntry("toolCount", 2);
    }

    private void stubDefinitionLookup(WorkflowDefinitionEntity definition) {
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
    }

    private WorkflowDraftService service() {
        return new WorkflowDraftService(
            tenantRepository,
            userAccountRepository,
            workflowDefinitionRepository,
            workflowNodeDefinitionRepository,
            workflowEdgeDefinitionRepository,
            workflowVariableDefinitionRepository,
            workflowVersionRepository,
            workflowVariableDeclarationValidator,
            workflowPublishValidator,
            workflowNodeConfigValidator,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static WorkflowDefinitionEntity draft() {
        return WorkflowDefinitionEntity.create(TENANT_ID, "需求评审", "用于验证正式发布", USER_ID, NOW);
    }

    private static WorkflowDraftApi.WorkflowNodeDraft node(String nodeId, String nodeType, List<String> outputVariables) {
        return new WorkflowDraftApi.WorkflowNodeDraft(nodeId, nodeType, nodeId, 0, 0, List.of(), outputVariables, Map.of());
    }

    private static WorkflowDraftApi.WorkflowEdgeDraft edge(String edgeId, String sourceNodeId, String targetNodeId) {
        return new WorkflowDraftApi.WorkflowEdgeDraft(edgeId, sourceNodeId, targetNodeId, "", "");
    }

    private static WorkflowDraftApi.WorkflowVariableDraft variable(String name, String type, String sourceNode) {
        return new WorkflowDraftApi.WorkflowVariableDraft(name, type, sourceNode, "", Map.of(), false, false);
    }
}
