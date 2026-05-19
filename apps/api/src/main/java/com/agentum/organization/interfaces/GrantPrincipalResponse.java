package com.agentum.organization.interfaces;

public record GrantPrincipalResponse(
    String principalType,
    String principalId,
    String principalName
) {
}
