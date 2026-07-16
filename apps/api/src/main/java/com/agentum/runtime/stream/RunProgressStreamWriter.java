package com.agentum.runtime.stream;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 运行进度 Stream 写入器：Worker 把所有 SSE 事件写入 run:{runId}:events，
 * API 侧 SSE 中继读取转发，使前端刷新/重连可回放当前步骤的全部进度（含流式累积文本）。
 *
 * <p>进度事件是热数据：MAXLEN 近似裁剪保证内存可控，事实源仍是 PostgreSQL。</p>
 */
@Component
public class RunProgressStreamWriter {

    private static final Logger log = LoggerFactory.getLogger(RunProgressStreamWriter.class);
    /**
     * 激活新作业与清空旧 Stream 必须原子完成，避免旧 Worker 在 reset 后又插入上一轮事件。
     */
    private static final DefaultRedisScript<Long> ACTIVATE_JOB_SCRIPT = new DefaultRedisScript<>("""
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
        if ARGV[3] == '1' then
          redis.call('DEL', KEYS[2])
        end
        return 1
        """, Long.class);
    /**
     * 事件写入与 activeJob 比较必须在同一条 Lua 内完成；GET 后再 XADD 仍有切换作业的竞态窗口。
     */
    private static final DefaultRedisScript<Long> APPEND_IF_ACTIVE_JOB_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then
          return 0
        end
        redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[2], '*', 'event', ARGV[3], 'payload', ARGV[4])
        return 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RuntimeExecutionProperties properties;
    private final ObjectMapper objectMapper;

    public RunProgressStreamWriter(
        StringRedisTemplate redisTemplate,
        RuntimeExecutionProperties properties,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条进度事件。payload 为 Map 时序列化为 JSON；为 String（如 [DONE]）时按原文写入。
     */
    public void append(UUID runId, String eventName, Object payload) {
        String payloadText = serializePayload(runId, eventName, payload);
        if (payloadText == null) {
            return;
        }
        try {
            byte[] key = streamKey(runId).getBytes(StandardCharsets.UTF_8);
            Map<byte[], byte[]> fields = new LinkedHashMap<>();
            fields.put("event".getBytes(StandardCharsets.UTF_8), eventName.getBytes(StandardCharsets.UTF_8));
            fields.put("payload".getBytes(StandardCharsets.UTF_8), payloadText.getBytes(StandardCharsets.UTF_8));
            MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord().in(key).ofMap(fields);
            RedisStreamCommands.XAddOptions options = RedisStreamCommands.XAddOptions
                .maxlen(properties.getRedis().getStreamMaxLen())
                .approximateTrimming(true);
            redisTemplate.execute((RedisCallback<RecordId>) connection -> connection.streamCommands().xAdd(record, options));
        } catch (Exception exception) {
            // 进度推送失败不阻断执行：事实源在 PostgreSQL，前端可通过详情接口兜底。
            log.warn("写入运行进度 Stream 失败 runId={} event={}", runId, eventName, exception);
        }
    }

    /**
     * 激活指定执行作业。手工恢复/重做会清空旧进度；自动重试可保留同一轮既有事件。
     */
    public void activateJob(UUID runId, UUID jobId, boolean resetStream) {
        long ttlMillis = Duration.ofSeconds(
            Math.max(600L, properties.getExecution().getNodeTimeoutSeconds() + 3600L)
        ).toMillis();
        try {
            redisTemplate.execute(
                ACTIVATE_JOB_SCRIPT,
                List.of(activeJobKey(runId), streamKey(runId)),
                jobId.toString(),
                Long.toString(ttlMillis),
                resetStream ? "1" : "0"
            );
        } catch (Exception exception) {
            // 没有代次栅栏就可能出现旧、新 Worker 混流，因此激活失败必须阻止命令继续发布。
            log.error("激活运行进度作业失败 runId={} jobId={}", runId, jobId, exception);
            throw new IllegalStateException("激活运行进度作业失败", exception);
        }
    }

    /**
     * 仅当前 Redis activeJob 仍等于 jobId 时追加事件，旧 Worker 的迟到 token 会被原子丢弃。
     */
    public boolean appendIfActiveJob(UUID runId, UUID jobId, String eventName, Object payload) {
        String payloadText = serializePayload(runId, eventName, payload);
        if (payloadText == null) {
            return false;
        }
        try {
            Long appended = redisTemplate.execute(
                APPEND_IF_ACTIVE_JOB_SCRIPT,
                List.of(activeJobKey(runId), streamKey(runId)),
                jobId.toString(),
                Long.toString(properties.getRedis().getStreamMaxLen()),
                eventName,
                payloadText
            );
            return Long.valueOf(1L).equals(appended);
        } catch (Exception exception) {
            // 进度推送失败不阻断模型执行；节点事实状态仍由 PostgreSQL 收敛。
            log.warn("写入当前作业进度失败 runId={} jobId={} event={}", runId, jobId, eventName, exception);
            return false;
        }
    }

    private String serializePayload(UUID runId, String eventName, Object payload) {
        if (payload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            log.warn("运行进度事件序列化失败 runId={} event={}", runId, eventName, exception);
            return null;
        }
    }

    private static String activeJobKey(UUID runId) {
        return "run:" + runId + ":active-job";
    }

    public static String streamKey(UUID runId) {
        return "run:" + runId + ":events";
    }
}
