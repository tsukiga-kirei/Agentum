package com.agentum.runtime.lease;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);
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
        redisTemplate.execute(RENEW, List.of(leaseKey(runId)), holder, Long.toString(Duration.ofSeconds(properties.getRedis().getLeaseTtlSeconds()).toMillis()));
    }

    public void release(UUID runId, String holder) {
        // 比较持有人和删除必须原子完成，防止旧 Worker 删除重启后新持有人的租约。
        redisTemplate.execute(RELEASE, List.of(leaseKey(runId)), holder);
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
