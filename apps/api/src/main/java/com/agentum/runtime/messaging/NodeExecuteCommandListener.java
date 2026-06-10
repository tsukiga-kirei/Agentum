package com.agentum.runtime.messaging;

import com.agentum.workbench.application.NodeExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        if (command == null || command.jobId() == null || command.runId() == null || command.nodeRunId() == null) {
            log.warn("收到非法节点执行命令，已丢弃 command={}", command);
            return;
        }
        nodeExecutionService.execute(command);
    }
}
