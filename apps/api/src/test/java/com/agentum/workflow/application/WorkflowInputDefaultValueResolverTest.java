package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.shared.platform.AgentumTimezones;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowInputDefaultValueResolverTest {

    @BeforeEach
    void useBusinessTimezone() {
        AgentumTimezones.apply("Asia/Shanghai");
    }

    @Test
    void shouldResolvePreviousMonthAcrossYearBoundary() {
        Map<String, Object> config = Map.of("inputFields", List.of(Map.of(
            "variable", "report_month",
            "defaultValueSource", "system",
            "systemDefaultValue", "previous_year_month",
            "allowManualOverride", false
        )));

        Map<String, Object> resolved = WorkflowInputDefaultValueResolver.apply(
            config,
            Map.of(),
            Instant.parse("2026-01-01T01:00:00Z"),
            true
        );

        assertThat(resolved).containsEntry("report_month", "2025-12");
    }

    @Test
    void shouldKeepManualOverrideButProtectLockedSystemValue() {
        Map<String, Object> editableConfig = Map.of("inputFields", List.of(Map.of(
            "variable", "report_date",
            "defaultValueSource", "system",
            "systemDefaultValue", "current_date",
            "allowManualOverride", true
        )));
        Map<String, Object> lockedConfig = Map.of("inputFields", List.of(Map.of(
            "variable", "report_date",
            "defaultValueSource", "system",
            "systemDefaultValue", "current_date",
            "allowManualOverride", false
        )));
        Instant reference = Instant.parse("2026-07-14T01:00:00Z");

        assertThat(WorkflowInputDefaultValueResolver.apply(editableConfig, Map.of("report_date", "2026-07-01"), reference, false))
            .containsEntry("report_date", "2026-07-01");
        assertThat(WorkflowInputDefaultValueResolver.apply(lockedConfig, Map.of("report_date", "2099-01-01"), reference, false))
            .containsEntry("report_date", "2026-07-14");
    }

    @Test
    void shouldResolveCurrentYear() {
        Map<String, Object> config = Map.of("inputFields", List.of(Map.of(
            "variable", "report_year",
            "defaultValueSource", "system",
            "systemDefaultValue", "current_year"
        )));

        Map<String, Object> resolved = WorkflowInputDefaultValueResolver.apply(
            config,
            Map.of(),
            Instant.parse("2026-07-14T01:00:00Z"),
            true
        );

        assertThat(resolved).containsEntry("report_year", "2026");
    }

    @Test
    void shouldResolveScheduleTextTemplateForEveryRun() {
        Map<String, Object> config = Map.of("inputFields", List.of(Map.of(
            "variable", "report_title",
            "defaultValueSource", "fixed",
            "defaultValue", "{{current_year}}年{{current_month}}月报告"
        )));

        Map<String, Object> resolved = WorkflowInputDefaultValueResolver.applyScheduledOverrides(
            config,
            Map.of("report_title", "批次{{runNumber}}-{{current_day_padded}}"),
            Instant.parse("2026-07-14T01:00:00Z"),
            Map.of("runNumber", "RUN-001", "current_day_padded", "14")
        );

        assertThat(resolved).containsEntry("report_title", "批次RUN-001-14");
    }

    @Test
    void shouldAllowScheduleToOverrideWorkflowDateRuleOrUseFixedDate() {
        Map<String, Object> config = Map.of("inputFields", List.of(Map.of(
            "variable", "report_date",
            "defaultValueSource", "system",
            "systemDefaultValue", "current_date"
        )));
        Instant reference = Instant.parse("2026-07-14T01:00:00Z");

        Map<String, Object> dynamic = WorkflowInputDefaultValueResolver.applyScheduledOverrides(
            config,
            Map.of("report_date", Map.of(
                WorkflowInputDefaultValueResolver.SCHEDULE_VALUE_TYPE_KEY,
                WorkflowInputDefaultValueResolver.SCHEDULE_SYSTEM_VALUE_TYPE,
                WorkflowInputDefaultValueResolver.SCHEDULE_SYSTEM_RULE_KEY,
                "previous_year_month"
            )),
            reference,
            Map.of()
        );
        Map<String, Object> fixed = WorkflowInputDefaultValueResolver.applyScheduledOverrides(
            config,
            Map.of("report_date", Map.of(
                WorkflowInputDefaultValueResolver.SCHEDULE_VALUE_TYPE_KEY,
                WorkflowInputDefaultValueResolver.SCHEDULE_FIXED_VALUE_TYPE,
                WorkflowInputDefaultValueResolver.SCHEDULE_FIXED_VALUE_KEY,
                "2026-06-30"
            )),
            reference,
            Map.of()
        );

        assertThat(dynamic).containsEntry("report_date", "2026-06");
        assertThat(fixed).containsEntry("report_date", "2026-06-30");
    }
}
