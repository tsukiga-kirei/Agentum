package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.shared.platform.AgentumTimezones;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeSystemVariablesTest {

    @BeforeEach
    void useBusinessTimezone() {
        AgentumTimezones.apply("Asia/Shanghai");
    }

    @Test
    void shouldExposeChineseDateAndWeekdayInBusinessTimezone() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-29T02:00:00Z"), ZoneOffset.UTC);
        WorkflowRunEntity run = WorkflowRunEntity.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "测试运行",
            "测试流程",
            UUID.randomUUID(),
            3,
            "20260629-ABCDEF12",
            clock.instant()
        );

        Map<String, Object> variables = WorkflowRuntimeSystemVariables.from(run, clock);

        assertThat(variables)
            .containsEntry("runNumber", "20260629-ABCDEF12")
            .containsEntry("date", "2026-06-29")
            .containsEntry("dateCompact", "20260629")
            .containsEntry("current_date_cn", "2026 年 6 月 29 日")
            .containsEntry("current_weekday", "星期一")
            .containsEntry("current_month", "6")
            .containsEntry("month", "06");
    }
}
