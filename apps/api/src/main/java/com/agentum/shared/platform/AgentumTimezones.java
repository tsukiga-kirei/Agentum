package com.agentum.shared.platform;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 平台业务时区入口。
 *
 * <p>通过环境变量 {@code AGENTUM_TIMEZONE} 统一驱动 JVM 默认时区、定时任务 cron 和业务日期变量，
 * 避免 Docker 默认 UTC 与产品文案「北京时间」不一致。</p>
 */
public final class AgentumTimezones {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static volatile ZoneId businessZone = ZoneId.of(DEFAULT_TIMEZONE);

    private AgentumTimezones() {
    }

    /**
     * 在 Spring 启动前尽早调用，确保日志行前缀时间与后续 Java 时间 API 使用同一时区。
     */
    public static void bootstrapFromEnvironment() {
        apply(firstNonBlank(System.getenv("AGENTUM_TIMEZONE"), DEFAULT_TIMEZONE));
    }

    public static void apply(String timezoneId) {
        String normalized = timezoneId == null ? "" : timezoneId.trim();
        if (normalized.isBlank()) {
            normalized = DEFAULT_TIMEZONE;
        }
        ZoneId zone = ZoneId.of(normalized);
        businessZone = zone;
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        System.setProperty("agentum.timezone", zone.getId());
    }

    public static ZoneId businessZone() {
        return businessZone;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }
}
