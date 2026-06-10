package com.agentum.runtime.stream;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        String payloadText;
        if (payload instanceof String text) {
            payloadText = text;
        } else {
            try {
                payloadText = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException exception) {
                log.warn("运行进度事件序列化失败 runId={} event={}", runId, eventName, exception);
                return;
            }
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
     * 清空运行进度 Stream：每次新作业入队前调用，保证回放内容只属于当前步骤的当前尝试。
     */
    public void reset(UUID runId) {
        try {
            redisTemplate.delete(streamKey(runId));
        } catch (Exception exception) {
            log.warn("清空运行进度 Stream 失败 runId={}", runId, exception);
        }
    }

    public static String streamKey(UUID runId) {
        return "run:" + runId + ":events";
    }
}
