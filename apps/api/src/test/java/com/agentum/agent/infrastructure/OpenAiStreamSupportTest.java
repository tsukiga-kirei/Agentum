package com.agentum.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.agent.application.ModelChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiStreamSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExtractPartialAnswerFromStreamingArguments() {
        OpenAiStreamSupport.FinalAnswerArgumentStreamer streamer = new OpenAiStreamSupport.FinalAnswerArgumentStreamer();

        assertThat(streamer.consume("{\"answer\": \"## 标题")).isEqualTo("## 标题");
        assertThat(streamer.consume("\\n正文\"")).isEqualTo("\n正文");
        assertThat(streamer.accumulatedAnswer()).isEqualTo("## 标题\n正文");
    }

    @Test
    void shouldAssembleToolCallsFromStreamChunks() throws Exception {
        OpenAiStreamSupport.StreamingToolCallAssembler assembler = new OpenAiStreamSupport.StreamingToolCallAssembler();
        assembler.absorb(objectMapper.readTree("""
            [{
              "index": 0,
              "id": "call_1",
              "function": {"name": "final_answer", "arguments": "{\\"answer\\": \\"ok"}
            }]
            """));
        assembler.absorb(objectMapper.readTree("""
            [{
              "index": 0,
              "function": {"arguments": "\\"}"}
            }]
            """));

        assertThat(assembler.toToolCalls()).hasSize(1);
        ModelChatClient.ToolCall toolCall = assembler.toToolCalls().get(0);
        assertThat(toolCall.name()).isEqualTo("final_answer");
        assertThat(toolCall.argumentsJson()).contains("ok");
    }
}
