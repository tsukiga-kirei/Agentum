package com.agentum.notification.interfaces;

import java.time.Instant;
import java.util.UUID;

public final class NotificationApi {

    private NotificationApi() {
    }

    public record NotificationUnreadCount(long unreadCount) {
    }

    public record NotificationRow(
        UUID id,
        String category,
        String scope,
        String title,
        String contentMarkdown,
        boolean unread,
        String publisherName,
        Instant createdAt,
        Instant readAt
    ) {
    }

    public record PublishAnnouncementRequest(
        String scope,
        UUID tenantId,
        String title,
        String contentMarkdown
    ) {
    }
}
