package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMemberProfileRequest(
    @NotBlank(message = "请输入用户名") @Size(max = 100, message = "用户名不能超过 100 个字符") String username,
    @NotBlank(message = "请输入成员姓名") @Size(max = 100, message = "成员姓名不能超过 100 个字符") String displayName,
    @Size(max = 255, message = "邮箱不能超过 255 个字符") String email
) {
}
