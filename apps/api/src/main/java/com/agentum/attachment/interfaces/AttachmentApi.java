package com.agentum.attachment.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 工作流输入附件接口契约；任何响应都不得包含对象存储键。 */
public final class AttachmentApi {

    private AttachmentApi() {
    }

    public record AttachmentRow(
        UUID id,
        String fieldId,
        String variableKey,
        String fileName,
        String extension,
        String contentType,
        long sizeBytes,
        String recognitionEngine,
        String status,
        String errorCode,
        String errorMessage,
        UUID parseResultId,
        Integer characterCount,
        Boolean truncated,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record AttachmentList(List<AttachmentRow> items) {
    }
}
