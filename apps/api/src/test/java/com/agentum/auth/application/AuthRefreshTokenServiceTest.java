package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.auth.domain.AuthRefreshTokenEntity;
import com.agentum.auth.infrastructure.AuthRefreshTokenRepository;
import com.agentum.shared.api.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthRefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-22T08:00:00Z");

    @Test
    void shouldStoreOnlyHashWhenIssuingRefreshToken() {
        AuthRefreshTokenRepository repository = mock(AuthRefreshTokenRepository.class);
        SecureRandom random = deterministicRandom();
        AuthRefreshTokenService service = service(repository, random);
        ArgumentCaptor<AuthRefreshTokenEntity> captor = ArgumentCaptor.forClass(AuthRefreshTokenEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedRefreshToken issued = service.issue(UUID.randomUUID(), UUID.randomUUID());

        assertThat(issued.value()).isNotBlank();
        assertThat(captor.getValue().getTokenHash()).hasSize(64).doesNotContain(issued.value());
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void shouldRotateTokenOnlyOnce() {
        AuthRefreshTokenRepository repository = mock(AuthRefreshTokenRepository.class);
        AuthRefreshTokenService service = service(repository, deterministicRandom());
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        AuthRefreshTokenEntity stored = AuthRefreshTokenEntity.create(userId, roleId,
            "7f8f6f7f72133f4b1f6a88690e8fdf4d6e519c880a48e34c9d4d3efb991a3079", NOW, NOW.plusSeconds(60));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RotatedRefreshToken rotated = service.rotate("old-refresh-token");

        assertThat(rotated.userId()).isEqualTo(userId);
        assertThat(rotated.roleAssignmentId()).isEqualTo(roleId);
        assertThat(stored.getRevokedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.rotate("old-refresh-token"))
            .isInstanceOf(ApiException.class)
            .extracting("code")
            .isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    private static AuthRefreshTokenService service(AuthRefreshTokenRepository repository, SecureRandom random) {
        return new AuthRefreshTokenService(repository, random, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    }

    private static SecureRandom deterministicRandom() {
        SecureRandom random = mock(SecureRandom.class);
        doAnswer(invocation -> {
            byte[] target = invocation.getArgument(0);
            for (int index = 0; index < target.length; index++) target[index] = (byte) (index + 1);
            return null;
        }).when(random).nextBytes(any(byte[].class));
        return random;
    }
}
