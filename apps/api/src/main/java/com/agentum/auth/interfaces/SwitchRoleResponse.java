package com.agentum.auth.interfaces;

import java.util.List;

// 角色切换响应，包含新 token 和切换后的角色、菜单（参照 AuraOA SwitchRoleResponse）。
public record SwitchRoleResponse(
    String token,
    AuthUserResponse user,
    RoleInfoResponse activeRole,
    List<String> permissions,
    List<MenuItemResponse> menus
) {
}
