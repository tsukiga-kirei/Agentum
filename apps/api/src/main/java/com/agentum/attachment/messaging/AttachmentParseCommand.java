package com.agentum.attachment.messaging;

import java.util.UUID;

public record AttachmentParseCommand(UUID attachmentId, String requestId) {
}
