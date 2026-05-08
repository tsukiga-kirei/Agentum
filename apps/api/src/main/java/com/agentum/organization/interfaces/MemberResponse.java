package com.agentum.organization.interfaces;

public record MemberResponse(
    String id,
    String username,
    String displayName,
    String email,
    String status,
    String lastLoginAt
) {
}
