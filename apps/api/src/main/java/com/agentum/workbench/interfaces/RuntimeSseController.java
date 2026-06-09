package com.agentum.workbench.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ClientDisconnectSupport;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务处理流式推送及步进控制控制器。
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench")
@Validated
public class RuntimeSseController {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSseController.class);

    private final WorkbenchAccess workbenchAccess;
    private final WorkbenchRuntimeService workbenchRuntimeService;
    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public RuntimeSseController(
        WorkbenchAccess workbenchAccess,
        WorkbenchRuntimeService workbenchRuntimeService
    ) {
        this.workbenchAccess = workbenchAccess;
        this.workbenchRuntimeService = workbenchRuntimeService;
    }

    /**
     * 获取任务执行的 SSE 事件流。
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        log.info("用户建立 SSE 连接 tenantId={} userId={} runId={} requestId={}",
            tenantId, principal.userId(), runId, RequestIds.current());

        // 同一 run 只允许保留最新连接；旧连接由客户端断开或 complete 自然结束。
        SseEmitter oldEmitter = emitters.put(runId, createEmitter(runId));
        if (oldEmitter != null) {
            log.debug("替换已有 SSE 连接 runId={}", runId);
            closeEmitterQuietly(oldEmitter);
        }

        SseEmitter emitter = emitters.get(runId);
        sendConnectedEvent(runId, emitter);
        return emitter;
    }

    private SseEmitter createEmitter(UUID runId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成 runId={}", runId);
            emitters.remove(runId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时 runId={}", runId);
            emitters.remove(runId, emitter);
        });
        emitter.onError(ex -> {
            if (ClientDisconnectSupport.isClientDisconnect(ex)) {
                log.debug("SSE 客户端断开 runId={}", runId);
            } else {
                log.warn("SSE 连接异常 runId={} message={}", runId, ex.getMessage());
            }
            emitters.remove(runId, emitter);
        });
        return emitter;
    }

    private void sendConnectedEvent(UUID runId, SseEmitter emitter) {
        if (emitter == null || emitters.get(runId) != emitter) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of(
                    "runId", runId.toString(),
                    "currentState", "connected",
                    "timestamp", java.time.Instant.now().toString()
                ), MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            emitters.remove(runId, emitter);
            if (ClientDisconnectSupport.isClientDisconnect(exception)) {
                log.debug("SSE 初始推送时客户端已断开 runId={}", runId);
            } else {
                log.warn("推送初始 SSE 消息失败 runId={}", runId, exception);
            }
            closeEmitterQuietly(emitter);
        }
    }

    private static void closeEmitterQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开时 complete 也可能失败，忽略即可。
        }
    }

    /**
     * 手动触发推进下一步执行。
     */
    @PostMapping("/runs/{runId}/advance")
    public ApiResponse<WorkbenchApi.RunDetail> advance(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        log.info("用户触发人工步进推进流程 tenantId={} userId={} runId={} requestId={}", 
            tenantId, principal.userId(), runId, RequestIds.current(request));

        // 异步推进单个节点，并通过对应 SSE emitter 推送事件；若前端尚未建立 SSE，仍继续执行节点逻辑。
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) {
            log.warn("推进步骤时未找到 SSE 连接 runId={} requestId={}", runId, RequestIds.current(request));
        }
        workbenchRuntimeService.advanceSingleStep(tenantId, principal, runId, emitter);

        // 立即返回当前节点状态快照
        return ApiResponse.success(
            workbenchRuntimeService.getRunDetail(tenantId, principal, runId), 
            RequestIds.current(request)
        );
    }
}
