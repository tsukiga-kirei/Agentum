package com.agentum.runtime.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * RabbitMQ 节点执行命令，契约见 packages/shared-contract/events/node-execute-command.schema.json。
 */
public record NodeExecuteCommand(
    int schemaVersion,
    UUID jobId,
    UUID tenantId,
    UUID runId,
    UUID nodeRunId,
    String nodeType,
    UUID operatorUserId,
    String requestId,
    String idempotencyKey,
    int attempt,
    Instant enqueuedAt
) {

    public static NodeExecuteCommand of(
        UUID jobId,
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        String nodeType,
        UUID operatorUserId,
        String requestId,
        int attempt,
        Instant enqueuedAt
    ) {
        return new NodeExecuteCommand(
            1,
            jobId,
            tenantId,
            runId,
            nodeRunId,
            nodeType,
            operatorUserId,
            requestId,
            runId + ":" + nodeRunId + ":" + attempt,
            attempt,
            enqueuedAt
        );
    }
}
