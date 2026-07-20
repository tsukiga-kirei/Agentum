package com.agentum.runtime.messaging;

import java.time.Instant;
import java.util.List;
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
    List<Integer> clusterAgentIndexes,
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
            List.of(),
            enqueuedAt
        );
    }

    /**
     * 子智能体单独重跑时显式冻结本轮参与汇总的下标集合。
     * 意图分派不能重新调用分类器，否则同一次业务运行可能因模型波动改派到其他智能体。
     */
    public static NodeExecuteCommand forClusterAgents(
        UUID jobId,
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        String nodeType,
        UUID operatorUserId,
        String requestId,
        int attempt,
        List<Integer> clusterAgentIndexes,
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
            clusterAgentIndexes == null ? List.of() : List.copyOf(clusterAgentIndexes),
            enqueuedAt
        );
    }
}
