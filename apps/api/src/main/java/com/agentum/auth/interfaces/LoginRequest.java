package com.agentum.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

// 登录请求必须显式携带入口类型；租户型入口还必须带 tenantId，防止前端入口选择绕过后端校验。
public record LoginRequest(
    @NotBlank(message = "请输入用户名") String username,
    @NotBlank(message = "请输入密码") String password,
    @NotBlank(message = "请选择登录入口") String portal,
    UUID tenantId
) {
}
