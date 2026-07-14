package com.agentum.workflow.application;

import com.agentum.shared.platform.AgentumTimezones;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析输入节点的系统日期默认值。
 *
 * <p>动态日期必须以运行或计划触发时间为基准在后端计算，不能依赖浏览器时区，也不能在创建定时任务时固化。</p>
 */
public final class WorkflowInputDefaultValueResolver {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private WorkflowInputDefaultValueResolver() {
    }

    public static Map<String, Object> apply(
        Map<String, Object> nodeConfig,
        Map<String, Object> submittedPayload,
        Instant referenceTime,
        boolean forceSystemValues
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>(submittedPayload == null ? Map.of() : submittedPayload);
        Object rawFields = nodeConfig == null ? null : nodeConfig.get("inputFields");
        if (!(rawFields instanceof List<?> fields)) {
            return resolved;
        }
        LocalDate referenceDate = referenceTime.atZone(AgentumTimezones.businessZone()).toLocalDate();
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> field) || !"system".equals(stringValue(field.get("defaultValueSource")))) {
                continue;
            }
            String variable = stringValue(field.get("variable"));
            if (variable.isBlank()) {
                continue;
            }
            boolean allowManualOverride = !Boolean.FALSE.equals(field.get("allowManualOverride"));
            Object submittedValue = resolved.get(variable);
            if (!forceSystemValues && allowManualOverride && !isBlank(submittedValue)) {
                continue;
            }
            resolved.put(variable, resolveRule(stringValue(field.get("systemDefaultValue")), referenceDate));
        }
        return resolved;
    }

    private static String resolveRule(String rule, LocalDate referenceDate) {
        return switch (rule) {
            case "current_year" -> String.valueOf(referenceDate.getYear());
            case "current_month" -> referenceDate.format(YEAR_MONTH);
            case "previous_month" -> referenceDate.minusMonths(1).format(YEAR_MONTH);
            case "current_date", "" -> referenceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            default -> referenceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        };
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
