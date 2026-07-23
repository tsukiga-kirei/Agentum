package com.agentum.attachment.messaging;

import java.util.UUID;

/** 附件解析命令显式携带租户与运行链路，消费者不能依赖发布线程的 MDC。 */
public record AttachmentParseCommand(
    UUID attachmentId,
    UUID tenantId,
    UUID runId,
    UUID nodeRunId,
    UUID operatorUserId,
    String requestId
) {
}
