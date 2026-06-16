package com.agentum.audit.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 操作审计日志 DTO。
 */
public record AuditOperationLogDto(
    UUID id,
    String operatorName,
    String actionType,
    String targetType,
    String targetName,
    String description,
    String payload, // JSON String 表达
    String clientIp,
    Instant createdAt
) {}
