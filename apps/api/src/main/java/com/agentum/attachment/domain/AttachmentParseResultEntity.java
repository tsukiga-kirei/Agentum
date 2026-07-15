package com.agentum.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachment_parse_results")
public class AttachmentParseResultEntity {

    @Id private UUID id;
    @Column(name = "attachment_id", nullable = false, unique = true) private UUID attachmentId;
    @Column(name = "parser_type", nullable = false, length = 30) private String parserType;
    @Column(name = "parser_version", nullable = false, length = 80) private String parserVersion;
    @Column(name = "parser_config_hash", nullable = false, length = 64) private String parserConfigHash;
    @Column(name = "content_storage_key", length = 1000) private String contentStorageKey;
    @Column(name = "character_count", nullable = false) private int characterCount;
    @Column(nullable = false) private boolean truncated;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "error_code", length = 100) private String errorCode;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected AttachmentParseResultEntity() {
    }

    public static AttachmentParseResultEntity parsing(UUID attachmentId, String parserType, String parserVersion, String configHash, Instant now) {
        AttachmentParseResultEntity entity = new AttachmentParseResultEntity();
        entity.id = UUID.randomUUID();
        entity.attachmentId = attachmentId;
        entity.parserType = parserType;
        entity.parserVersion = parserVersion;
        entity.parserConfigHash = configHash;
        entity.status = "parsing";
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void ready(String storageKey, int characterCount, boolean truncated, Instant now) {
        this.contentStorageKey = storageKey;
        this.characterCount = characterCount;
        this.truncated = truncated;
        this.status = "ready";
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void failed(String code, String message, Instant now) {
        status = "failed";
        errorCode = code;
        errorMessage = message;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAttachmentId() { return attachmentId; }
    public String getContentStorageKey() { return contentStorageKey; }
    public String getStatus() { return status; }
    public int getCharacterCount() { return characterCount; }
    public boolean isTruncated() { return truncated; }
}
