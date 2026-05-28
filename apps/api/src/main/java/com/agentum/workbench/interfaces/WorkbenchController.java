package com.agentum.workbench.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workbench.application.WorkbenchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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

    public WorkbenchController(WorkbenchAccess workbenchAccess, WorkbenchService workbenchService) {
        this.workbenchAccess = workbenchAccess;
        this.workbenchService = workbenchService;
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
            workbenchService.listAvailableWorkflows(tenantId, principal, keyword, page, size, sort),
            RequestIds.current(request)
        );
    }
}
