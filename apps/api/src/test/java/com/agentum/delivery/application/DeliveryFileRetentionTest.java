package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryFileRetentionTest {

    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");

    @Test
    void shouldNotWriteExpiryForPermanentRetention() {
        DeliveryFileRetention retention = DeliveryFileRetention.from(Map.of("retentionPolicy", "permanent"), NOW);
        Map<String, Object> result = new LinkedHashMap<>();

        retention.writeTo(result);

        assertThat(result)
            .containsEntry("retentionPolicy", "permanent")
            .doesNotContainKeys("retentionDays", "expiresAt");
    }

    @Test
    void shouldKeepLegacyRetentionDaysAsDaysPolicy() {
        DeliveryFileRetention retention = DeliveryFileRetention.from(Map.of("retentionDays", 30), NOW);
        Map<String, Object> result = new LinkedHashMap<>();

        retention.writeTo(result);

        assertThat(result)
            .containsEntry("retentionPolicy", "days")
            .containsEntry("retentionDays", 30)
            .containsEntry("expiresAt", "2026-08-14T02:00:00Z");
    }
}
