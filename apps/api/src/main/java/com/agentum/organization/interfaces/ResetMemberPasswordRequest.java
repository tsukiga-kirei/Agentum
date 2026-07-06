package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetMemberPasswordRequest(
    @NotBlank(message = "请输入新密码") @Size(min = 8, max = 100, message = "新密码长度应为 8 到 100 个字符") String password
) {
}
