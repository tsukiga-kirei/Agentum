package com.agentum.audit.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流运行审计概要信息。
 */
public record AuditRunSummaryDto(
    UUID id,
    String title,
    String workflowName,
    int versionNumber,
    String state,
    Instant startedAt,
    Instant completedAt,
    String operatorName
) {}
