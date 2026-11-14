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
                // OpenAI 兼容流式规范：若未显式提供 index 字段，默认为首个工具调用 (0)
                int index = item.has("index") ? item.get("index").asInt(0) : 0;
                PartialToolCall partial = calls.computeIfAbsent(index, ignored -> new PartialToolCall());
                if (item.hasNonNull("id") && !item.get("id").asText("").isBlank()) {
                    partial.id = item.get("id").asText("");
                }
                JsonNode function = item.path("function");
                if (function.hasNonNull("name") && !function.get("name").asText("").isBlank()) {
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
            // 兼容部分网关只在首个或结尾 chunk 传递 name，单工具且 arguments 持续增加时持续透传
            if (calls.size() == 1) {
                PartialToolCall single = calls.values().iterator().next();
                if (single.arguments.length() > 0 && (single.name == null || single.name.isBlank() || "final_answer".equals(single.name))) {
                    return single.arguments.toString();
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
        int keyLength = 8;
        if (answerKey < 0) {
            answerKey = rawArguments.indexOf("'answer'");
            if (answerKey < 0) {
                return "";
            }
        }
        int colon = rawArguments.indexOf(':', answerKey + keyLength);
        if (colon < 0) {
            return "";
        }
        int cursor = colon + 1;
        while (cursor < rawArguments.length() && Character.isWhitespace(rawArguments.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= rawArguments.length()) {
            return "";
        }
        char quote = rawArguments.charAt(cursor);
        if (quote != '"' && quote != '\'') {
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
            if (ch == quote) {
                // 遇到字符串闭合引号退出
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
