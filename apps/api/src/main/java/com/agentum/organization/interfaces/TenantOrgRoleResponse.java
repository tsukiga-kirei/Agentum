package com.agentum.organization.interfaces;

import java.util.List;

public record TenantOrgRoleResponse(
    String id,
    String name,
    String description,
    List<String> pagePermissions,
    List<TenantResourcePermissionResponse> resourcePermissions,
    boolean systemRole,
    String status,
    String createdAt,
    String updatedAt
) {
}
