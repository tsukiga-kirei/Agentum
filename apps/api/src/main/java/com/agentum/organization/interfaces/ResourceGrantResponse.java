package com.agentum.organization.interfaces;

import java.util.List;

public record ResourceGrantResponse(
    String id,
    String principalType,
    String principalId,
    String principalName,
    String resourceType,
    String resourceId,
    String resourceName,
    String resourceCode,
    List<String> actions,
    String createdAt
) {
}
