package com.agentum.auth.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMyProfileRequest(
    @NotBlank(message = "请输入姓名") @Size(max = 100, message = "姓名不能超过 100 个字符") String displayName,
    @Size(max = 255, message = "邮箱不能超过 255 个字符") String email
) {
}
