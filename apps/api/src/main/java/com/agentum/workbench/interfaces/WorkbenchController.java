package com.agentum.workbench.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workbench.application.WorkbenchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务工作台 REST 入口。
 *
 * <p>所有接口都通过 {@link WorkbenchAccess} 校验登录主体是否属于目标租户；
 * 系统管理员可跨租户访问，业务用户与租户管理员只能访问自己的租户上下文。</p>
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench")
@Validated
public class WorkbenchController {

    private final WorkbenchAccess workbenchAccess;
    private final WorkbenchService workbenchService;
    private final WorkbenchRuntimeService workbenchRuntimeService;

    public WorkbenchController(
        WorkbenchAccess workbenchAccess,
        WorkbenchService workbenchService,
        WorkbenchRuntimeService workbenchRuntimeService
    ) {
        this.workbenchAccess = workbenchAccess;
        this.workbenchService = workbenchService;
        this.workbenchRuntimeService = workbenchRuntimeService;
    }

    @GetMapping("/summary")
    public ApiResponse<WorkbenchApi.WorkbenchSummary> summary(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchService.getSummary(tenantId, principal), RequestIds.current(request));
    }

    @GetMapping("/available-workflows")
    public ApiResponse<PageResponse<WorkbenchApi.AvailableWorkflowRow>> listAvailableWorkflows(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.listLaunchableWorkflows(tenantId, principal, keyword, page, size, sort),
            RequestIds.current(request)
        );
    }

    @GetMapping("/available-workflows/{workflowId}/preview")
    public ApiResponse<WorkbenchApi.AvailableWorkflowPreview> getAvailableWorkflowPreview(
        @PathVariable UUID tenantId,
        @PathVariable UUID workflowId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.getAvailableWorkflowPreview(tenantId, principal, workflowId),
            RequestIds.current(request)
        );
    }

    @PostMapping("/runs")
    public ApiResponse<WorkbenchApi.RunDetail> createRun(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @org.springframework.web.bind.annotation.RequestBody WorkbenchApi.CreateRunRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchRuntimeService.createRun(tenantId, principal, body), RequestIds.current(request));
    }

    @GetMapping("/active-runs")
    public ApiResponse<PageResponse<WorkbenchApi.TaskRunRow>> listActiveRuns(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "") String state,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.listActiveRuns(tenantId, principal, keyword, state, page, size, sort),
            RequestIds.current(request)
        );
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<WorkbenchApi.TaskRunRow>> listRuns(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(
            workbenchRuntimeService.listRuns(tenantId, principal, keyword, page, size, sort),
            RequestIds.current(request)
        );
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<WorkbenchApi.RunDetail> getRun(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchRuntimeService.getRunDetail(tenantId, principal, runId), RequestIds.current(request));
    }

    @PostMapping("/runs/{runId}/save")
    public ApiResponse<WorkbenchApi.RunDetail> saveRun(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @org.springframework.web.bind.annotation.RequestBody(required = false) WorkbenchApi.SaveRunRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchRuntimeService.saveRun(tenantId, principal, runId, body), RequestIds.current(request));
    }

    @DeleteMapping("/runs/{runId}")
    public ApiResponse<Void> deleteRun(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        workbenchRuntimeService.deleteRun(tenantId, principal, runId);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @PostMapping("/runs/{runId}/rollback")
    public ApiResponse<WorkbenchApi.RunDetail> rollbackRun(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @org.springframework.web.bind.annotation.RequestBody WorkbenchApi.RollbackRunRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchRuntimeService.rollbackRun(tenantId, principal, runId, body), RequestIds.current(request));
    }

    @PostMapping("/todos/{todoId}/complete")
    public ApiResponse<WorkbenchApi.RunDetail> completeTodo(
        @PathVariable UUID tenantId,
        @PathVariable UUID todoId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @org.springframework.web.bind.annotation.RequestBody WorkbenchApi.CompleteTodoRequest body,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        return ApiResponse.success(workbenchRuntimeService.completeTodo(tenantId, principal, todoId, body), RequestIds.current(request));
    }
}
