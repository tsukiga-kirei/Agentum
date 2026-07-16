package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.audit.application.AuditService;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.permission.application.CollaborationAccessPolicy;
import com.agentum.shared.api.ApiException;
import com.agentum.tenant.domain.TenantEntity;
import com.agentum.tenant.infrastructure.TenantRepository;
import com.agentum.workflow.domain.WorkflowDefinitionEntity;
import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import com.agentum.workflow.domain.WorkflowEdgeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowNodeDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVariableDefinitionEntity;
import com.agentum.workflow.domain.WorkflowVersionEntity;
import com.agentum.schedule.infrastructure.WorkflowScheduleRepository;
import com.agentum.workflow.infrastructure.WorkflowDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowAccessGrantRepository;
import com.agentum.workflow.infrastructure.WorkflowEdgeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeDefinitionRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
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
    private static final UUID COLLABORATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock
    private WorkflowAccessGrantRepository workflowAccessGrantRepository;
    @Mock
    private WorkflowNodeDefinitionRepository workflowNodeDefinitionRepository;
    @Mock
    private WorkflowEdgeDefinitionRepository workflowEdgeDefinitionRepository;
    @Mock
    private WorkflowVariableDefinitionRepository workflowVariableDefinitionRepository;
    @Mock
    private WorkflowVersionRepository workflowVersionRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private WorkflowScheduleRepository workflowScheduleRepository;
    @Mock
    private WorkflowVariableDeclarationValidator workflowVariableDeclarationValidator;
    @Mock
    private WorkflowPublishValidator workflowPublishValidator;
    @Mock
    private WorkflowNodeConfigValidator workflowNodeConfigValidator;
    @Mock
    private UserMembershipRepository userMembershipRepository;
    @Mock
    private AuditService auditService;

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
        assertThat(result.draft().latestVersionNumber()).isEqualTo(1);
        assertThat(result.draft().launchEnabled()).isTrue();
        assertThat(result.draft().hasUnpublishedChanges()).isFalse();
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
    void shouldSaveSequentialGraphWithCorrectNodeCount() {
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

        assertThat(definition.getNodeCount()).isEqualTo(2);
        assertThat(detail.draft().nodeCount()).isEqualTo(2);
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

    @Test
    void shouldAllowSpecifiedEditorToSaveWorkflowGraph() {
        WorkflowDefinitionEntity definition = draft();
        definition.updateAccess("self", "specified", USER_ID, NOW);
        WorkflowAccessGrantEntity editGrant = WorkflowAccessGrantEntity.create(
            TENANT_ID, definition.getId(), COLLABORATOR_ID, "edit", USER_ID, NOW
        );
        stubDefinitionLookup(definition);
        when(workflowAccessGrantRepository.findByWorkflowId(definition.getId())).thenReturn(List.of(editGrant));

        WorkflowDraftApi.WorkflowDraftDetail detail = service().saveGraph(
            TENANT_ID,
            COLLABORATOR_ID,
            definition.getId(),
            new WorkflowDraftApi.SaveWorkflowDraftGraphRequest(List.of(), List.of(), List.of())
        );

        assertThat(detail.access().accessLevel()).isEqualTo("edit");
        assertThat(detail.access().canManageAccess()).isFalse();
    }

    @Test
    void shouldCopyReadableWorkflowAsNewDraftOwnedByOperator() {
        WorkflowDefinitionEntity source = draft();
        UUID sourceWorkflowId = source.getId();
        WorkflowNodeDefinitionEntity sourceNode = WorkflowNodeDefinitionEntity.create(
            sourceWorkflowId,
            "agent_1",
            "agent",
            "风险识别",
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            List.of("input_1"),
            List.of("agent_response"),
            Map.of("brickType", "agent"),
            0,
            NOW
        );
        WorkflowEdgeDefinitionEntity sourceEdge = WorkflowEdgeDefinitionEntity.create(
            sourceWorkflowId,
            "e_agent_delivery",
            "agent_1",
            "delivery_1",
            "",
            "",
            0,
            NOW
        );
        WorkflowVariableDefinitionEntity sourceVariable = WorkflowVariableDefinitionEntity.create(
            sourceWorkflowId,
            "agent_response",
            "object",
            "agent_1",
            "智能体输出",
            "{}",
            false,
            true,
            0,
            NOW
        );
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(sourceWorkflowId, TENANT_ID)).thenReturn(Optional.of(source));
        when(workflowAccessGrantRepository.findByWorkflowId(any())).thenReturn(List.of());
        when(workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(sourceWorkflowId)).thenReturn(List.of(sourceNode));
        when(workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(sourceWorkflowId)).thenReturn(List.of(sourceEdge));
        when(workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(sourceWorkflowId)).thenReturn(List.of(sourceVariable));
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());

        WorkflowDraftApi.WorkflowDraftRow copied = service().copyDraft(TENANT_ID, USER_ID, sourceWorkflowId);

        assertThat(copied.id()).isNotEqualTo(sourceWorkflowId);
        assertThat(copied.name()).isEqualTo("需求评审（副本）");
        assertThat(copied.status()).isEqualTo("draft");
        assertThat(copied.nodeCount()).isEqualTo(1);
        assertThat(copied.ownerId()).isEqualTo(USER_ID);
        ArgumentCaptor<WorkflowNodeDefinitionEntity> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeDefinitionEntity.class);
        verify(workflowNodeDefinitionRepository).save(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getNodeKey()).isEqualTo("agent_1");
        assertThat(nodeCaptor.getValue().getOutputVariables()).containsExactly("agent_response");
        verify(workflowEdgeDefinitionRepository).save(any(WorkflowEdgeDefinitionEntity.class));
        verify(workflowVariableDefinitionRepository).save(any(WorkflowVariableDefinitionEntity.class));
    }

    @Test
    void shouldExportReadableWorkflowAsPortableJsonDocument() {
        WorkflowDefinitionEntity definition = draft();
        WorkflowNodeDefinitionEntity node = WorkflowNodeDefinitionEntity.create(
            definition.getId(),
            "agent_1",
            "agent",
            "风险识别",
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            List.of("company_name"),
            List.of("risk_report"),
            Map.of("brickType", "agent"),
            0,
            NOW
        );
        WorkflowVariableDefinitionEntity variable = WorkflowVariableDefinitionEntity.create(
            definition.getId(),
            "risk_report",
            "object",
            "agent_1",
            "智能体输出",
            "{}",
            false,
            true,
            0,
            NOW
        );
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowAccessGrantRepository.findByWorkflowId(definition.getId())).thenReturn(List.of());
        when(workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of(node));
        when(workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of(variable));
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());

        WorkflowDraftApi.WorkflowExportDocument document = service().exportDraft(TENANT_ID, USER_ID, definition.getId());

        assertThat(document.schemaVersion()).isEqualTo("agentum.workflow.export.v1");
        assertThat(document.sourceWorkflowId()).isEqualTo(definition.getId());
        assertThat(document.name()).isEqualTo("需求评审");
        assertThat(document.nodes()).hasSize(1);
        assertThat(document.nodes().get(0).outputVariables()).containsExactly("risk_report");
        assertThat(document.variables()).extracting(WorkflowDraftApi.WorkflowVariableDraft::name).containsExactly("risk_report");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldImportWorkflowJsonAsNewDraftOwnedByOperator() {
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowAccessGrantRepository.findByWorkflowId(any())).thenReturn(List.of());
        when(workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(userAccountRepository.findAllById(any())).thenReturn(List.of());
        WorkflowDraftApi.WorkflowExportDocument document = new WorkflowDraftApi.WorkflowExportDocument(
            "agentum.workflow.export.v1",
            NOW,
            "共享流程",
            "来自其他设计者的流程",
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            List.of(node("trigger_manual", "trigger", List.of("starter")), node("input_1", "user_input", List.of("company_name"))),
            List.of(edge("e_trigger_input", "trigger_manual", "input_1")),
            List.of(variable("starter", "string", "trigger_manual"), variable("company_name", "string", "input_1"))
        );

        WorkflowDraftApi.WorkflowDraftDetail detail = service().importDraft(
            TENANT_ID,
            USER_ID,
            new WorkflowDraftApi.ImportWorkflowDraftRequest(document, null, null)
        );

        ArgumentCaptor<WorkflowDefinitionEntity> definitionCaptor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(workflowDefinitionRepository, org.mockito.Mockito.atLeastOnce()).save(definitionCaptor.capture());
        WorkflowDefinitionEntity imported = definitionCaptor.getAllValues().get(0);
        assertThat(imported.getName()).isEqualTo("共享流程（导入）");
        assertThat(imported.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(detail.draft().ownerId()).isEqualTo(USER_ID);
        assertThat(detail.draft().latestVersionNumber()).isZero();

        ArgumentCaptor<Iterable<WorkflowNodeDefinitionEntity>> nodeCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(workflowNodeDefinitionRepository).saveAll(nodeCaptor.capture());
        List<WorkflowNodeDefinitionEntity> savedNodes = new ArrayList<>();
        nodeCaptor.getValue().forEach(savedNodes::add);
        assertThat(savedNodes).extracting(WorkflowNodeDefinitionEntity::getNodeKey).containsExactly("trigger_manual", "input_1");
    }

    @Test
    void shouldReplaceWorkflowAccessGrantsAfterFlushWhenUpdatingAccess() {
        WorkflowDefinitionEntity definition = draft();
        WorkflowAccessGrantEntity existingReadGrant = WorkflowAccessGrantEntity.create(
            TENANT_ID, definition.getId(), COLLABORATOR_ID, "read", USER_ID, NOW
        );
        stubDefinitionLookup(definition);
        when(workflowAccessGrantRepository.findByWorkflowId(definition.getId())).thenReturn(List.of(existingReadGrant));
        when(userMembershipRepository.findByTenantIdAndStatus(TENANT_ID, "active"))
            .thenReturn(List.of(
                UserMembershipEntity.create(TENANT_ID, USER_ID, null),
                UserMembershipEntity.create(TENANT_ID, COLLABORATOR_ID, null)
            ));

        WorkflowDraftApi.WorkflowDraftDetail detail = service().updateAccess(
            TENANT_ID,
            USER_ID,
            definition.getId(),
            new WorkflowDraftApi.UpdateWorkflowAccessRequest("specified", "self", List.of(COLLABORATOR_ID), List.of())
        );

        verify(workflowAccessGrantRepository).deleteByWorkflowId(definition.getId());
        verify(workflowAccessGrantRepository).flush();
        verify(workflowAccessGrantRepository).save(any(WorkflowAccessGrantEntity.class));
        assertThat(detail.access().readScope()).isEqualTo("specified");
        assertThat(detail.access().editScope()).isEqualTo("self");
        assertThat(detail.access().readUserIds()).containsExactly(COLLABORATOR_ID);
        assertThat(detail.access().editUserIds()).isEmpty();
    }

    @Test
    void shouldRejectReadOnlyCollaboratorWhenSavingWorkflowGraph() {
        WorkflowDefinitionEntity definition = draft();
        definition.updateAccess("specified", "self", USER_ID, NOW);
        WorkflowAccessGrantEntity readGrant = WorkflowAccessGrantEntity.create(
            TENANT_ID, definition.getId(), COLLABORATOR_ID, "read", USER_ID, NOW
        );
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowAccessGrantRepository.findByWorkflowId(definition.getId())).thenReturn(List.of(readGrant));

        assertThatThrownBy(() -> service().saveGraph(
            TENANT_ID,
            COLLABORATOR_ID,
            definition.getId(),
            new WorkflowDraftApi.SaveWorkflowDraftGraphRequest(List.of(), List.of(), List.of())
        ))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKFLOW_EDIT_ACCESS_REQUIRED");
    }

    @Test
    void shouldDeleteDraftWhenNoRuntimeReferencesExist() {
        WorkflowDefinitionEntity definition = draft();
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowRunRepository.existsByWorkflowId(definition.getId())).thenReturn(false);
        when(workflowScheduleRepository.existsByWorkflowId(definition.getId())).thenReturn(false);

        service().deleteDraft(TENANT_ID, USER_ID, definition.getId());

        verify(workflowAccessGrantRepository).deleteByWorkflowId(definition.getId());
        verify(workflowDefinitionRepository).delete(definition);
    }

    @Test
    void shouldRejectDeleteWhenWorkflowHasRuntimeRuns() {
        WorkflowDefinitionEntity definition = draft();
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowRunRepository.existsByWorkflowId(definition.getId())).thenReturn(true);

        assertThatThrownBy(() -> service().deleteDraft(TENANT_ID, USER_ID, definition.getId()))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("WORKFLOW_HAS_RUNTIME_REFERENCES");
        verify(workflowDefinitionRepository, never()).delete(any());
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
            workflowAccessGrantRepository,
            workflowNodeDefinitionRepository,
            workflowEdgeDefinitionRepository,
            workflowVariableDefinitionRepository,
            workflowVersionRepository,
            workflowRunRepository,
            workflowScheduleRepository,
            workflowVariableDeclarationValidator,
            workflowPublishValidator,
            workflowNodeConfigValidator,
            userMembershipRepository,
            new CollaborationAccessPolicy(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            auditService
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
