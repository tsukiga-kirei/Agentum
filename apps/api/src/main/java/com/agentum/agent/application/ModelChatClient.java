package com.agentum.agent.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ModelChatClient {

    ChatResult chat(ChatRequest request);

    interface StreamingCallback {
        void onChunk(String deltaContent);

        /**
         * final_answer 工具参数中的 answer 字段流式到达时触发，供运行态 SSE 展示 Markdown 正文。
         */
        default void onFinalAnswerDelta(String deltaContent, String accumulatedAnswer) {
        }

        void onComplete(ChatResult result);
        void onError(String code, String message);
    }

    default void chatStream(ChatRequest request, StreamingCallback callback) {
        try {
            ChatResult result = chat(request);
            callback.onChunk(result.content());
            callback.onComplete(result);
        } catch (Exception e) {
            callback.onError("MODEL_STREAM_ERROR", e.getMessage());
        }
    }

    record ChatRequest(
        UUID providerId,
        String providerType,
        String baseUrl,
        String apiKey,
        String modelName,
        List<ChatMessage> messages,
        Map<String, Object> options,
        List<ToolDefinition> tools
    ) {
        public ChatRequest(
            UUID providerId,
            String providerType,
            String baseUrl,
            String apiKey,
            String modelName,
            List<ChatMessage> messages,
            Map<String, Object> options
        ) {
            this(providerId, providerType, baseUrl, apiKey, modelName, messages, options, List.of());
        }

        public ChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            options = options == null ? Map.of() : Map.copyOf(options);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public ChatMessage(String role, String content) {
            this(role, content, null, List.of());
        }

        public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", content == null ? "" : content, null, toolCalls);
        }

        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content == null ? "" : content, toolCallId, List.of());
        }

        public ChatMessage {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    record ToolDefinition(String name, String description, Map<String, Object> parameters) {
        public ToolDefinition {
            description = description == null ? "" : description;
            parameters = parameters == null ? Map.of("type", "object", "properties", Map.of()) : Map.copyOf(parameters);
        }
    }

    record ToolCall(String id, String name, String argumentsJson) {
        public ToolCall {
            id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            name = name == null ? "" : name;
            argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        }
    }

    record ChatResult(
        String content,
        Map<String, Object> responseSnapshot,
        Map<String, Object> tokenUsage,
        long latencyMs,
        List<ToolCall> toolCalls,
        String finishReason
    ) {
        public ChatResult(String content, Map<String, Object> responseSnapshot, Map<String, Object> tokenUsage, long latencyMs) {
            this(content, responseSnapshot, tokenUsage, latencyMs, List.of(), "");
        }

        public ChatResult {
            content = content == null ? "" : content;
            responseSnapshot = safeCopy(responseSnapshot);
            tokenUsage = safeCopy(tokenUsage);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
        }

        private static Map<String, Object> safeCopy(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    sanitized.put(key, value);
                }
            });
            return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
        }
    }
}
