package com.agentum.organization.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.organization.application.TenantOrganizationAccess;
import com.agentum.organization.application.TenantOrganizationService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/org-roles")
    public ApiResponse<PageResponse<TenantOrgRoleResponse>> listOrgRoles(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        return ApiResponse.success(tenantOrganizationService.listTenantOrgRoles(tenantId, page, size, sort), RequestIds.current(request));
    }

    @GetMapping("/resource-options")
    public ApiResponse<List<TenantResourceOptionResponse>> listResourceOptions(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 资源选项只返回系统管理已启用给当前租户的能力，租户管理不能越权授权全局未启用能力。
        return ApiResponse.success(tenantOrganizationService.listTenantResourceOptions(tenantId), RequestIds.current(request));
    }

    @PostMapping("/org-roles")
    public ApiResponse<TenantOrgRoleResponse> createOrgRole(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody CreateTenantOrgRoleRequest createTenantOrgRoleRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 租户内自定义角色会影响业务侧模块和页签可见性，写动作必须记录操作者并由后端校验页签范围。
        return ApiResponse.success(
            tenantOrganizationService.createTenantOrgRole(tenantId, principal.userId(), createTenantOrgRoleRequest),
            RequestIds.current(request)
        );
    }

    @PatchMapping("/org-roles/{roleId}")
    public ApiResponse<TenantOrgRoleResponse> updateOrgRole(
        @PathVariable UUID tenantId,
        @PathVariable UUID roleId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody UpdateTenantOrgRoleRequest updateTenantOrgRoleRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 禁用租户内角色只停用第二层权限配置，不直接删除历史授权，后续审计可追溯配置变更。
        return ApiResponse.success(
            tenantOrganizationService.updateTenantOrgRole(tenantId, principal.userId(), roleId, updateTenantOrgRoleRequest),
            RequestIds.current(request)
        );
    }

    @PatchMapping("/memberships/{membershipId}/role")
    public ApiResponse<TenantOrganizationOverviewResponse> updateMembershipRole(
        @PathVariable UUID tenantId,
        @PathVariable UUID membershipId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody UpdateMembershipRoleRequest updateMembershipRoleRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 角色调整会直接影响成员可执行动作和页面可见范围，必须保留操作上下文用于审计追踪。
        return ApiResponse.success(
            tenantOrganizationService.updateMembershipRole(tenantId, principal.userId(), membershipId, updateMembershipRoleRequest),
            RequestIds.current(request)
        );
    }

    @PatchMapping("/memberships/{membershipId}/department")
    public ApiResponse<TenantOrganizationOverviewResponse> updateMembershipDepartment(
        @PathVariable UUID tenantId,
        @PathVariable UUID membershipId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody UpdateMembershipDepartmentRequest updateMembershipDepartmentRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 部门调整会影响待办分派和数据过滤，前端只提交意图，部门归属由后端按租户二次校验。
        return ApiResponse.success(
            tenantOrganizationService.updateMembershipDepartment(tenantId, principal.userId(), membershipId, updateMembershipDepartmentRequest),
            RequestIds.current(request)
        );
    }

    @PatchMapping("/memberships/{membershipId}/status")
    public ApiResponse<TenantOrganizationOverviewResponse> updateMembershipStatus(
        @PathVariable UUID tenantId,
        @PathVariable UUID membershipId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody UpdateMembershipStatusRequest updateMembershipStatusRequest,
        HttpServletRequest request
    ) {
        tenantOrganizationAccess.assertCanManageTenant(principal, tenantId);
        // 禁用成员关系会影响该用户在当前租户的入口切换，后端负责同步登录角色并保留历史关系。
        return ApiResponse.success(
            tenantOrganizationService.updateMembershipStatus(tenantId, principal.userId(), membershipId, updateMembershipStatusRequest),
            RequestIds.current(request)
        );
    }
}
