package com.agentum.delivery.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 统一解释 Word、Excel 交付文件保存策略。永久保存不生成到期时间，按天保存才进入清理任务扫描范围。
 */
record DeliveryFileRetention(String policy, Integer days, Instant expiresAt) {

    static DeliveryFileRetention from(Map<String, Object> config, Instant createdAt) {
        String policy = stringValue(config == null ? null : config.get("retentionPolicy"));
        // 历史能力只有 retentionDays，缺少策略时继续按天保存，避免升级后意外把已有交付文件改成永久。
        if (policy.isBlank()) {
            policy = "days";
        }
        if ("permanent".equals(policy)) {
            return new DeliveryFileRetention("permanent", null, null);
        }
        int days = positiveInt(config == null ? null : config.get("retentionDays"), 180, 1, 3650);
        return new DeliveryFileRetention("days", days, createdAt.plus(days, ChronoUnit.DAYS));
    }

    void writeTo(Map<String, Object> result) {
        result.put("retentionPolicy", policy);
        if ("days".equals(policy)) {
            result.put("retentionDays", days);
            result.put("expiresAt", expiresAt.toString());
        }
    }

    private static int positiveInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null && !value.toString().isBlank()) {
            try {
                parsed = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim().toLowerCase(java.util.Locale.ROOT);
    }
}
