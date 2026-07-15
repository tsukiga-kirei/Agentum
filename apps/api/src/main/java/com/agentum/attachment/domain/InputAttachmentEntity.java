package com.agentum.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 输入附件实体只保存对象存储引用和脱敏状态，不把文件正文写入数据库。 */
@Entity
@Table(name = "input_attachments")
public class InputAttachmentEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "run_id", nullable = false) private UUID runId;
    @Column(name = "node_run_id", nullable = false) private UUID nodeRunId;
    @Column(name = "field_id", nullable = false, length = 120) private String fieldId;
    @Column(name = "variable_key", nullable = false, length = 120) private String variableKey;
    @Column(name = "uploaded_by", nullable = false) private UUID uploadedBy;
    @Column(name = "original_file_name", nullable = false, length = 255) private String originalFileName;
    @Column(nullable = false, length = 30) private String extension;
    @Column(name = "content_type", nullable = false, length = 160) private String contentType;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(name = "content_sha256", nullable = false, length = 64) private String contentSha256;
    @Column(name = "storage_key", nullable = false, length = 1000) private String storageKey;
    @Column(name = "recognition_engine", nullable = false, length = 20) private String recognitionEngine;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "error_code", length = 100) private String errorCode;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "expires_at") private Instant expiresAt;

    protected InputAttachmentEntity() {
    }

    public static InputAttachmentEntity create(
        UUID id, UUID tenantId, UUID runId, UUID nodeRunId, String fieldId, String variableKey, UUID uploadedBy,
        String originalFileName, String extension, String contentType, long sizeBytes, String sha256,
        String storageKey, String recognitionEngine, String initialStatus, Instant expiresAt, Instant now
    ) {
        InputAttachmentEntity entity = new InputAttachmentEntity();
        entity.id = id;
        entity.tenantId = tenantId;
        entity.runId = runId;
        entity.nodeRunId = nodeRunId;
        entity.fieldId = fieldId;
        entity.variableKey = variableKey;
        entity.uploadedBy = uploadedBy;
        entity.originalFileName = originalFileName;
        entity.extension = extension;
        entity.contentType = contentType;
        entity.sizeBytes = sizeBytes;
        entity.contentSha256 = sha256;
        entity.storageKey = storageKey;
        entity.recognitionEngine = recognitionEngine;
        entity.status = initialStatus;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.expiresAt = expiresAt;
        return entity;
    }

    public void markParsing(Instant now) { status = "parsing"; errorCode = null; errorMessage = null; updatedAt = now; }
    public void markReady(Instant now) { status = "ready"; errorCode = null; errorMessage = null; updatedAt = now; }
    public void markFailed(String code, String message, Instant now) { status = "failed"; errorCode = code; errorMessage = message; updatedAt = now; }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getRunId() { return runId; }
    public UUID getNodeRunId() { return nodeRunId; }
    public String getFieldId() { return fieldId; }
    public String getVariableKey() { return variableKey; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getOriginalFileName() { return originalFileName; }
    public String getExtension() { return extension; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContentSha256() { return contentSha256; }
    public String getStorageKey() { return storageKey; }
    public String getRecognitionEngine() { return recognitionEngine; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
