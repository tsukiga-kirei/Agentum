package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentRuntimeExtractFinalAnswerTest {

    @Test
    void shouldExtractPartialAnswerFromTruncatedJson() {
        String truncated = "{\"answer\":\"## 结论\\n这是被截断的最终答案，缺少闭合引号";
        assertThat(AgentRuntimeService.extractPartialAnswerFromTruncatedJson(truncated))
            .isEqualTo("## 结论\n这是被截断的最终答案，缺少闭合引号");
    }

    @Test
    void shouldReturnEmptyWhenAnswerKeyMissing() {
        assertThat(AgentRuntimeService.extractPartialAnswerFromTruncatedJson("{\"summary\":\"无答案\"}"))
            .isBlank();
    }
}
