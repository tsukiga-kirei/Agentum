package com.agentum.organization.interfaces;

public record RoleResponse(
    String id,
    String code,
    String name,
    String status,
    String description
) {
}
