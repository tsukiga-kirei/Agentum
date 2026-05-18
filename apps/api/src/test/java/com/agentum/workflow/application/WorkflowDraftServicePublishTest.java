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
import java.util.List;
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

    private void stubDefinitionLookup(WorkflowDefinitionEntity definition) {
        when(tenantRepository.findByIdAndStatus(TENANT_ID, "active")).thenReturn(Optional.of(TenantEntity.create("租户", "tenant", NOW)));
        when(workflowDefinitionRepository.findByIdAndTenantId(definition.getId(), TENANT_ID)).thenReturn(Optional.of(definition));
        when(workflowNodeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(workflowEdgeDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(workflowVariableDefinitionRepository.findByWorkflowIdOrderBySortOrderAsc(definition.getId())).thenReturn(List.of());
        when(userAccountRepository.findAll()).thenReturn(List.of());
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
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static WorkflowDefinitionEntity draft() {
        return WorkflowDefinitionEntity.create(TENANT_ID, "需求评审", "用于验证正式发布", USER_ID, NOW);
    }
}
