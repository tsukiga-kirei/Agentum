package com.agentum.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_messages")
public class NotificationMessageEntity {

    public static final String CATEGORY_SYSTEM_NOTICE = "system_notice";
    public static final String CATEGORY_SCHEDULE_RESULT = "schedule_result";

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 30)
    private String scope;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(name = "content_markdown", nullable = false)
    private String contentMarkdown;

    @Column(name = "source_type", length = 60)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationMessageEntity() {
    }

    public static NotificationMessageEntity create(
        UUID tenantId,
        String scope,
        String category,
        String title,
        String contentMarkdown,
        String sourceType,
        UUID sourceId,
        UUID createdBy,
        Instant now
    ) {
        NotificationMessageEntity entity = new NotificationMessageEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.scope = scope;
        entity.category = category;
        entity.title = title;
        entity.contentMarkdown = contentMarkdown;
        entity.sourceType = sourceType;
        entity.sourceId = sourceId;
        entity.createdBy = createdBy;
        entity.createdAt = now;
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getScope() { return scope; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getContentMarkdown() { return contentMarkdown; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
