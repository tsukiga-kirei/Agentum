package com.agentum.organization.interfaces;

import java.util.List;

public record MembershipResponse(
    String id,
    String userId,
    String userDisplayName,
    String departmentId,
    String departmentName,
    List<MembershipRoleResponse> roles,
    String spaceCode,
    boolean defaultMembership,
    String status
) {
}
