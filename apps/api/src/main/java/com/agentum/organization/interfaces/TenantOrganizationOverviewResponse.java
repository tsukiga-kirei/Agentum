package com.agentum.organization.interfaces;

import java.util.List;

public record TenantOrganizationOverviewResponse(
    String tenantId,
    String tenantName,
    String tenantCode,
    List<MemberResponse> members,
    List<DepartmentResponse> departments,
    List<RoleResponse> roles,
    List<MembershipResponse> memberships
) {
}
