package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import org.junit.jupiter.api.Test;

class WorkflowDesignerCatalogServiceTest {

    private final WorkflowDesignerCatalogService service = new WorkflowDesignerCatalogService();

    @Test
    void shouldReturnBackendManagedBrickTemplates() {
        WorkflowDraftApi.WorkflowDesignerCatalog catalog = service.getCatalog();

        assertThat(catalog.systemTrigger().brickType()).isEqualTo("trigger");
        assertThat(catalog.brickTemplates())
            .extracting(WorkflowDraftApi.WorkflowBrickTemplate::brickType)
            .containsExactly("input", "agent", "cluster", "delivery");
        assertThat(catalog.brickTemplates())
            .flatExtracting(template -> template.defaultOutputVariables())
            .contains("input_1", "agent_response", "cluster_result", "delivery_record");
        assertThat(catalog.variableMetadata()).containsKeys("starter", "input_1", "agent_response", "cluster_result", "delivery_record");
    }
}
