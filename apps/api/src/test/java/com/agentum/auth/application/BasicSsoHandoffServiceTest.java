package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class BasicSsoHandoffServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> operations;
    private ObjectMapper objectMapper;
    private BasicSsoHandoffService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        objectMapper = new ObjectMapper();
        service = new BasicSsoHandoffService(redisTemplate, objectMapper, new SecureRandom(), Duration.ofSeconds(60));
    }

    @Test
    void shouldCreateShortLivedHandoffCodeWithoutPersistingCredentials() {
        String code = service.create(handoff());

        assertThat(code).matches("[A-Za-z0-9_-]{40,}");
        verify(operations).set(startsWith(BasicSsoHandoffService.KEY_PREFIX), any(String.class), any(Duration.class));
    }

    @Test
    void shouldConsumeHandoffOnlyOnce() throws Exception {
        String code = "a".repeat(43);
        when(operations.getAndDelete(BasicSsoHandoffService.key(code)))
            .thenReturn(objectMapper.writeValueAsString(handoff()));

        assertThat(service.consume(code)).isEqualTo(handoff());
        verify(operations).getAndDelete(BasicSsoHandoffService.key(code));
    }

    @Test
    void shouldRejectExpiredOrMalformedHandoffCode() {
        assertThatThrownBy(() -> service.consume("expired-code"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("AUTH_SSO_HANDOFF_INVALID");
    }

    private static BasicSsoHandoff handoff() {
        return new BasicSsoHandoff(TENANT_ID, PROVIDER_ID, "operator", "business");
    }
}
