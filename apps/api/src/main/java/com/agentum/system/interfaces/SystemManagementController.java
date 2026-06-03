package com.agentum.system.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.system.application.SystemAdminAccess;
import com.agentum.system.application.SystemManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public ApiResponse<PageResponse<SystemManagementApi.TenantRow>> listTenants(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listTenants(page, size, sort), RequestIds.current(request));
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

    @PostMapping("/tenants/{tenantId}/admins")
    public ApiResponse<Void> createTenantAdmin(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateTenantAdminRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        systemManagementService.createTenantAdmin(tenantId, body);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @PatchMapping("/tenants/{tenantId}/admins/{membershipId}/profile")
    public ApiResponse<Void> updateTenantAdminProfile(
        @PathVariable UUID tenantId,
        @PathVariable UUID membershipId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateTenantAdminProfileRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        systemManagementService.updateTenantAdminProfile(tenantId, membershipId, body);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @PatchMapping("/tenants/{tenantId}/admins/{membershipId}/status")
    public ApiResponse<Void> updateTenantAdminStatus(
        @PathVariable UUID tenantId,
        @PathVariable UUID membershipId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateTenantAdminStatusRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        systemManagementService.updateTenantAdminStatus(tenantId, membershipId, body);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @GetMapping("/model-providers")
    public ApiResponse<PageResponse<SystemManagementApi.ModelProviderRow>> listModelProviders(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listModelProviders(page, size, sort), RequestIds.current(request));
    }

    @GetMapping("/model-provider-types")
    public ApiResponse<List<SystemManagementApi.ModelProviderTypeRow>> listModelProviderTypes(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listModelProviderTypes(), RequestIds.current(request));
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

    @PatchMapping("/model-providers/{providerId}")
    public ApiResponse<SystemManagementApi.ModelProviderRow> updateModelProvider(
        @PathVariable UUID providerId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateModelProviderRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.updateModelProvider(providerId, body), RequestIds.current(request));
    }

    @DeleteMapping("/model-providers/{providerId}")
    public ApiResponse<Void> deleteModelProvider(
        @PathVariable UUID providerId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        systemManagementService.deleteModelProvider(providerId);
        return ApiResponse.success(RequestIds.current(request));
    }

    @PostMapping("/model-providers/{providerId}/test")
    public ApiResponse<SystemManagementApi.ModelProviderTestResult> testModelProvider(
        @PathVariable UUID providerId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.testModelProvider(providerId), RequestIds.current(request));
    }

    @GetMapping("/capabilities")
    public ApiResponse<PageResponse<SystemManagementApi.CapabilityRow>> listCapabilities(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listCapabilities(page, size, sort), RequestIds.current(request));
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

    @PatchMapping("/capabilities/{capabilityId}")
    public ApiResponse<SystemManagementApi.CapabilityRow> updateCapability(
        @PathVariable UUID capabilityId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateCapabilityRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.updateCapability(capabilityId, body), RequestIds.current(request));
    }

    @DeleteMapping("/capabilities/{capabilityId}")
    public ApiResponse<Void> deleteCapability(
        @PathVariable UUID capabilityId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        systemManagementService.deleteCapability(capabilityId);
        return ApiResponse.success(RequestIds.current(request));
    }

    @PostMapping("/capabilities/{capabilityId}/test")
    public ApiResponse<SystemManagementApi.CapabilityTestResult> testCapability(
        @PathVariable UUID capabilityId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.testCapability(capabilityId), RequestIds.current(request));
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

    @PatchMapping("/tenant-capability-grants/{grantId}/status")
    public ApiResponse<SystemManagementApi.GrantRow> updateGrantStatus(
        @PathVariable UUID grantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateGrantStatusRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.updateGrantStatus(grantId, body), RequestIds.current(request));
    }

    @GetMapping("/tenant-model-assignments")
    public ApiResponse<List<SystemManagementApi.TenantModelAssignmentRow>> listTenantModelAssignments(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam UUID tenantId,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.listTenantModelAssignments(tenantId), RequestIds.current(request));
    }

    @PostMapping("/tenant-model-assignments")
    public ApiResponse<SystemManagementApi.TenantModelAssignmentRow> createTenantModelAssignment(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.CreateTenantModelAssignmentRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.createTenantModelAssignment(body), RequestIds.current(request));
    }

    @PatchMapping("/tenant-model-assignments/{assignmentId}/status")
    public ApiResponse<SystemManagementApi.TenantModelAssignmentRow> updateTenantModelAssignmentStatus(
        @PathVariable UUID assignmentId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SystemManagementApi.UpdateTenantModelAssignmentStatusRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(systemManagementService.updateTenantModelAssignmentStatus(assignmentId, body), RequestIds.current(request));
    }
}
