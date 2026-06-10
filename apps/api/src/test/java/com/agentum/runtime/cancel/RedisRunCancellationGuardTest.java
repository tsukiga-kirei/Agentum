package com.agentum.runtime.cancel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.shared.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 取消信号与执行截止时间测试：用户中断抛 RUN_CANCELLED、超过截止时间抛超时，
 * 两种异常码区分「重新执行」与「恢复进度」的前端语义。
 */
class RedisRunCancellationGuardTest {

    private static final Instant NOW = Instant.parse("2026-06-10T08:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final String CANCEL_KEY = "run:" + RUN_ID + ":cancel";
    private static final String DEADLINE_KEY = "run:" + RUN_ID + ":deadline";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisRunCancellationGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new RedisRunCancellationGuard(redisTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldThrowRunCancelledWhenCancelFlagPresent() {
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(true);

        assertThatThrownBy(() -> guard.assertExecutable(RUN_ID))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("code", "RUN_CANCELLED");
    }

    @Test
    void shouldThrowTimeoutWhenDeadlinePassed() {
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false);
        when(valueOperations.get(DEADLINE_KEY)).thenReturn(Long.toString(NOW.minusSeconds(1).toEpochMilli()));

        assertThatThrownBy(() -> guard.assertExecutable(RUN_ID))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("code", "WORKBENCH_NODE_EXECUTION_TIMEOUT");
    }

    @Test
    void shouldPassWhenNotCancelledAndDeadlineNotReached() {
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false);
        when(valueOperations.get(DEADLINE_KEY)).thenReturn(Long.toString(NOW.plusSeconds(600).toEpochMilli()));

        assertThatCode(() -> guard.assertExecutable(RUN_ID)).doesNotThrowAnyException();
    }

    @Test
    void shouldIgnoreMalformedDeadlineValue() {
        // 截止时间格式异常时不阻断执行，由取消信号与回收器兜底。
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false);
        when(valueOperations.get(DEADLINE_KEY)).thenReturn("not-a-number");

        assertThatCode(() -> guard.assertExecutable(RUN_ID)).doesNotThrowAnyException();
    }
}
