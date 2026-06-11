package com.agentum.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与运行态落库、前端展示共用的 final_answer 解析口径。
 * 日志记录最终答案时须走同一解析链，避免 content 与 tool.arguments.answer 不一致时误导排查。
 */
public final class FinalAnswerContentResolver {

    private static final Pattern FINAL_ANSWER_FALLBACK_PATTERN =
        Pattern.compile("\"answer\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private FinalAnswerContentResolver() {
    }

    /**
     * 统一解析最终展示/落库正文：完整 JSON → 流式累积文本 → 模型正文 → 截断 JSON 片段。
     */
    public static String resolve(
        ModelChatClient.ChatResult result,
        String streamedDisplayText,
        ObjectMapper objectMapper
    ) {
        if (result == null) {
            return "";
        }
        Optional<String> parsedFromTool = extractCompleteFinalAnswer(result.toolCalls(), objectMapper);
        if (parsedFromTool.isPresent() && !parsedFromTool.get().isBlank()) {
            return parsedFromTool.get();
        }
        String streamed = firstNonBlank(streamedDisplayText);
        if (!streamed.isBlank()) {
            return streamed;
        }
        String content = firstNonBlank(result.content());
        if (!content.isBlank()) {
            return content;
        }
        for (ModelChatClient.ToolCall toolCall : result.toolCalls()) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            String partial = extractPartialAnswerFromTruncatedJson(toolCall.argumentsJson());
            if (!partial.isBlank()) {
                return partial;
            }
        }
        return "";
    }

    static String extractPartialAnswerFromTruncatedJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "";
        }
        int answerKey = indexOfAnswerKey(rawJson);
        if (answerKey < 0) {
            return "";
        }
        int colon = rawJson.indexOf(':', answerKey);
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < rawJson.length() && Character.isWhitespace(rawJson.charAt(start))) {
            start++;
        }
        if (start >= rawJson.length()) {
            return "";
        }
        char quote = rawJson.charAt(start);
        if (quote != '"' && quote != '\'') {
            return "";
        }
        start++;
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = start; index < rawJson.length(); index++) {
            char current = rawJson.charAt(index);
            if (escaped) {
                appendEscapedChar(builder, current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                break;
            }
            builder.append(current);
        }
        return builder.toString().trim();
    }

    static boolean looksLikeTruncatedFinalAnswerJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        String trimmed = rawJson.trim();
        if (!trimmed.startsWith("{") || indexOfAnswerKey(trimmed) < 0) {
            return false;
        }
        return !trimmed.endsWith("}") && !trimmed.endsWith("\"}");
    }

    private static Optional<String> extractCompleteFinalAnswer(
        List<ModelChatClient.ToolCall> toolCalls,
        ObjectMapper objectMapper
    ) {
        for (ModelChatClient.ToolCall toolCall : toolCalls) {
            if (!"final_answer".equals(toolCall.name())) {
                continue;
            }
            String rawJson = toolCall.argumentsJson();
            Map<String, Object> args = looksLikeTruncatedFinalAnswerJson(rawJson)
                ? Map.of("raw", rawJson)
                : parseJsonObject(rawJson, objectMapper);
            String answer = stringValue(args.get("answer"));
            if (!answer.isBlank()) {
                return Optional.of(answer);
            }
            Matcher matcher = FINAL_ANSWER_FALLBACK_PATTERN.matcher(rawJson == null ? "" : rawJson);
            if (matcher.find()) {
                return Optional.of(unescapeJsonString(matcher.group(1)));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String rawJson, ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        } catch (Exception ignored) {
            // 截断或非法 JSON 时回退 raw 文本路径。
        }
        return Map.of("raw", rawJson);
    }

    private static int indexOfAnswerKey(String rawJson) {
        int answerKey = rawJson.indexOf("\"answer\"");
        if (answerKey >= 0) {
            return answerKey;
        }
        return rawJson.indexOf("'answer'");
    }

    private static void appendEscapedChar(StringBuilder builder, char current) {
        switch (current) {
            case 'n' -> builder.append('\n');
            case 't' -> builder.append('\t');
            case 'r' -> builder.append('\r');
            default -> builder.append(current);
        }
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
