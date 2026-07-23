package com.agentum.attachment.messaging;

import com.agentum.attachment.application.AttachmentParseService;
import com.agentum.shared.logging.LogContext;
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
        // 附件解析同样运行在复用的 MQ 线程中，必须恢复并在完成后清理租户、运行与请求链路。
        try (LogContext.Scope ignored = LogContext.openTenantOperation(
            command.tenantId(),
            command.operatorUserId(),
            command.runId(),
            null,
            command.nodeRunId(),
            command.requestId()
        )) {
            parseService.parse(command.attachmentId());
        }
    }
}
