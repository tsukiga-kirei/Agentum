package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-08T08:00:00Z");
    private static final String SECRET = "test-secret-with-enough-length";

    @Test
    void shouldCreateAndParseTenantScopedToken() {
        AuthTokenService tokenService = tokenServiceAt(NOW, Duration.ofHours(8));
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        String token = tokenService.createToken(new CurrentUserPrincipal(
            userId,
            "operator",
            tenantId,
            "executor",
            "business",
            "默认空间"
        ));

        AuthTokenClaims claims = tokenService.parse(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.role()).isEqualTo("executor");
        assertThat(claims.portal()).isEqualTo("business");
        assertThat(claims.spaceCode()).isEqualTo("默认空间");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
    }

    @Test
    void shouldRejectExpiredToken() {
        AuthTokenService issuer = tokenServiceAt(NOW, Duration.ofMinutes(30));
        AuthTokenService parser = tokenServiceAt(NOW.plus(Duration.ofHours(1)), Duration.ofMinutes(30));

        String token = issuer.createToken(new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "admin",
            null,
            "system_admin",
            "system_admin",
            "system"
        ));

        assertThatThrownBy(() -> parser.parse(token))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_TOKEN_EXPIRED");
    }

    @Test
    void shouldRejectTamperedToken() {
        AuthTokenService tokenService = tokenServiceAt(NOW, Duration.ofHours(8));

        String token = tokenService.createToken(new CurrentUserPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "admin",
            null,
            "system_admin",
            "system_admin",
            "system"
        ));

        String tamperedToken = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> tokenService.parse(tamperedToken))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_TOKEN_INVALID");
    }

    private static AuthTokenService tokenServiceAt(Instant instant, Duration ttl) {
        return new AuthTokenService(new ObjectMapper(), Clock.fixed(instant, ZoneOffset.UTC), SECRET, ttl);
    }
}
