package com.agentum.runtime.stream;

import com.agentum.shared.api.ClientDisconnectSupport;
import com.agentum.shared.logging.LogContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 中继：浏览器连接不再绑定执行线程，而是订阅 Redis Stream 并转发。
 *
 * <p>支持 replay（重进页面回放当前步骤全部事件）与 lastEventId（断线续传），
 * 任意 API 实例都可建立连接，执行进程崩溃不影响已落库与已写入 Stream 的进度。</p>
 */
@Component
public class RunStreamRelayService {

    private static final Logger log = LoggerFactory.getLogger(RunStreamRelayService.class);
    private static final long POLL_INTERVAL_MILLIS = 300;
    private static final int BATCH_SIZE = 200;

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final ExecutorService relayExecutor;

    public RunStreamRelayService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        AtomicLong counter = new AtomicLong();
        this.relayExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "agentum-sse-relay-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 建立 SSE 连接并启动中继循环。
     *
     * @param lastEventId 断线续传起点（Redis Stream ID），为空时按 replay 决定起点
     * @param replay      true 时从头回放 Stream 中保留的事件（刷新/重进页面场景）
     */
    public SseEmitter openStream(UUID runId, String lastEventId, boolean replay) {
        // 0 表示不设超时，长任务执行期间由客户端断开或 [DONE] 终止。
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        Map<String, String> logContext = LogContext.snapshot();
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ex -> {
            open.set(false);
            if (!ClientDisconnectSupport.isClientDisconnect(ex)) {
                try (LogContext.Scope ignored = LogContext.openSnapshot(logContext)) {
                    log.warn("SSE 中继连接异常 runId={} message={}", runId, ex.getMessage());
                }
            }
        });
        relayExecutor.submit(() -> {
            // 中继线程独立于 Servlet 线程，恢复认证后的租户上下文才能把连接异常写入正确文件。
            try (LogContext.Scope ignored = LogContext.openSnapshot(logContext)) {
                relayLoop(runId, lastEventId, replay, emitter, open);
            }
        });
        return emitter;
    }

    private void relayLoop(UUID runId, String lastEventId, boolean replay, SseEmitter emitter, AtomicBoolean open) {
        String streamKey = RunProgressStreamWriter.streamKey(runId);
        String cursor = resolveStartCursor(streamKey, lastEventId, replay);

        if (!sendConnectedEvent(runId, emitter, open, cursor)) {
            return;
        }

        try {
            while (open.get()) {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                    streamKey,
                    Range.rightUnbounded(Range.Bound.exclusive(cursor)),
                    Limit.limit().count(BATCH_SIZE)
                );
                if (records == null || records.isEmpty()) {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    cursor = record.getId().getValue();
                    Map<Object, Object> fields = record.getValue();
                    String eventName = stringField(fields.get("event"));
                    String payload = stringField(fields.get("payload"));
                    if (eventName.isBlank()) {
                        continue;
                    }
                    if (!sendEvent(emitter, open, cursor, eventName, payload)) {
                        return;
                    }
                    if ("message".equals(eventName) && "[DONE]".equals(payload)) {
                        completeQuietly(emitter, open);
                        return;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.warn("SSE 中继循环异常 runId={} message={}", runId, exception.getMessage());
        } finally {
            completeQuietly(emitter, open);
        }
    }

    private String resolveStartCursor(String streamKey, String lastEventId, boolean replay) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            return lastEventId.trim();
        }
        if (replay) {
            return "0-0";
        }
        // 默认只接收新事件：从当前最新条目之后开始。
        List<MapRecord<String, Object, Object>> latest = redisTemplate.opsForStream()
            .reverseRange(streamKey, Range.unbounded(), Limit.limit().count(1));
        if (latest == null || latest.isEmpty()) {
            return "0-0";
        }
        return latest.get(0).getId().getValue();
    }

    private boolean sendConnectedEvent(UUID runId, SseEmitter emitter, AtomicBoolean open, String cursor) {
        return sendEvent(emitter, open, null, "connected", "{\"runId\":\"" + runId
            + "\",\"currentState\":\"connected\",\"mode\":\"async\",\"lastEventId\":\"" + cursor
            + "\",\"timestamp\":\"" + clock.instant() + "\"}");
    }

    private boolean sendEvent(SseEmitter emitter, AtomicBoolean open, String eventId, String eventName, String payload) {
        if (!open.get()) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName);
            if (eventId != null) {
                builder.id(eventId);
            }
            // payload 已是 JSON 文本或 [DONE]，按原文转发，避免二次序列化引入引号包裹。
            builder.data(payload, MediaType.TEXT_PLAIN);
            emitter.send(builder);
            return true;
        } catch (Exception exception) {
            open.set(false);
            if (!ClientDisconnectSupport.isClientDisconnect(exception)) {
                log.debug("SSE 中继推送失败 event={} message={}", eventName, exception.getMessage());
            }
            return false;
        }
    }

    private static void completeQuietly(SseEmitter emitter, AtomicBoolean open) {
        open.set(false);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开时 complete 可能抛错，忽略即可。
        }
    }

    private static String stringField(Object value) {
        return value == null ? "" : value.toString();
    }
}
