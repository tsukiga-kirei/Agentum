package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMembershipStatusRequest(
    @NotBlank(message = "请选择成员状态")
    @Size(max = 30, message = "成员状态不能超过 30 个字符")
    String status
) {
}
