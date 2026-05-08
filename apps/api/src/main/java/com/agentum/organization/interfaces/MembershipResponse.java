package com.agentum.organization.interfaces;

public record MembershipResponse(
    String id,
    String userId,
    String userDisplayName,
    String departmentId,
    String departmentName,
    String roleId,
    String roleName,
    String roleCode,
    String spaceCode,
    boolean defaultMembership,
    String status
) {
}
