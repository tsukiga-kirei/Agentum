package com.agentum.attachment.messaging;

import com.agentum.attachment.application.AttachmentParseService;
import com.agentum.shared.api.RequestIds;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AttachmentParseCommandListener {
    private final AttachmentParseService parseService;

    public AttachmentParseCommandListener(AttachmentParseService parseService) {
        this.parseService = parseService;
    }

    @RabbitListener(queues = "${agentum.runtime.rabbitmq.queue-attachment-parse}", containerFactory = "runtimeListenerContainerFactory")
    public void onCommand(AttachmentParseCommand command) {
        if (command == null || command.attachmentId() == null) return;
        String previous = MDC.get(RequestIds.MDC_KEY);
        if (command.requestId() != null && !command.requestId().isBlank()) MDC.put(RequestIds.MDC_KEY, command.requestId());
        try {
            parseService.parse(command.attachmentId());
        } finally {
            if (previous == null) MDC.remove(RequestIds.MDC_KEY); else MDC.put(RequestIds.MDC_KEY, previous);
        }
    }
}
