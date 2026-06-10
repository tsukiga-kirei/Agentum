package com.agentum.runtime.messaging;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 节点执行命令发布器：advance / 重新执行 / 恢复进度都会通过它把作业投递到 RabbitMQ。
 */
@Component
public class NodeExecuteCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(NodeExecuteCommandPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeExecutionProperties properties;

    public NodeExecuteCommandPublisher(RabbitTemplate runtimeRabbitTemplate, RuntimeExecutionProperties properties) {
        this.rabbitTemplate = runtimeRabbitTemplate;
        this.properties = properties;
    }

    public void publish(NodeExecuteCommand command) {
        rabbitTemplate.convertAndSend(
            properties.getRabbitmq().getExchange(),
            properties.getRabbitmq().getQueueNodeExecute(),
            command
        );
        log.info(
            "节点执行命令已入队 tenantId={} runId={} nodeRunId={} jobId={} attempt={} requestId={}",
            command.tenantId(),
            command.runId(),
            command.nodeRunId(),
            command.jobId(),
            command.attempt(),
            command.requestId()
        );
    }
}
