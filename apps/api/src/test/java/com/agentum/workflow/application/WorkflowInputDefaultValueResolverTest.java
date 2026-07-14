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
            "systemDefaultValue", "previous_month",
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
}
