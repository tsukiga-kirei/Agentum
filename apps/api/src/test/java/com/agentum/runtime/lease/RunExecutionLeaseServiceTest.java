package com.agentum.runtime.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 执行租约互斥语义测试：同一 runId 只能被一个执行进程持有，
 * 续期与释放只能作用于自己持有的租约，避免误删/误延长其他进程的互斥窗口。
 */
class RunExecutionLeaseServiceTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String LEASE_KEY = "run:" + RUN_ID + ":lease";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RunExecutionLeaseService leaseService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        leaseService = new RunExecutionLeaseService(redisTemplate, new RuntimeExecutionProperties());
    }

    @Test
    void shouldAcquireLeaseWhenKeyAbsent() {
        when(valueOperations.setIfAbsent(eq(LEASE_KEY), eq("worker-a"), any(Duration.class))).thenReturn(true);

        assertThat(leaseService.tryAcquire(RUN_ID, "worker-a")).isTrue();
    }

    @Test
    void shouldRejectAcquireWhenLeaseHeldByOther() {
        when(valueOperations.setIfAbsent(eq(LEASE_KEY), eq("worker-b"), any(Duration.class))).thenReturn(false);

        assertThat(leaseService.tryAcquire(RUN_ID, "worker-b")).isFalse();
    }

    @Test
    void shouldRenewOnlyWhenHolderMatches() {
        when(valueOperations.get(LEASE_KEY)).thenReturn("worker-a");

        leaseService.renew(RUN_ID, "worker-a");

        verify(redisTemplate).expire(eq(LEASE_KEY), any(Duration.class));
    }

    @Test
    void shouldNotRenewLeaseHeldByOther() {
        when(valueOperations.get(LEASE_KEY)).thenReturn("worker-other");

        leaseService.renew(RUN_ID, "worker-a");

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldReleaseOnlyOwnLease() {
        when(valueOperations.get(LEASE_KEY)).thenReturn("worker-other");

        leaseService.release(RUN_ID, "worker-a");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void shouldReportActiveLease() {
        when(redisTemplate.hasKey(LEASE_KEY)).thenReturn(true);

        assertThat(leaseService.hasActiveLease(RUN_ID)).isTrue();
    }
}
