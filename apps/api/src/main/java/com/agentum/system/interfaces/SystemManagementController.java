package com.agentum.system.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.application.SystemAdminAccess;
import com.agentum.system.application.SystemManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台级系统管理接口：租户状态、模型供应商注册、全局能力与租户授权。
 * 必须由 system_admin 调用；具体路由与菜单分层可对照 AuraOA，但实现保持 Spring 边界。
 */
@RestController
@RequestMapping("/api/system")
@Validated
public class SystemManagementController {

    private final SystemAdminAccess systemAdminAccess;
    private final SystemManagementService systemManagementService;

    public SystemManagementController(SystemAdminAccess systemAdminAccess, SystemManagementService systemManagementService) {
        this.systemAdminAccess = systemAdminAccess;
        this.systemManagementService = systemManagementService;
    }

    @GetMapping("/summary")
    public ApiResponse<SystemManagementApi.Summary> summary(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.getSummary(), RequestIds.current(request));
    }

    @GetMapping("/tenants")
    public ApiResponse<List<SystemManagementApi.TenantRow>> listTenants(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listTenants(), RequestIds.current(request));
    }

    @PostMapping("/tenants")
    public ApiResponse<SystemManagementApi.TenantRow> createTenant(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateTenantRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.createTenant(body), RequestIds.current(request));
    }

    @PatchMapping("/tenants/{tenantId}/status")
    public ApiResponse<SystemManagementApi.TenantRow> updateTenantStatus(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateTenantStatusRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.updateTenantStatus(tenantId, body), RequestIds.current(request));
    }

    @GetMapping("/model-providers")
    public ApiResponse<List<SystemManagementApi.ModelProviderRow>> listModelProviders(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listModelProviders(), RequestIds.current(request));
    }

    @PostMapping("/model-providers")
    public ApiResponse<SystemManagementApi.ModelProviderRow> createModelProvider(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateModelProviderRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.createModelProvider(body), RequestIds.current(request));
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<SystemManagementApi.CapabilityRow>> listCapabilities(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listCapabilities(), RequestIds.current(request));
    }

    @PostMapping("/capabilities")
    public ApiResponse<SystemManagementApi.CapabilityRow> createCapability(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateCapabilityRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.createCapability(body), RequestIds.current(request));
    }

    @GetMapping("/tenant-capability-grants")
    public ApiResponse<List<SystemManagementApi.GrantRow>> listGrants(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(required = false) UUID tenantId,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listGrants(tenantId), RequestIds.current(request));
    }

    @PostMapping("/tenant-capability-grants")
    public ApiResponse<SystemManagementApi.GrantRow> createGrant(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateGrantRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.createGrant(body), RequestIds.current(request));
    }
}
