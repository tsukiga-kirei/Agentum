package com.agentum.organization.interfaces;

public record MemberImportFailedRowResponse(
    int rowNumber,
    String reason
) {
}
