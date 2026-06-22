package com.agentum.auth.interfaces;

import com.agentum.auth.application.AuthService;
import com.agentum.auth.application.AuthCookieService;
import com.agentum.auth.application.AuthSessionResult;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final AuthCookieService cookieService;

    public AuthController(AuthService authService, AuthCookieService cookieService) {
        this.authService = authService;
        this.cookieService = cookieService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request, HttpServletResponse response) {
        AuthSessionResult result = authService.login(loginRequest);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
        return ApiResponse.success(result.response(), RequestIds.current(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        try {
            AuthSessionResult result = authService.refresh(cookieService.read(request));
            response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
            return ApiResponse.success(result.response(), RequestIds.current(request));
        } catch (ApiException exception) {
            // 失效 Cookie 必须同步清理，否则刷新页面会重复携带同一枚无效令牌。
            response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clear());
            throw exception;
        }
    }

    // /me 返回与登录相同结构（含 roles、activeRole、menus），前端可据此恢复完整会话状态。
    @GetMapping("/me")
    public ApiResponse<LoginResponse> me(@AuthenticationPrincipal CurrentUserPrincipal principal, HttpServletRequest request) {
        if (principal == null) {
            log.warn("当前用户查询被拒绝：缺少认证主体 requestId={}", RequestIds.current(request));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        return ApiResponse.success(authService.currentUser(principal), RequestIds.current(request));
    }

    // 角色切换：传入 user_role_assignments 的 ID，后端校验归属后重签 token（参照 AuraOA PUT /api/auth/switch-role）。
    @PutMapping("/switch-role")
    public ApiResponse<SwitchRoleResponse> switchRole(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @Valid @RequestBody SwitchRoleRequest switchRoleRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (principal == null) {
            log.warn("角色切换被拒绝：缺少认证主体 requestId={}", RequestIds.current(request));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        UUID targetRoleId = UUID.fromString(switchRoleRequest.roleId());
        AuthSessionResult result = authService.switchRole(principal, targetRoleId, cookieService.read(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.create(result.refreshToken()));
        LoginResponse login = result.response();
        return ApiResponse.success(new SwitchRoleResponse(login.token(), login.user(), login.activeRole(), login.permissions(), login.menus()), RequestIds.current(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CurrentUserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        authService.logout(cookieService.read(request), principal);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clear());
        return ApiResponse.success(RequestIds.current(request));
    }
}
