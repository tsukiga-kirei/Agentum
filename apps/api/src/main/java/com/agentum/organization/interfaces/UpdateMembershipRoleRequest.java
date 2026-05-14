package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

// 成员角色调整请求，角色归属与状态由 service 层按租户重新校验。
public record UpdateMembershipRoleRequest(
    @NotNull(message = "请选择角色") List<UUID> roleIds
) {
}
