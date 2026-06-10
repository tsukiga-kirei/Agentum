package com.agentum.runtime.cancel;

import com.agentum.shared.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的取消信号与执行截止时间实现：取代原内存 RunExecutionCancellationRegistry，
 * 保证多实例/Worker 场景下中断与超时对所有执行进程一致可见。
 */
@Component
public class RedisRunCancellationGuard implements RunCancellationGuard {

    /** 取消信号保留 1 小时：足够覆盖在途执行的退出窗口，又不会永久残留。 */
    private static final Duration CANCEL_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public RedisRunCancellationGuard(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public void requestCancel(UUID runId) {
        if (runId != null) {
            redisTemplate.opsForValue().set(cancelKey(runId), "1", CANCEL_TTL);
        }
    }

    @Override
    public void clearCancel(UUID runId) {
        if (runId != null) {
            redisTemplate.delete(cancelKey(runId));
        }
    }

    @Override
    public boolean isCancelled(UUID runId) {
        return runId != null && Boolean.TRUE.equals(redisTemplate.hasKey(cancelKey(runId)));
    }

    @Override
    public void markDeadline(UUID runId, long deadlineEpochMillis) {
        if (runId == null) {
            return;
        }
        long ttlMillis = Math.max(1000L, deadlineEpochMillis - clock.millis() + Duration.ofMinutes(10).toMillis());
        redisTemplate.opsForValue().set(deadlineKey(runId), Long.toString(deadlineEpochMillis), Duration.ofMillis(ttlMillis));
    }

    @Override
    public void assertExecutable(UUID runId) {
        if (runId == null) {
            return;
        }
        if (isCancelled(runId)) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_CANCELLED", "任务已中断");
        }
        String deadline = redisTemplate.opsForValue().get(deadlineKey(runId));
        if (deadline != null) {
            try {
                if (clock.millis() > Long.parseLong(deadline)) {
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "WORKBENCH_NODE_EXECUTION_TIMEOUT",
                        "节点执行超过最大时长限制，已自动中止，请重新执行"
                    );
                }
            } catch (NumberFormatException ignored) {
                // 截止时间格式异常时不阻断执行，仅依赖取消信号与回收器兜底。
            }
        }
    }

    private static String cancelKey(UUID runId) {
        return "run:" + runId + ":cancel";
    }

    private static String deadlineKey(UUID runId) {
        return "run:" + runId + ":deadline";
    }
}
