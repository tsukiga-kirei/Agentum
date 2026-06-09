package com.agentum.agent.infrastructure;

import com.agentum.agent.application.ModelChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI 兼容流式 chunk 解析辅助：合并 tool_calls 分片，并从 final_answer 参数 JSON 中提取可展示的 answer 增量。
 */
final class OpenAiStreamSupport {

    private OpenAiStreamSupport() {
    }

    static final class StreamingToolCallAssembler {
        private final Map<Integer, PartialToolCall> calls = new TreeMap<>();

        void absorb(JsonNode toolCallsDelta) {
            if (toolCallsDelta == null || !toolCallsDelta.isArray()) {
                return;
            }
            for (JsonNode item : toolCallsDelta) {
                int index = item.path("index").asInt(calls.size());
                PartialToolCall partial = calls.computeIfAbsent(index, ignored -> new PartialToolCall());
                if (item.hasNonNull("id")) {
                    partial.id = item.get("id").asText("");
                }
                JsonNode function = item.path("function");
                if (function.hasNonNull("name")) {
                    partial.name = function.get("name").asText("");
                }
                if (function.has("arguments") && !function.get("arguments").isNull()) {
                    partial.arguments.append(function.get("arguments").asText(""));
                }
            }
        }

        List<ModelChatClient.ToolCall> toToolCalls() {
            List<ModelChatClient.ToolCall> toolCalls = new ArrayList<>();
            for (PartialToolCall partial : calls.values()) {
                if (partial.name == null || partial.name.isBlank()) {
                    continue;
                }
                toolCalls.add(new ModelChatClient.ToolCall(
                    partial.id,
                    partial.name,
                    partial.arguments.isEmpty() ? "{}" : partial.arguments.toString()
                ));
            }
            return toolCalls;
        }

        String latestFinalAnswerArguments() {
            for (PartialToolCall partial : calls.values()) {
                if ("final_answer".equals(partial.name)) {
                    return partial.arguments.toString();
                }
            }
            return "";
        }
    }

    static final class FinalAnswerArgumentStreamer {
        private final StringBuilder argumentsBuffer = new StringBuilder();
        private int streamedAnswerLength;

        /**
         * 消费 arguments 增量，返回本次新增的 answer 文本片段（可能为空字符串）。
         */
        String consume(String argumentDelta) {
            if (argumentDelta == null || argumentDelta.isEmpty()) {
                return "";
            }
            argumentsBuffer.append(argumentDelta);
            String answer = extractAnswerValue(argumentsBuffer.toString());
            if (answer.length() <= streamedAnswerLength) {
                return "";
            }
            String delta = answer.substring(streamedAnswerLength);
            streamedAnswerLength = answer.length();
            return delta;
        }

        String accumulatedAnswer() {
            return extractAnswerValue(argumentsBuffer.toString());
        }
    }

    static String extractAnswerValue(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "";
        }
        int answerKey = rawArguments.indexOf("\"answer\"");
        if (answerKey < 0) {
            return "";
        }
        int colon = rawArguments.indexOf(':', answerKey + 8);
        if (colon < 0) {
            return "";
        }
        int cursor = colon + 1;
        while (cursor < rawArguments.length() && Character.isWhitespace(rawArguments.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= rawArguments.length() || rawArguments.charAt(cursor) != '"') {
            return "";
        }
        cursor++;
        StringBuilder answer = new StringBuilder();
        boolean escaping = false;
        for (; cursor < rawArguments.length(); cursor++) {
            char ch = rawArguments.charAt(cursor);
            if (escaping) {
                answer.append(unescape(ch));
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                continue;
            }
            if (ch == '"') {
                break;
            }
            answer.append(ch);
        }
        return answer.toString();
    }

    private static char unescape(char ch) {
        return switch (ch) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '"' -> '"';
            case '\\' -> '\\';
            default -> ch;
        };
    }

    static Map<String, Object> buildResponseSnapshot(
        String content,
        String finishReason,
        String responseId,
        List<ModelChatClient.ToolCall> toolCalls
    ) {
        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("content", content);
        responseSnapshot.put("finishReason", finishReason);
        responseSnapshot.put("id", responseId);
        if (!toolCalls.isEmpty()) {
            responseSnapshot.put("toolCalls", toolCalls.stream()
                .map(toolCall -> Map.of(
                    "id", toolCall.id(),
                    "name", toolCall.name(),
                    "arguments", toolCall.argumentsJson()
                ))
                .toList());
        }
        return responseSnapshot;
    }

    private static final class PartialToolCall {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }
}
