package com.agentum.notification.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.notification.application.NotificationService;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/unread-count")
    public ApiResponse<NotificationApi.NotificationUnreadCount> unreadCount(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            new NotificationApi.NotificationUnreadCount(notificationService.unreadCount(principal)),
            RequestIds.current(request)
        );
    }

    @GetMapping
    public ApiResponse<PageResponse<NotificationApi.NotificationRow>> list(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam(defaultValue = "all") String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        HttpServletRequest request
    ) {
        return ApiResponse.success(notificationService.list(principal, status, page, size), RequestIds.current(request));
    }

    @PatchMapping("/read-all")
    public ApiResponse<NotificationApi.NotificationUnreadCount> markAllRead(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        return ApiResponse.success(notificationService.markAllRead(principal), RequestIds.current(request));
    }

    @PatchMapping("/{messageId}/read")
    public ApiResponse<Void> markRead(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @PathVariable UUID messageId,
        HttpServletRequest request
    ) {
        notificationService.markRead(principal, messageId);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    @PostMapping("/announcements")
    public ApiResponse<NotificationApi.NotificationRow> publishAnnouncement(
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestBody NotificationApi.PublishAnnouncementRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(notificationService.publishAnnouncement(principal, body), RequestIds.current(request));
    }
}
