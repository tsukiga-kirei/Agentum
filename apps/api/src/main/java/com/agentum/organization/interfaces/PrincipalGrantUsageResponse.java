package com.agentum.organization.interfaces;

/**
 * 组织主体（角色 / 部门 / 用户）在页签与能力分配中的引用计数，供停用前预检查展示。
 */
public record PrincipalGrantUsageResponse(
    String principalType,
    String principalId,
    long pageGrantRows,
    long resourceGrantRows
) {
}
