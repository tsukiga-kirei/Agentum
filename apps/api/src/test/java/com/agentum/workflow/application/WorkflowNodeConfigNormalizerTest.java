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
}
