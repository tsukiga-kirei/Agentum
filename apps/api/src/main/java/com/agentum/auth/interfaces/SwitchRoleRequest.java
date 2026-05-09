package com.agentum.auth.interfaces;

import jakarta.validation.constraints.NotBlank;

// 角色切换请求，传入 user_role_assignments 的主键 ID；后端校验该角色属于当前用户后重签 token。
public record SwitchRoleRequest(
    @NotBlank(message = "请选择要切换的角色") String roleId
) {
}
