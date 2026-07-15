package com.agentum.workflow.application;

import com.agentum.shared.platform.AgentumTimezones;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析输入节点的系统日期默认值。
 *
 * <p>动态日期必须以运行或计划触发时间为基准在后端计算，不能依赖浏览器时区，也不能在创建定时任务时固化。</p>
 */
public final class WorkflowInputDefaultValueResolver {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");
    public static final String SCHEDULE_VALUE_TYPE_KEY = "__scheduleValueType";
    public static final String SCHEDULE_SYSTEM_VALUE_TYPE = "system";
    public static final String SCHEDULE_FIXED_VALUE_TYPE = "fixed";
    public static final String SCHEDULE_SYSTEM_RULE_KEY = "rule";
    public static final String SCHEDULE_FIXED_VALUE_KEY = "value";

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

    /**
     * 解析定时任务对输入字段的覆盖值。
     *
     * <p>定时任务允许覆盖流程中的日期规则与文本预设。日期规则使用显式对象保存，文本模板则保留
     * {@code {{variable}}}，直到每次计划触发时结合本次运行变量解析，避免在创建任务时固化日期。</p>
     */
    public static Map<String, Object> applyScheduledOverrides(
        Map<String, Object> nodeConfig,
        Map<String, Object> scheduledPayload,
        Instant referenceTime,
        Map<String, Object> runtimeVariables
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>(scheduledPayload == null ? Map.of() : scheduledPayload);
        Object rawFields = nodeConfig == null ? null : nodeConfig.get("inputFields");
        if (!(rawFields instanceof List<?> fields)) {
            return resolved;
        }
        LocalDate referenceDate = referenceTime.atZone(AgentumTimezones.businessZone()).toLocalDate();
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> field)) {
                continue;
            }
            String variable = stringValue(field.get("variable"));
            if (variable.isBlank()) {
                continue;
            }
            Object scheduledValue = resolved.get(variable);
            if (scheduledValue instanceof Map<?, ?> binding
                && SCHEDULE_SYSTEM_VALUE_TYPE.equals(stringValue(binding.get(SCHEDULE_VALUE_TYPE_KEY)))) {
                resolved.put(variable, resolveRule(stringValue(binding.get(SCHEDULE_SYSTEM_RULE_KEY)), referenceDate));
                continue;
            }
            if (scheduledValue instanceof Map<?, ?> binding
                && SCHEDULE_FIXED_VALUE_TYPE.equals(stringValue(binding.get(SCHEDULE_VALUE_TYPE_KEY)))) {
                resolved.put(variable, renderTemplate(stringValue(binding.get(SCHEDULE_FIXED_VALUE_KEY)), runtimeVariables));
                continue;
            }
            if (!isBlank(scheduledValue)) {
                resolved.put(variable, renderTemplate(String.valueOf(scheduledValue), runtimeVariables));
                continue;
            }
            String defaultValueSource = stringValue(field.get("defaultValueSource"));
            if ("system".equals(defaultValueSource)) {
                resolved.put(variable, resolveRule(stringValue(field.get("systemDefaultValue")), referenceDate));
            } else if ("fixed".equals(defaultValueSource)) {
                resolved.put(variable, renderTemplate(stringValue(field.get("defaultValue")), runtimeVariables));
            }
        }
        return resolved;
    }

    private static String resolveRule(String rule, LocalDate referenceDate) {
        return switch (rule) {
            case "current_year" -> String.valueOf(referenceDate.getYear());
            case "current_year_month" -> referenceDate.format(YEAR_MONTH);
            case "previous_year_month" -> referenceDate.minusMonths(1).format(YEAR_MONTH);
            case "current_date", "" -> referenceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            default -> referenceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        };
    }

    private static String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty() || variables == null || variables.isEmpty()) {
            return template == null ? "" : template;
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? matcher.group() : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
