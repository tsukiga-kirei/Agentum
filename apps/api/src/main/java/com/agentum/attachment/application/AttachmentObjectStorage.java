package com.agentum.attachment.application;

import java.util.UUID;

public interface AttachmentObjectStorage {
    String storeOriginal(UUID tenantId, UUID attachmentId, String fileName, String contentType, byte[] bytes);
    String storeParsedContent(UUID tenantId, UUID attachmentId, String markdown);
    byte[] read(String storageKey);
    void delete(String storageKey);
}
