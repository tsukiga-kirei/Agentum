package com.agentum.auth.interfaces;

import java.util.List;

// 登录响应包含 token、用户信息、完整角色列表、活跃角色和菜单。
// 前端依赖 roles/activeRole 实现角色切换，依赖 menus 动态渲染导航。
public record LoginResponse(
    String token,
    AuthUserResponse user,
    List<RoleInfoResponse> roles,
    RoleInfoResponse activeRole,
    List<String> permissions,
    List<MenuItemResponse> menus
) {
}
