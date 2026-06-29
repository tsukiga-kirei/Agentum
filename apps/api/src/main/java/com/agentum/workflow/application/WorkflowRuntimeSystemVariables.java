package com.agentum.workflow.application;

import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流运行时固定变量集中在这里维护，避免智能体、输入节点和交付节点各自生成不同口径的日期。
 */
public final class WorkflowRuntimeSystemVariables {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String[] WEEKDAY_NAMES = {
        "星期一",
        "星期二",
        "星期三",
        "星期四",
        "星期五",
        "星期六",
        "星期日"
    };

    private WorkflowRuntimeSystemVariables() {
    }

    public static Map<String, Object> from(WorkflowRunEntity run, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Map<String, Object> variables = new LinkedHashMap<>();
        putRunVariables(variables, run);
        putDateVariables(variables, today);
        return variables;
    }

    public static Map<String, Object> from(Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Map<String, Object> variables = new LinkedHashMap<>();
        putDateVariables(variables, today);
        return variables;
    }

    public static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("runId", "当前运行实例 ID");
        descriptions.put("runNumber", "当前运行编号");
        descriptions.put("date", "当前日期，格式：2026-06-29");
        descriptions.put("dateCompact", "当前日期紧凑格式：20260629");
        descriptions.put("current_date", "当前日期，格式：2026-06-29");
        descriptions.put("current_date_cn", "当前中文日期，格式：2026 年 6 月 29 日");
        descriptions.put("current_weekday", "当前星期，格式：星期一");
        descriptions.put("current_year", "当前年份");
        descriptions.put("current_month", "当前月份");
        descriptions.put("current_day", "当前日");
        descriptions.put("year", "当前年份");
        descriptions.put("month", "当前月份，两位数字");
        descriptions.put("day", "当前日，两位数字");
        return descriptions;
    }

    private static void putRunVariables(Map<String, Object> variables, WorkflowRunEntity run) {
        if (run == null) {
            return;
        }
        variables.put("runId", run.getId().toString());
        variables.put("runNumber", run.getRunNumber());
    }

    private static void putDateVariables(Map<String, Object> variables, LocalDate today) {
        String isoDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String compactDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        String weekday = WEEKDAY_NAMES[today.getDayOfWeek().getValue() - 1];
        variables.put("date", isoDate);
        variables.put("dateCompact", compactDate);
        variables.put("current_date", isoDate);
        variables.put("current_date_cn", "%d 年 %d 月 %d 日".formatted(today.getYear(), today.getMonthValue(), today.getDayOfMonth()));
        variables.put("current_weekday", weekday);
        variables.put("current_year", String.valueOf(today.getYear()));
        variables.put("current_month", String.valueOf(today.getMonthValue()));
        variables.put("current_day", String.valueOf(today.getDayOfMonth()));
        variables.put("year", String.valueOf(today.getYear()));
        variables.put("month", "%02d".formatted(today.getMonthValue()));
        variables.put("day", "%02d".formatted(today.getDayOfMonth()));
    }
}
