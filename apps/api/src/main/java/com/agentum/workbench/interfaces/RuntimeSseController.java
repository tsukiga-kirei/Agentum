package com.agentum.workbench.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.runtime.stream.RunStreamRelayService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务运行态控制器：SSE 进度流（Redis Stream 中继）与步进/中断/重新执行/恢复进度入口。
 *
 * <p>执行与浏览器连接完全解耦：advance 仅创建执行作业并投递 MQ，
 * 真实执行由 Worker 完成，进度通过 Redis Stream 回放，刷新/断线均可无感恢复。</p>
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench")
@Validated
public class RuntimeSseController {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSseController.class);

    private final WorkbenchAccess workbenchAccess;
    private final WorkbenchRuntimeService workbenchRuntimeService;
    private final RunStreamRelayService runStreamRelayService;

    public RuntimeSseController(
        WorkbenchAccess workbenchAccess,
        WorkbenchRuntimeService workbenchRuntimeService,
        RunStreamRelayService runStreamRelayService
    ) {
        this.workbenchAccess = workbenchAccess;
        this.workbenchRuntimeService = workbenchRuntimeService;
        this.runStreamRelayService = runStreamRelayService;
    }

    /**
     * 获取任务执行的 SSE 事件流（Redis Stream 中继）。
     *
     * @param lastEventId 断线续传起点（上次收到的事件 ID），优先级高于 replay
     * @param replay      true 时回放当前步骤已产生的全部事件（刷新/重进页面场景）
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @RequestParam(required = false) String lastEventId,
        @RequestParam(defaultValue = "false") boolean replay,
        @AuthenticationPrincipal CurrentUserPrincipal principal
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        log.info("用户建立 SSE 连接 tenantId={} userId={} runId={} replay={} lastEventId={} requestId={}",
            tenantId, principal.userId(), runId, replay, lastEventId, RequestIds.current());
        return runStreamRelayService.openStream(runId, lastEventId, replay);
    }

    /**
     * 推进执行下一步：创建执行作业并投递 MQ，立即返回含 activeJob 的任务详情。
     */
    @PostMapping("/runs/{runId}/advance")
    public ApiResponse<WorkbenchApi.RunDetail> advance(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        log.info("用户触发步进推进流程 tenantId={} userId={} runId={} requestId={}",
            tenantId, principal.userId(), runId, RequestIds.current(request));
        return ApiResponse.success(
            workbenchRuntimeService.advanceRun(tenantId, principal, runId),
            RequestIds.current(request)
        );
    }

    /**
     * 主动中断当前执行：节点置为 canceled 并清空该步骤全部运行数据。
     */
    @PostMapping("/runs/{runId}/interrupt")
    public ApiResponse<WorkbenchApi.RunDetail> interrupt(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        log.info("用户中断任务步骤 tenantId={} userId={} runId={} requestId={}",
            tenantId, principal.userId(), runId, RequestIds.current(request));
        return ApiResponse.success(
            workbenchRuntimeService.interruptRun(tenantId, principal, runId),
            RequestIds.current(request)
        );
    }

    /**
     * 主动「重新执行」：清空节点全部数据后从头重跑整个节点（用于中断后的整步重做）。
     */
    @PostMapping("/runs/{runId}/nodes/{nodeRunId}/restart")
    public ApiResponse<WorkbenchApi.RunDetail> restart(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.restartNode(tenantId, principal, runId, nodeRunId),
            RequestIds.current(request)
        );
    }

    /**
     * 被动「恢复进度」：保留已成功子智能体结果，仅重跑失败/未完成部分（用于异常失败后的最小损失恢复）。
     */
    @PostMapping("/runs/{runId}/nodes/{nodeRunId}/recover")
    public ApiResponse<WorkbenchApi.RunDetail> recover(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.recoverNode(tenantId, principal, runId, nodeRunId),
            RequestIds.current(request)
        );
    }
}
