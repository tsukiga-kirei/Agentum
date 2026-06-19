package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenUsageTest {

    @Test
    void shouldNormalizeProviderFieldNamesAndAccumulateTurnUsage() {
        TokenUsage openAi = TokenUsage.fromProviderUsage(Map.of(
            "prompt_tokens", 120,
            "completion_tokens", 30,
            "total_tokens", 150
        ));
        TokenUsage inputOutput = TokenUsage.fromProviderUsage(Map.of(
            "input_tokens", "40",
            "output_tokens", 10
        ));

        assertThat(openAi.plus(inputOutput)).isEqualTo(new TokenUsage(160, 40, 200));
    }
}
