package com.agentum.organization.interfaces;

import java.util.List;

public record PageGrantResponse(
    String id,
    String groupName,
    List<GrantPrincipalResponse> principals,
    List<PageGrantItemResponse> pages,
    String createdAt
) {
}
