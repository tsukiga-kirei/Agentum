package com.agentum.organization.interfaces;

public record ResourceGrantItemResponse(
    String resourceType,
    String resourceId,
    String resourceName,
    String resourceCode
) {
}
