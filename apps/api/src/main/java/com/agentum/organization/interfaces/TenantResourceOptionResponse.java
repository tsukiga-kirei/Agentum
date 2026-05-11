package com.agentum.organization.interfaces;

public record TenantResourceOptionResponse(
    String resourceType,
    String resourceId,
    String resourceName,
    String resourceCode,
    String version,
    String riskLevel
) {
}
