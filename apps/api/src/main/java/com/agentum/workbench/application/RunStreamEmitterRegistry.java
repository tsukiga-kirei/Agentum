package com.agentum.workbench.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务运行 SSE 连接注册表：推进步骤时按 runId 解析最新连接，避免重连后仍向已关闭 emitter 推送。
 */
@Component
public class RunStreamEmitterRegistry {

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<SseEmitter, AtomicBoolean> openStates = new ConcurrentHashMap<>();

    public void register(UUID runId, SseEmitter emitter) {
        SseEmitter previous = emitters.put(runId, emitter);
        openStates.put(emitter, new AtomicBoolean(true));
        if (previous != null && previous != emitter) {
            // 刷新页面时旧连接由客户端 abort，仅标记关闭，避免 complete() 触发 Tomcat 非回收请求告警。
            markClosed(previous);
        }
    }

    public SseEmitter current(UUID runId) {
        return emitters.get(runId);
    }

    public AtomicBoolean openState(SseEmitter emitter) {
        return openStates.computeIfAbsent(emitter, ignored -> new AtomicBoolean(true));
    }

    public void markClosed(SseEmitter emitter) {
        AtomicBoolean open = openStates.get(emitter);
        if (open != null) {
            open.set(false);
        }
    }

    public void remove(UUID runId, SseEmitter emitter) {
        emitters.remove(runId, emitter);
        openStates.remove(emitter);
    }
}
