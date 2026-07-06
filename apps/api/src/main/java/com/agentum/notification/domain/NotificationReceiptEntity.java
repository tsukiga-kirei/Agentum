package com.agentum.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_receipts")
public class NotificationReceiptEntity {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationReceiptEntity() {
    }

    public static NotificationReceiptEntity unread(UUID messageId, UUID tenantId, UUID userId, Instant now) {
        NotificationReceiptEntity entity = new NotificationReceiptEntity();
        entity.id = UUID.randomUUID();
        entity.messageId = messageId;
        entity.tenantId = tenantId;
        entity.userId = userId;
        entity.createdAt = now;
        return entity;
    }

    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getMessageId() { return messageId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
