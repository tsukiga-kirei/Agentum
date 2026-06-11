package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinalAnswerContentResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPreferFinalAnswerToolOverContentField() {
        ModelChatClient.ChatResult result = new ModelChatClient.ChatResult(
            "# 日志里的长正文\n## 第一节",
            Map.of(),
            Map.of(),
            100L,
            List.of(new ModelChatClient.ToolCall(
                "call-1",
                "final_answer",
                "{\"answer\":\"# 前端展示的短正文\\n## 第三节\"}"
            )),
            "tool_calls"
        );

        assertThat(FinalAnswerContentResolver.resolve(result, "", objectMapper))
            .isEqualTo("# 前端展示的短正文\n## 第三节");
    }

    @Test
    void shouldPreferStreamedDisplayWhenToolJsonIsTruncated() {
        String truncated = "{\"answer\":\"## 结论\\n流式正文被截断";
        ModelChatClient.ChatResult result = new ModelChatClient.ChatResult(
            "",
            Map.of(),
            Map.of(),
            100L,
            List.of(new ModelChatClient.ToolCall("call-1", "final_answer", truncated)),
            "tool_calls"
        );

        assertThat(FinalAnswerContentResolver.resolve(result, "## 结论\n流式正文完整版", objectMapper))
            .isEqualTo("## 结论\n流式正文完整版");
    }
}
