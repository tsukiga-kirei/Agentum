package com.agentum.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeMyPasswordRequest(
    @NotBlank(message = "请输入当前密码") String currentPassword,
    @NotBlank(message = "请输入新密码") @Size(min = 8, max = 100, message = "新密码长度应为 8 到 100 个字符") String newPassword
) {
}
