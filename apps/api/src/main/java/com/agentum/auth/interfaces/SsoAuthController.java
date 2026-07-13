package com.agentum.auth.interfaces;

import com.agentum.auth.application.LoginCallbackPageRenderer;
import com.agentum.auth.application.AuthCookieService;
import com.agentum.auth.application.AuthSessionResult;
import com.agentum.auth.application.BasicSsoHandoff;
import com.agentum.auth.application.BasicSsoHandoffService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SsoAuthController {

    private final SsoAuthService ssoAuthService;
    private final LoginCallbackPageRenderer callbackPageRenderer;
    private final AuthCookieService cookieService;
    private final BasicSsoHandoffService basicSsoHandoffService;

    public SsoAuthController(
        SsoAuthService ssoAuthService,
        LoginCallbackPageRenderer callbackPageRenderer,
        AuthCookieService cookieService,
        BasicSsoHandoffService basicSsoHandoffService
    ) {
        this.ssoAuthService = ssoAuthService;
        this.callbackPageRenderer = callbackPageRenderer;
        this.cookieService = cookieService;
        this.basicSsoHandoffService = basicSsoHandoffService;
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

    @GetMapping(value = "/api/auth/sso/basic-entry", produces = MediaType.TEXT_HTML_VALUE)
    public String basicEntry(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestParam(defaultValue = "business") String portal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthSessionResult result = ssoAuthService.handleBasicEntry(
            authorization,
            portal,
            request.getRemoteAddr(),
            request.getHeader(HttpHeaders.ORIGIN),
            request.getHeader(HttpHeaders.REFERER)
        );
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
        return callbackPageRenderer.render(result.response());
    }

    @GetMapping("/api/auth/sso/basic-redirection")
    public void basicRedirection(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestParam(defaultValue = "business") String portal,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws IOException {
        // 该接口仿照第三方 H5 登录：业务系统服务端用 Basic 换取一次性地址，再由浏览器跳到 Agentum 域名建立会话。
        BasicSsoHandoff handoff = ssoAuthService.prepareBasicHandoff(
            authorization,
            portal,
            request.getRemoteAddr(),
            request.getHeader(HttpHeaders.ORIGIN),
            request.getHeader(HttpHeaders.REFERER)
        );
        String code = basicSsoHandoffService.create(handoff);
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, ssoAuthService.basicHandoffConsumeUrl(code));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping(value = "/api/auth/sso/basic-consume", produces = MediaType.TEXT_HTML_VALUE)
    public String basicConsume(@RequestParam String code, HttpServletResponse response) {
        BasicSsoHandoff handoff = basicSsoHandoffService.consume(code);
        AuthSessionResult result = ssoAuthService.consumeBasicHandoff(handoff);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
        // 一次性码虽无共享密码，仍禁止缓存和 Referer 透传，减少短期码落入历史记录或第三方日志的机会。
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        return callbackPageRenderer.render(result.response());
    }

    @GetMapping(value = "/api/auth/sso/callback/{providerId}", produces = MediaType.TEXT_HTML_VALUE)
    public String callback(
        @PathVariable UUID providerId,
        @RequestParam String code,
        @RequestParam String state,
        HttpServletResponse response
    ) {
        AuthSessionResult result = ssoAuthService.handleCallback(providerId, code, state);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
        return callbackPageRenderer.render(result.response());
    }
}
