package com.agentum.organization.interfaces;

import java.util.List;

public record MemberImportResultResponse(
    int total,
    int success,
    List<MemberImportFailedRowResponse> failedRows
) {
}
