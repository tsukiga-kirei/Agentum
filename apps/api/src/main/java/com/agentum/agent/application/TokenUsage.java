package com.agentum.agent.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化模型 Token 用量。
 *
 * <p>不同 OpenAI 兼容网关可能返回 prompt/completion 或 input/output 两套字段名，
 * 运行态和审计只暴露这一份稳定契约，避免前端理解供应商差异。</p>
 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }

    public static TokenUsage fromProviderUsage(Map<String, Object> rawUsage) {
        if (rawUsage == null || rawUsage.isEmpty()) {
            return empty();
        }
        long input = firstLong(rawUsage, "prompt_tokens", "input_tokens", "promptTokens", "inputTokens");
        long output = firstLong(rawUsage, "completion_tokens", "output_tokens", "completionTokens", "outputTokens");
        long total = firstLong(rawUsage, "total_tokens", "totalTokens");
        return new TokenUsage(input, output, total > 0 ? total : input + output);
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            totalTokens + other.totalTokens
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", totalTokens);
        return result;
    }

    private static long firstLong(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Number number) {
                return Math.max(0, number.longValue());
            }
            if (value instanceof String text) {
                try {
                    return Math.max(0, Long.parseLong(text.trim()));
                } catch (NumberFormatException ignored) {
                    // 供应商异常字段按 0 处理，原始 usage 仍保留在调用日志中供排查。
                }
            }
        }
        return 0;
    }
}
