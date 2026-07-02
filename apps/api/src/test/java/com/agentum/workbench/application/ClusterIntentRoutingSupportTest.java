package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.agentum.shared.api.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterIntentRoutingSupportTest {

    @Test
    void shouldOnlySelectConfiguredIntentCodes() {
        List<Map<String, Object>> agents = List.of(
            Map.of("id", "agent_monthly", "name", "月报智能体"),
            Map.of("id", "agent_other", "name", "其他智能体")
        );
        Map<String, Object> nodeConfig = Map.of(
            "intentRoutes", List.of(
                Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报", "agentId", "agent_monthly")
            )
        );

        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            nodeConfig,
            ClusterIntentRoutingSupport.intentRoutes(nodeConfig, agents),
            agents,
            Map.of("final_answer", "{\"intentCodes\":[\"agent_secret\",\"monthly_report\"],\"confidence\":0.9,\"reason\":\"用户要求月报\"}")
        );

        assertThat(decision.selectedCodes()).containsExactly("monthly_report");
        assertThat(decision.selectedAgentIndexes()).containsExactly(0);
    }

    @Test
    void shouldUseFallbackAgentWhenNoIntentMatches() {
        List<Map<String, Object>> agents = List.of(
            Map.of("id", "agent_monthly", "name", "月报智能体"),
            Map.of("id", "agent_other", "name", "其他智能体")
        );
        Map<String, Object> nodeConfig = Map.of(
            "intentFallbackMode", "agent",
            "fallbackAgentId", "agent_other",
            "intentRoutes", List.of(
                Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报", "agentId", "agent_monthly")
            )
        );

        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            nodeConfig,
            ClusterIntentRoutingSupport.intentRoutes(nodeConfig, agents),
            agents,
            Map.of("final_answer", "{\"intentCodes\":[],\"reason\":\"表达不明确\"}")
        );

        assertThat(decision.selectedCodes()).isEmpty();
        assertThat(decision.selectedAgentIndexes()).containsExactly(1);
        assertThat(decision.usedFallback()).isTrue();
    }

    @Test
    void shouldParseJsonWhenModelAddsMarkdownFenceOrLeadingDots() {
        List<Map<String, Object>> agents = List.of(Map.of("id", "agent_monthly", "name", "月报智能体"));
        Map<String, Object> nodeConfig = Map.of(
            "intentRoutes", List.of(
                Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报", "agentId", "agent_monthly")
            )
        );

        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            nodeConfig,
            ClusterIntentRoutingSupport.intentRoutes(nodeConfig, agents),
            agents,
            Map.of("final_answer", "...```json\n{\"intentCodes\":[\"monthly_report\"],\"reason\":\"用户要求月报\"}\n```")
        );

        assertThat(decision.selectedCodes()).containsExactly("monthly_report");
        assertThat(decision.selectedAgentIndexes()).containsExactly(0);
    }

    @Test
    void shouldRejectLegacyExecutionModeAfterMigration() {
        ApiException exception = catchThrowableOfType(
            () -> ClusterIntentRoutingSupport.normalizeExecutionMode("parallel"),
            ApiException.class
        );

        assertThat(exception.getCode()).isEqualTo("WORKFLOW_CLUSTER_EXECUTION_MODE_INVALID");
    }
}
