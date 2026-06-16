package com.agentum.audit.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * MCP/Skill/Model 调用审计日志。
 */
public record AuditToolCallDto(
    UUID id,
    UUID runId,
    UUID nodeRunId,
    String toolType, // "MCP" | "MODEL"
    String toolName,
    String status,
    Long latencyMs,
    Instant createdAt,
    String callerName, // 如关联工作流运行或节点名称
    Map<String, Object> requestPayload,
    Map<String, Object> responsePayload,
    String errorMessage
) {}
