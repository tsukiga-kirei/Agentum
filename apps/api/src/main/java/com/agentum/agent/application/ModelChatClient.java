package com.agentum.agent.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ModelChatClient {

    ChatResult chat(ChatRequest request);

    record ChatRequest(
        UUID providerId,
        String providerType,
        String baseUrl,
        String apiKey,
        String modelName,
        List<ChatMessage> messages,
        Map<String, Object> options
    ) {
        public ChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    record ChatMessage(String role, String content) {
    }

    record ChatResult(String content, Map<String, Object> responseSnapshot, Map<String, Object> tokenUsage, long latencyMs) {
        public ChatResult {
            responseSnapshot = responseSnapshot == null ? Map.of() : Map.copyOf(responseSnapshot);
            tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
        }
    }
}
