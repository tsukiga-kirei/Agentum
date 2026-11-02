package com.agentum.runtime.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用本地 Docker Redis 验证 Lua 原子性和真实 Stream 裁剪，默认单元测试不依赖中间件。 */
@EnabledIfEnvironmentVariable(named = "AGENTUM_REDIS_INTEGRATION_TEST", matches = "true")
class RuntimeRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RuntimeExecutionProperties properties;
    private UUID runId;

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        properties = new RuntimeExecutionProperties();
        runId = UUID.randomUUID();
    }

    @AfterEach
    void cleanupOnlyThisTestRun() {
        redis.delete(List.of(RunProgressStreamWriter.streamKey(runId), RunProgressStreamWriter.startKey(runId),
            RunProgressStreamWriter.snapshotKey(runId), "run:" + runId + ":active-job", "run:" + runId + ":lease"));
        factory.destroy();
    }

    @Test
    void shouldKeepNodeIdentityAndReasoningAfterStreamIsTrimmed() {
        properties.getRedis().setStreamMaxLen(10);
        RunProgressStreamWriter writer = new RunProgressStreamWriter(redis, properties, new ObjectMapper());
        UUID job = UUID.randomUUID();
        writer.activateJob(runId, job, true);
        writer.appendIfActiveJob(runId, job, "node_started", Map.of("nodeRunId", "node-a", "nodeName", "采集", "nodeType", "agent"));
        writer.appendIfActiveJob(runId, job, "agent_streaming", Map.of("streamKind", "reasoning", "accumulatedContent", "已核对全部资料"));
        for (int index = 0; index < 400; index++) {
            writer.appendIfActiveJob(runId, job, "agent_streaming", Map.of("streamKind", "final_answer", "accumulatedContent", "完整报告" + index));
        }
        var retained = redis.opsForStream().range(RunProgressStreamWriter.streamKey(runId), Range.unbounded());
        assertThat(retained).noneMatch(record -> "node_started".equals(record.getValue().get("event")));
        assertThat(redis.opsForValue().get(RunProgressStreamWriter.startKey(runId))).contains("node-a");
        assertThat(redis.opsForHash().values(RunProgressStreamWriter.snapshotKey(runId)).toString())
            .contains("已核对全部资料").contains("完整报告399");
    }

    @Test
    void shouldRejectOldWorkerEventsAndClearOldSnapshotsWhenJobChanges() {
        RunProgressStreamWriter writer = new RunProgressStreamWriter(redis, properties, new ObjectMapper());
        UUID previous = UUID.randomUUID();
        writer.activateJob(runId, previous, true);
        writer.appendIfActiveJob(runId, previous, "node_started", Map.of("nodeName", "旧执行"));
        UUID current = UUID.randomUUID();
        writer.activateJob(runId, current, true);
        assertThat(writer.appendIfActiveJob(runId, previous, "agent_streaming", Map.of("accumulatedContent", "迟到内容"))).isFalse();
        assertThat(redis.opsForValue().get(RunProgressStreamWriter.startKey(runId))).isNull();
        assertThat(redis.opsForHash().size(RunProgressStreamWriter.snapshotKey(runId))).isZero();
        assertThat(writer.appendIfActiveJob(runId, current, "node_started", Map.of("nodeName", "新执行"))).isTrue();
    }

    @Test
    void shouldPreventOldWorkerFromRenewingOrDeletingNewLease() {
        RunExecutionLeaseService leases = new RunExecutionLeaseService(redis, properties);
        assertThat(leases.tryAcquire(runId, "previous")).isTrue();
        leases.forceRelease(runId);
        assertThat(leases.tryAcquire(runId, "current")).isTrue();
        leases.renew(runId, "previous");
        leases.release(runId, "previous");
        assertThat(redis.opsForValue().get("run:" + runId + ":lease")).isEqualTo("current");
        leases.release(runId, "current");
        assertThat(leases.hasActiveLease(runId)).isFalse();
    }
}
