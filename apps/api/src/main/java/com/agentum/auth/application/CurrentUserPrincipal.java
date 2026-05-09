package com.agentum.auth.application;

import java.util.UUID;

// 认证过滤器从 Token 中还原出的最小身份上下文；业务层仍需按资源和动作做二次权限校验。
// roleAssignmentId 关联 user_role_assignments 表，用于角色切换时定位当前活跃角色。
public record CurrentUserPrincipal(
    UUID userId,
    String username,
    UUID tenantId,
    String role,
    String portal,
    String spaceCode,
    UUID roleAssignmentId
) {
}
