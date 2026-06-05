package com.agentum.auth.interfaces;

import com.agentum.auth.application.LoginCallbackPageRenderer;
import com.agentum.auth.application.SsoAuthService;
import com.agentum.auth.application.SsoAuthorizeRedirect;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SsoAuthController {

    private final SsoAuthService ssoAuthService;
    private final LoginCallbackPageRenderer callbackPageRenderer;

    public SsoAuthController(SsoAuthService ssoAuthService, LoginCallbackPageRenderer callbackPageRenderer) {
        this.ssoAuthService = ssoAuthService;
        this.callbackPageRenderer = callbackPageRenderer;
    }

    @GetMapping("/api/public/tenants/{tenantId}/sso-providers")
    public ApiResponse<List<SsoProviderResponse>> listProviders(@PathVariable UUID tenantId, HttpServletRequest request) {
        return ApiResponse.success(ssoAuthService.listTenantProviders(tenantId), RequestIds.current(request));
    }

    @GetMapping("/api/auth/sso/authorize")
    public void authorize(
        @RequestParam UUID tenantId,
        @RequestParam UUID providerId,
        @RequestParam String portal,
        HttpServletResponse response
    ) throws IOException {
        SsoAuthorizeRedirect redirect = ssoAuthService.createAuthorizeRedirect(tenantId, providerId, portal);
        response.sendRedirect(redirect.redirectUrl());
    }

    @GetMapping(value = "/api/auth/sso/callback/{providerId}", produces = MediaType.TEXT_HTML_VALUE)
    public String callback(
        @PathVariable UUID providerId,
        @RequestParam String code,
        @RequestParam String state
    ) {
        LoginResponse loginResponse = ssoAuthService.handleCallback(providerId, code, state);
        return callbackPageRenderer.render(loginResponse);
    }
}
