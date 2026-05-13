package com.agentum.organization.interfaces;

public record PageGrantResponse(
    String id,
    String principalType,
    String principalId,
    String principalName,
    String pageKey,
    String pageName,
    String createdAt
) {
}
