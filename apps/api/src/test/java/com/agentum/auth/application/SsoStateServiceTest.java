package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SsoStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-05T08:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    @Test
    void shouldCreateAndParseSignedState() {
        SsoStateService service = new SsoStateService(Clock.fixed(NOW, ZoneOffset.UTC), "sso-test-secret", Duration.ofMinutes(5));

        String state = service.createState(TENANT_ID, PROVIDER_ID, "business");

        SsoState parsed = service.parseState(state);

        assertThat(parsed.tenantId()).isEqualTo(TENANT_ID);
        assertThat(parsed.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(parsed.portal()).isEqualTo("business");
        assertThat(parsed.nonce()).isNotBlank();
        assertThat(parsed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void shouldRejectTamperedState() {
        SsoStateService service = new SsoStateService(Clock.fixed(NOW, ZoneOffset.UTC), "sso-test-secret", Duration.ofMinutes(5));

        String state = service.createState(TENANT_ID, PROVIDER_ID, "business");
        String tampered = state.substring(0, state.length() - 2) + "xx";

        assertThatThrownBy(() -> service.parseState(tampered))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_SSO_STATE_INVALID");
    }
}
