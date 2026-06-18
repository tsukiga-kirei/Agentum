package com.agentum.runtime.messaging;

import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.NodeExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 节点执行 Worker（同 JVM 形态）：消费 RabbitMQ 节点执行命令并委派给执行服务。
 *
 * <p>执行服务内部完成幂等校验、租约互斥、失败重试与终态落库，
 * 监听器只负责消息接收，避免异常向上抛导致无界 requeue。</p>
 */
@Component
public class NodeExecuteCommandListener {

    private static final Logger log = LoggerFactory.getLogger(NodeExecuteCommandListener.class);

    private final NodeExecutionService nodeExecutionService;

    public NodeExecuteCommandListener(NodeExecutionService nodeExecutionService) {
        this.nodeExecutionService = nodeExecutionService;
    }

    @RabbitListener(
        queues = "${agentum.runtime.rabbitmq.queue-node-execute}",
        containerFactory = "runtimeListenerContainerFactory"
    )
    public void onNodeExecuteCommand(NodeExecuteCommand command) {
        String previousRequestId = MDC.get(RequestIds.MDC_KEY);
        String commandRequestId = command == null ? null : command.requestId();
        if (commandRequestId != null && !commandRequestId.isBlank()) {
            // RabbitMQ 消费线程不会经过 HTTP Filter，必须从命令恢复 MDC 才能串联模型、MCP 和下游服务日志。
            MDC.put(RequestIds.MDC_KEY, commandRequestId);
        }
        try {
            if (command == null || command.jobId() == null || command.runId() == null || command.nodeRunId() == null) {
                log.warn("收到非法节点执行命令，已丢弃 command={}", command);
                return;
            }
            nodeExecutionService.execute(command);
        } finally {
            if (previousRequestId == null || previousRequestId.isBlank()) {
                MDC.remove(RequestIds.MDC_KEY);
            } else {
                MDC.put(RequestIds.MDC_KEY, previousRequestId);
            }
        }
    }
}
