package com.agentum.organization.interfaces;

import java.util.List;

public record TenantResourcePermissionResponse(
    String resourceType,
    String resourceId,
    String resourceName,
    String resourceCode,
    List<String> actions
) {
}
