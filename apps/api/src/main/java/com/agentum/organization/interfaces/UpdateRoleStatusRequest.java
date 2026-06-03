package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;

public record UpdateRoleStatusRequest(
    @NotBlank(message = "请输入角色状态") String status
) {
}
