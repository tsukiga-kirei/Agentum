package com.agentum.organization.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.application.TenantOrganizationAccess;
import com.agentum.organization.application.TenantOrganizationService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tenants/{tenantId}/organization")
public class TenantOrganizationController {

    private final TenantOrganizationAccess tenantOrganizationAccess;
    private final TenantOrganizationService tenantOrganizationService;

    public TenantOrganizationController(TenantOrganizationAccess tenantOrganizationAccess, TenantOrganizationService tenantOrganizationService) {
        this.tenantOrganizationAccess = tenantOrganizationAccess;
        this.tenantOrganizationService = tenantOrganizationService;
    }

    @GetMapping("/overview")
    public ApiResponse<TenantOrganizationOverviewResponse> overview(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        return ApiResponse.success(tenantOrganizationService.getOverview(tenantId), RequestIds.current(request));
    }

    @PostMapping("/members")
    public ApiResponse<TenantOrganizationOverviewResponse> createMember(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody CreateMemberRequest createMemberRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 成员写入需要同时保留目标租户和操作者，便于后续审计日志与权限事件串联。
        return ApiResponse.success(tenantOrganizationService.createMember(tenantId, principal.userId(), createMemberRequest), RequestIds.current(request));
    }

    @PostMapping("/departments")
    public ApiResponse<TenantOrganizationOverviewResponse> createDepartment(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody CreateDepartmentRequest createDepartmentRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 部门树会影响待办分派和资源过滤，写动作必须把操作者带入 service 日志上下文。
        return ApiResponse.success(tenantOrganizationService.createDepartment(tenantId, principal.userId(), createDepartmentRequest), RequestIds.current(request));
    }
}
