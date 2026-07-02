package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterIntentRoutingSupportTest {

    @Test
    void shouldOnlySelectConfiguredIntentCodes() {
        List<Map<String, Object>> agents = List.of(
            Map.of("name", "月报智能体", "intentCode", "monthly_report", "intentDescription", "处理月报"),
            Map.of("name", "其他智能体", "intentCode", "other", "intentDescription", "处理其他")
        );

        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            Map.of("intentConfidenceThreshold", 0.6),
            ClusterIntentRoutingSupport.intentRoutes(agents),
            Map.of("final_answer", "{\"intentCodes\":[\"agent_secret\",\"monthly_report\"],\"confidence\":0.9,\"reason\":\"用户要求月报\"}")
        );

        assertThat(decision.selectedCodes()).containsExactly("monthly_report");
        assertThat(decision.selectedAgentIndexes()).containsExactly(0);
    }

    @Test
    void shouldUseFallbackIntentWhenConfidenceIsLow() {
        List<Map<String, Object>> agents = List.of(
            Map.of("name", "月报智能体", "intentCode", "monthly_report", "intentDescription", "处理月报"),
            Map.of("name", "其他智能体", "intentCode", "other", "intentDescription", "处理其他")
        );

        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            Map.of(
                "intentConfidenceThreshold", 0.8,
                "intentFallbackMode", "fallback_intent",
                "fallbackIntentCode", "other"
            ),
            ClusterIntentRoutingSupport.intentRoutes(agents),
            Map.of("final_answer", "{\"intentCodes\":[\"monthly_report\"],\"confidence\":0.4,\"reason\":\"表达不明确\"}")
        );

        assertThat(decision.selectedCodes()).containsExactly("other");
        assertThat(decision.selectedAgentIndexes()).containsExactly(1);
        assertThat(decision.usedFallback()).isTrue();
    }
}
