package com.agentum.organization.interfaces;

public record DepartmentResponse(
    String id,
    String parentId,
    String name,
    String code,
    int sortOrder,
    String status
) {
}
