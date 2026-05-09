package com.agentum.auth.interfaces;

// 菜单项，由后端根据系统角色和租户内权限计算，前端不再硬编码菜单可见性。
public record MenuItemResponse(
    String key,
    String label,
    String icon,
    String description
) {
}
