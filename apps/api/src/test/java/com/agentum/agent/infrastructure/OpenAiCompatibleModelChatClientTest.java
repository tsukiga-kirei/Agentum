package com.agentum.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.agent.application.ModelChatClient;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    @Test
    void shouldOmitMaxTokensWhenNotConfigured() throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("buildPayload", ModelChatClient.ChatRequest.class);
        method.setAccessible(true);
        ModelChatClient.ChatRequest request = new ModelChatClient.ChatRequest(
            UUID.randomUUID(),
            "openai-compatible",
            "https://example.test",
            "sk-test",
            "gpt-4o-mini",
            List.of(new ModelChatClient.ChatMessage("user", "hello")),
            Map.of(),
            List.of()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) method.invoke(client, request);

        assertThat(payload).doesNotContainKey("max_tokens");
    }

    @Test
    void shouldIncludeMaxTokensWhenConfigured() throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("buildPayload", ModelChatClient.ChatRequest.class);
        method.setAccessible(true);
        ModelChatClient.ChatRequest request = new ModelChatClient.ChatRequest(
            UUID.randomUUID(),
            "openai-compatible",
            "https://example.test",
            "sk-test",
            "gpt-4o-mini",
            List.of(new ModelChatClient.ChatMessage("user", "hello")),
            Map.of("maxTokens", 8192),
            List.of()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) method.invoke(client, request);

        assertThat(payload).containsEntry("max_tokens", 8192);
    }

    @Test
    void shouldMaskCredentialsInAiDebugPayload() throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("payloadSummaryForLog", Map.class);
        method.setAccessible(true);

        String loggedPayload = (String) method.invoke(client, Map.of(
            "messages", List.of(Map.of(
                "role", "user",
                "content", "请调用接口，Authorization: Bearer secret-token，token=plain-token"
            )),
            "extensions", Map.of(
                "apiKey", "sk-1234567890abcdef",
                "safe", "visible"
            )
        ));

        assertThat(loggedPayload)
            .contains("visible", "******")
            .doesNotContain("secret-token", "plain-token", "sk-1234567890abcdef");
    }

    @Test
    void shouldLogCorrelatedAiRequestAndResponseAtDebugLevel() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleModelChatClient.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            UUID runId = UUID.randomUUID();
            UUID nodeRunId = UUID.randomUUID();
            UUID modelCallLogId = UUID.randomUUID();
            ModelChatClient.ChatRequest request = new ModelChatClient.ChatRequest(
                UUID.randomUUID(),
                "openai-compatible",
                "https://example.test",
                "sk-never-log-this",
                "gpt-test",
                List.of(new ModelChatClient.ChatMessage("user", "请分析 token=never-log-this")),
                Map.of("temperature", 0.2),
                List.of(new ModelChatClient.ToolDefinition("final_answer", "提交答案", Map.of("type", "object"))),
                null,
                runId,
                nodeRunId,
                modelCallLogId
            );
            Map<String, Object> payload = invokeBuildPayload(request);
            Method requestLogger = OpenAiCompatibleModelChatClient.class.getDeclaredMethod(
                "logChatRequest", ModelChatClient.ChatRequest.class, URI.class, Map.class, boolean.class
            );
            requestLogger.setAccessible(true);
            requestLogger.invoke(client, request, URI.create("https://example.test/chat/completions"), payload, false);

            ModelChatClient.ChatResult result = new ModelChatClient.ChatResult(
                "",
                Map.of(),
                Map.of("total_tokens", 12),
                15L,
                List.of(new ModelChatClient.ToolCall("call-final", "final_answer", "{\"answer\":\"完成\"}")),
                "tool_calls"
            );
            Method responseLogger = OpenAiCompatibleModelChatClient.class.getDeclaredMethod(
                "logChatResponse", ModelChatClient.ChatRequest.class, ModelChatClient.ChatResult.class, String.class, long.class, boolean.class
            );
            responseLogger.setAccessible(true);
            responseLogger.invoke(client, request, result, "完成", 15L, false);

            assertThat(appender.list).hasSize(2);
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs)
                .contains("AI调用链路-请求", "AI调用链路-响应")
                .contains(runId.toString(), nodeRunId.toString(), modelCallLogId.toString())
                .contains("final_answer", "完成")
                .doesNotContain("never-log-this", "sk-never-log-this");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void shouldTreatCancelWatcherFlagAsStreamCancelled() throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod(
            "isStreamCancelled",
            BooleanSupplier.class,
            AtomicBoolean.class
        );
        method.setAccessible(true);

        AtomicBoolean aborted = new AtomicBoolean(true);
        boolean cancelled = (boolean) method.invoke(null, (BooleanSupplier) () -> false, aborted);

        assertThat(cancelled).isTrue();
    }

    private ModelChatClient.ChatResult invokeParseResult(String body) throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("parseResult", String.class, long.class);
        method.setAccessible(true);
        return (ModelChatClient.ChatResult) method.invoke(client, body, 10L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildPayload(ModelChatClient.ChatRequest request) throws Exception {
        Method method = OpenAiCompatibleModelChatClient.class.getDeclaredMethod("buildPayload", ModelChatClient.ChatRequest.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(client, request);
    }
}
