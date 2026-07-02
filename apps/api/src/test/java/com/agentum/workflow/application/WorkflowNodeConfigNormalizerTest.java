package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowNodeConfigNormalizerTest {

    @Test
    void shouldFillDefaultCustomPromptsForLegacyAgentNode() {
        WorkflowDraftApi.WorkflowNodeDraft node = new WorkflowDraftApi.WorkflowNodeDraft(
            "agent_1",
            "agent",
            "单智能体处理",
            0,
            0,
            List.of(),
            List.of("agent_response"),
            Map.of(
                "systemPromptTemplateId", "none",
                "userPromptTemplateId", "none",
                "systemPrompt", "",
                "userPrompt", ""
            )
        );

        WorkflowDraftApi.WorkflowNodeDraft normalized = WorkflowNodeConfigNormalizer.normalizeNode(node);

        assertThat(normalized.config().get("systemPrompt")).isEqualTo(WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT);
        assertThat(normalized.config().get("userPrompt")).isEqualTo(WorkflowPromptDefaults.DEFAULT_USER_PROMPT);
    }

    @Test
    void shouldSyncAgentOutputVariableFromOutputVariables() {
        WorkflowDraftApi.WorkflowNodeDraft node = new WorkflowDraftApi.WorkflowNodeDraft(
            "agent_1",
            "agent",
            "单智能体处理",
            0,
            0,
            List.of(),
            List.of("agent"),
            Map.of("userPrompt", "请完成任务")
        );

        WorkflowDraftApi.WorkflowNodeDraft normalized = WorkflowNodeConfigNormalizer.normalizeNode(node);

        assertThat(normalized.config().get("output")).isEqualTo("agent");
        assertThat(normalized.config().get("outputVariable")).isEqualTo("agent");
    }

    @Test
    void shouldFillClusterAgentPromptsWithoutRewritingExecutionMode() {
        WorkflowDraftApi.WorkflowNodeDraft node = new WorkflowDraftApi.WorkflowNodeDraft(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("input_1"),
            List.of("agent_1_output"),
            Map.of(
                "executionMode", "relay",
                "clusterAgents", List.of(Map.of(
                    "id", "agent_1",
                    "name", "子智能体 1",
                    "output", "agent_1_output",
                    "systemPromptTemplateId", "none",
                    "userPromptTemplateId", "none",
                    "systemPrompt", "",
                    "userPrompt", ""
                ))
            )
        );

        WorkflowDraftApi.WorkflowNodeDraft normalized = WorkflowNodeConfigNormalizer.normalizeNode(node);

        assertThat(normalized.config().get("executionMode")).isEqualTo("relay");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) normalized.config().get("clusterAgents");
        assertThat(agents.getFirst().get("systemPrompt")).isEqualTo(WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT);
        assertThat(agents.getFirst().get("userPrompt")).isEqualTo(WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT);
    }
}
