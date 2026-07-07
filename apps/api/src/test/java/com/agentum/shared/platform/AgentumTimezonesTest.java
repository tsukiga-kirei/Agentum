package com.agentum.shared.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

class AgentumTimezonesTest {

    private final TimeZone originalTimeZone = TimeZone.getDefault();
    private final ZoneId originalBusinessZone = AgentumTimezones.businessZone();

    @AfterEach
    void restoreTimezone() {
        AgentumTimezones.apply(originalBusinessZone.getId());
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void shouldResolveDailyNineAmInConfiguredBusinessTimezone() {
        AgentumTimezones.apply("Asia/Shanghai");

        CronExpression cron = CronExpression.parse("0 0 9 * * *");
        Instant base = Instant.parse("2026-07-06T00:30:00Z");
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(base, AgentumTimezones.businessZone()));

        assertThat(next).isNotNull();
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(next.getMinute()).isZero();
        assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-07-06T01:00:00Z"));
    }
}
