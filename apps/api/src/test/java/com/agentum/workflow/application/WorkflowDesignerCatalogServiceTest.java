package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.agent.application.AgentRuntimeProperties;
import com.agentum.system.infrastructure.ModelProviderRepository;
import com.agentum.system.infrastructure.TenantModelAssignmentRepository;
import com.agentum.system.domain.ModelProviderEntity;
import com.agentum.system.domain.TenantModelAssignmentEntity;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowDesignerCatalogServiceTest {

    private final AgentRuntimeProperties runtimeProperties = runtimeProperties();
    private final WorkflowDesignerCatalogService service = new WorkflowDesignerCatalogService(
        runtimeProperties,
        mock(TenantModelAssignmentRepository.class),
        mock(ModelProviderRepository.class)
    );

    @Test
    void shouldReturnBackendManagedBrickTemplates() {
        WorkflowDraftApi.WorkflowDesignerCatalog catalog = service.getCatalog(UUID.randomUUID());

        assertThat(catalog.systemTrigger().brickType()).isEqualTo("trigger");
        assertThat(catalog.brickTemplates())
            .extracting(WorkflowDraftApi.WorkflowBrickTemplate::brickType)
            .containsExactly("input", "agent", "cluster", "delivery");
        assertThat(catalog.brickTemplates())
            .flatExtracting(WorkflowDraftApi.WorkflowBrickTemplate::defaultOutputVariables)
            .contains("input_1", "agent_response", "cluster_result", "delivery_record");
        assertThat(catalog.variableMetadata()).containsKeys("starter", "input_1", "agent_response", "cluster_result", "delivery_record");
        assertThat(catalog.agentRuntimeLimits().suggestedIterationsPerTurn()).isEqualTo(4);
        assertThat(catalog.agentRuntimeLimits().maxIterationsPerTurn()).isEqualTo(20);
        assertThat(catalog.brickTemplates().stream()
            .filter(template -> "agent".equals(template.brickType()))
            .findFirst().orElseThrow().defaultConfig())
            .containsEntry("maxAgentIterationsPerTurn", 4);
        @SuppressWarnings("unchecked")
        var inputFields = (java.util.List<java.util.Map<String, Object>>) catalog.brickTemplates().stream()
            .filter(template -> "input".equals(template.brickType()))
            .findFirst().orElseThrow().defaultConfig().get("inputFields");
        assertThat(inputFields.getFirst()).containsEntry("required", true);
    }

    @Test
    void shouldExposeReasoningModelsAssignedToTenant() {
        UUID tenantId = UUID.randomUUID();
        TenantModelAssignmentRepository assignmentRepository = mock(TenantModelAssignmentRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        ModelProviderEntity provider = ModelProviderEntity.create(
            "推理模型",
            "openai-compatible",
            "https://example.test/v1",
            "glm-reasoner",
            true,
            "active",
            Instant.parse("2026-06-22T00:00:00Z")
        );
        TenantModelAssignmentEntity assignment = TenantModelAssignmentEntity.create(
            tenantId,
            provider.getId(),
            "glm-reasoner",
            "enabled",
            Instant.parse("2026-06-22T00:00:00Z")
        );
        when(assignmentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(assignment));
        when(providerRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        WorkflowDesignerCatalogService catalogService = new WorkflowDesignerCatalogService(
            runtimeProperties,
            assignmentRepository,
            providerRepository
        );

        WorkflowDraftApi.WorkflowDesignerCatalog catalog = catalogService.getCatalog(tenantId);

        assertThat(catalog.modelOptions()).singleElement().satisfies(model -> {
            assertThat(model.providerId()).isEqualTo(provider.getId());
            assertThat(model.reasoningModel()).isTrue();
        });
        assertThat(catalog.brickTemplates().stream()
            .filter(template -> "agent".equals(template.brickType()))
            .findFirst().orElseThrow().defaultConfig())
            .containsEntry("modelProviderId", provider.getId().toString())
            .containsEntry("modelName", "glm-reasoner")
            .containsEntry("enableThinking", false);
    }

    private static AgentRuntimeProperties runtimeProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setSuggestedIterationsPerTurn(4);
        properties.setMaxIterationsPerTurn(20);
        return properties;
    }
}
