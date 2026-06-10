package com.agentum.runtime.lease;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行租约：同一 runId 同一时刻最多一个执行进程持有租约（SET NX）。
 *
 * <p>Worker 执行期间周期性续期；进程崩溃后租约随 TTL 过期，
 * StaleExecutionReaper 据此判定「节点 running 但执行已死亡」并标记失败。</p>
 */
@Component
public class RunExecutionLeaseService {

    private final StringRedisTemplate redisTemplate;
    private final RuntimeExecutionProperties properties;

    public RunExecutionLeaseService(StringRedisTemplate redisTemplate, RuntimeExecutionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean tryAcquire(UUID runId, String holder) {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(leaseKey(runId), holder, Duration.ofSeconds(properties.getRedis().getLeaseTtlSeconds()));
        return Boolean.TRUE.equals(acquired);
    }

    public void renew(UUID runId, String holder) {
        // 仅续期自己持有的租约，避免误延长其他进程的互斥窗口。
        if (Objects.equals(holder, redisTemplate.opsForValue().get(leaseKey(runId)))) {
            redisTemplate.expire(leaseKey(runId), Duration.ofSeconds(properties.getRedis().getLeaseTtlSeconds()));
        }
    }

    public void release(UUID runId, String holder) {
        if (Objects.equals(holder, redisTemplate.opsForValue().get(leaseKey(runId)))) {
            redisTemplate.delete(leaseKey(runId));
        }
    }

    public boolean hasActiveLease(UUID runId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(leaseKey(runId)));
    }

    /** 恢复进度等兜底路径：强制释放僵死租约，避免 queued 作业永久占锁。 */
    public void forceRelease(UUID runId) {
        redisTemplate.delete(leaseKey(runId));
    }

    private static String leaseKey(UUID runId) {
        return "run:" + runId + ":lease";
    }
}
