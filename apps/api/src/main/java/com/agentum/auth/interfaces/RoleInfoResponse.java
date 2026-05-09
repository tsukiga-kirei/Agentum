package com.agentum.auth.interfaces;

// 角色分配信息（参照 AuraOA RoleInfo），用于登录响应和角色切换。
public record RoleInfoResponse(
    String id,
    String role,
    String tenantId,
    String tenantName,
    String label
) {
}
