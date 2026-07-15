package com.agentum.attachment.interfaces;

import com.agentum.attachment.application.AttachmentRecognitionSettingsService;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.application.SystemAdminAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/settings/attachment-recognition")
public class SystemAttachmentSettingsController {

    private final SystemAdminAccess systemAdminAccess;
    private final AttachmentRecognitionSettingsService settingsService;

    public SystemAttachmentSettingsController(SystemAdminAccess systemAdminAccess, AttachmentRecognitionSettingsService settingsService) {
        this.systemAdminAccess = systemAdminAccess;
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<AttachmentRecognitionApi.Settings> get(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(settingsService.getSettings(), RequestIds.current(request));
    }

    @PutMapping
    public ApiResponse<AttachmentRecognitionApi.Settings> update(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody AttachmentRecognitionApi.UpdateSettingsRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(settingsService.update(body, principal.userId()), RequestIds.current(request));
    }

    @PostMapping("/test-connection")
    public ApiResponse<AttachmentRecognitionApi.TestConnectionResult> testConnection(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody AttachmentRecognitionApi.TestConnectionRequest body,
        HttpServletRequest request
    ) {
        systemAdminAccess.assertSystemAdmin(principal);
        return ApiResponse.success(settingsService.testConnection(body, principal.userId()), RequestIds.current(request));
    }
}
