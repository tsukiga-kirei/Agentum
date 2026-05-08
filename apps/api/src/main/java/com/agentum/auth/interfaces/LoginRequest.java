package com.agentum.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record LoginRequest(
    @NotBlank(message = "请输入用户名") String username,
    @NotBlank(message = "请输入密码") String password,
    @NotBlank(message = "请选择登录入口") String portal,
    UUID tenantId
) {
}
