package com.agentum.auth.interfaces;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BootstrapAdminRequest(
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,100}$", message = "用户名需为 3 到 100 位字母、数字或下划线")
    String username,

    @NotBlank(message = "请输入显示名称")
    @Size(max = 100, message = "显示名称不能超过 100 个字符")
    String displayName,

    @NotBlank(message = "请输入初始密码")
    @Size(min = 8, max = 100, message = "初始密码长度应为 8 到 100 个字符")
    String password,

    @Size(max = 255, message = "邮箱不能超过 255 个字符")
    @Email(message = "请输入有效邮箱")
    String email
) {
}
