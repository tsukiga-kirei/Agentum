package com.agentum.auth.interfaces;

import com.agentum.auth.application.AuthService;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        return ApiResponse.success(authService.login(loginRequest), RequestIds.current(request));
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
        HttpServletRequest request
    ) {
        if (principal == null) {
            log.warn("角色切换被拒绝：缺少认证主体 requestId={}", RequestIds.current(request));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }

        UUID targetRoleId = UUID.fromString(switchRoleRequest.roleId());
        return ApiResponse.success(authService.switchRole(principal, targetRoleId), RequestIds.current(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CurrentUserPrincipal principal, HttpServletRequest request) {
        // 当前阶段是无状态 Bearer Token，登出只清理前端本地凭据；这里保留审计线索，后续接入 token 吊销表。
        log.info(
            "用户登出 userId={} tenantId={} role={} requestId={}",
            principal == null ? null : principal.userId(),
            principal == null ? null : principal.tenantId(),
            principal == null ? null : principal.role(),
            RequestIds.current(request)
        );
        return ApiResponse.success(RequestIds.current(request));
    }
}
