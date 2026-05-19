package com.agentum.organization.interfaces;

import java.util.List;

public record ResourceGrantResponse(
    String id,
    String groupName,
    List<GrantPrincipalResponse> principals,
    List<ResourceGrantItemResponse> resources,
    String createdAt
) {
}
