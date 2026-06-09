package com.agentum.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.agent.application.ModelChatClient;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelChatClientTest {

    private final OpenAiCompatibleModelChatClient client = new OpenAiCompatibleModelChatClient(new ObjectMapper());

    @Test
    void shouldParseToolCallsWhenArgumentsIsJsonObject() throws Exception {
        String body = """
            {
              "id": "chatcmpl-test",
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {
                      "name": "final_answer",
                      "arguments": {"answer": "hello"}
                    }
                  }]
                }
              }],
              "usage": null
            }
            """;

        ModelChatClient.ChatResult result = invokeParseResult(body);

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("final_answer");
        assertThat(result.toolCalls().get(0).argumentsJson()).contains("hello");
    }

    @Test
    void shouldRejectEmptyResponseBody() throws Exception {
        assertThatThrownBy(() -> invokeParseResult(""))
            .hasCauseInstanceOf(ApiException.class)
            .cause()
            .extracting(Throwable::getMessage)
            .isEqualTo("模型返回空响应体");
    }

    @Test
    void shouldIgnoreNullUsageFieldsWhenBuildingChatResult() throws Exception {
        String body = """
            {
              "id": "chatcmpl-test",
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}
              }],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": null,
                "total_tokens": 10
              }
            }
            """;

        ModelChatClient.ChatResult result = invokeParseResult(body);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.tokenUsage()).containsEntry("prompt_tokens", 10);
        assertThat(result.tokenUsage()).doesNotContainKey("completion_tokens");
    }

    private ModelChatClient.ChatResult invokeParseResult(String body) throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("parseResult", String.class, long.class);
        method.setAccessible(true);
        return (ModelChatClient.ChatResult) method.invoke(client, body, 10L);
    }
}
