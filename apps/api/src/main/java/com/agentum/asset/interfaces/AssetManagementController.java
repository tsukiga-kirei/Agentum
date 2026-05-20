package com.agentum.asset.interfaces;

import com.agentum.asset.application.AssetAccess;
import com.agentum.asset.application.AssetManagementService;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/tenants/{tenantId}/assets")
@Validated
public class AssetManagementController {

    private final AssetAccess assetAccess;
    private final AssetManagementService assetManagementService;

    public AssetManagementController(AssetAccess assetAccess, AssetManagementService assetManagementService) {
        this.assetAccess = assetAccess;
        this.assetManagementService = assetManagementService;
    }

    @GetMapping("/summary")
    public ApiResponse<AssetManagementApi.AssetSummary> summary(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.getSummary(tenantId, principal), RequestIds.current(request));
    }

    @GetMapping("/system-capabilities")
    public ApiResponse<PageResponse<AssetManagementApi.SystemCapabilityAssetRow>> listSystemCapabilities(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "openedAt,desc") String sort,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.listTenantSystemCapabilities(tenantId, principal, page, size, sort), RequestIds.current(request));
    }

    @GetMapping("/mine")
    public ApiResponse<PageResponse<AssetManagementApi.MyAssetRow>> listMine(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "updatedAt,desc") String sort,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.listMyAssets(tenantId, principal, keyword, page, size, sort), RequestIds.current(request));
    }

    @PostMapping("/mine")
    public ApiResponse<AssetManagementApi.MyAssetRow> createMine(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody AssetManagementApi.CreateMyAssetRequest body,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.createMyAsset(tenantId, principal, body), RequestIds.current(request));
    }

    @GetMapping("/mine/{assetId}")
    public ApiResponse<AssetManagementApi.MyAssetDetail> getMine(
        @PathVariable UUID tenantId,
        @PathVariable UUID assetId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.getMyAsset(tenantId, assetId, principal), RequestIds.current(request));
    }

    @PatchMapping("/mine/{assetId}")
    public ApiResponse<AssetManagementApi.MyAssetDetail> updateMine(
        @PathVariable UUID tenantId,
        @PathVariable UUID assetId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody AssetManagementApi.UpdateMyAssetRequest body,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.updateMyAsset(tenantId, assetId, principal, body), RequestIds.current(request));
    }

    @PostMapping("/mine/{assetId}/publish")
    public ApiResponse<AssetManagementApi.MyAssetDetail> publishMine(
        @PathVariable UUID tenantId,
        @PathVariable UUID assetId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        return ApiResponse.success(assetManagementService.publishMyAsset(tenantId, assetId, principal), RequestIds.current(request));
    }

    @DeleteMapping("/mine/{assetId}")
    public ApiResponse<Void> deleteMine(
        @PathVariable UUID tenantId,
        @PathVariable UUID assetId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        assetAccess.assertCanUseAssets(principal, tenantId);
        assetManagementService.deleteMyAsset(tenantId, assetId, principal);
        return ApiResponse.success(RequestIds.current(request));
    }
}
