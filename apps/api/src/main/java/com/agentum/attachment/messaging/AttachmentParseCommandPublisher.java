package com.agentum.attachment.messaging;

import com.agentum.runtime.execution.RuntimeExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AttachmentParseCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(AttachmentParseCommandPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final RuntimeExecutionProperties properties;

    public AttachmentParseCommandPublisher(RabbitTemplate runtimeRabbitTemplate, RuntimeExecutionProperties properties) {
        this.rabbitTemplate = runtimeRabbitTemplate;
        this.properties = properties;
    }

    public void publish(AttachmentParseCommand command) {
        rabbitTemplate.convertAndSend(properties.getRabbitmq().getExchange(), properties.getRabbitmq().getQueueAttachmentParse(), command);
        log.info("附件解析命令已入队 attachmentId={} requestId={}", command.attachmentId(), command.requestId());
    }
}
