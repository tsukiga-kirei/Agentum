package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequiredMcpResultsTest {
    private WorkflowNodeRunEntity node(String key, String type, int order, Map<String, Object> config) {
        var node = mock(WorkflowNodeRunEntity.class);
        lenient().when(node.getNodeKey()).thenReturn(key); lenient().when(node.getNodeType()).thenReturn(type);
        lenient().when(node.getSortOrder()).thenReturn(order); lenient().when(node.getConfigSnapshot()).thenReturn(config); return node;
    }
    @Test void isolatesClusterMembersAndIgnoresDisabledItemsAndInactiveTemplateMode() {
        var source = node("collect", "parallel_group", 2, Map.of());
        var binding = Map.of("sourceNodeId", "collect", "variable", "mcp_datasets", "path", "member_1.cap_demo.rows");
        var config = Map.<String, Object>of("generationMode", "file_template", "templateBindings", List.of(binding));
        var delivery = node("deliver", "delivery", 3, Map.of("deliveryConfigMode", "multiple", "deliveryItems", List.of(Map.of("config", config))));
        var nodes = List.of(source, delivery);
        assertThat(RequiredMcpResults.paths(RequiredMcpResults.config(Map.of(), source, 0, nodes))).isEmpty();
        var required = RequiredMcpResults.config(Map.of(), source, 1, nodes);
        assertThat(RequiredMcpResults.paths(required)).containsExactly("cap_demo.rows");
        assertThatThrownBy(() -> RequiredMcpResults.validate(required, Map.of())).hasMessageContaining("尚未产生完整结果");
        RequiredMcpResults.validate(required, Map.of("cap_demo", Map.of("rows", Map.of("datasetId", UUID.randomUUID().toString()))));
        var disabled = node("disabled", "delivery", 3, Map.of("deliveryConfigMode", "multiple", "deliveryItems", List.of(Map.of("enabled", false, "config", config))));
        assertThat(RequiredMcpResults.paths(RequiredMcpResults.config(Map.of(), source, 1, List.of(source, disabled)))).isEmpty();
        var inactive = node("inactive", "delivery", 3, Map.of("generationMode", "content", "templateBindings", List.of(binding)));
        assertThat(RequiredMcpResults.paths(RequiredMcpResults.config(Map.of(), source, 1, List.of(source, inactive)))).isEmpty();
    }
}
