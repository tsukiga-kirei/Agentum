package com.agentum.notification.application;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.auth.domain.UserAccount;
import com.agentum.auth.domain.UserRoleAssignmentEntity;
import com.agentum.auth.infrastructure.UserAccountRepository;
import com.agentum.auth.infrastructure.UserRoleAssignmentRepository;
import com.agentum.notification.domain.NotificationMessageEntity;
import com.agentum.notification.domain.NotificationReceiptEntity;
import com.agentum.notification.infrastructure.NotificationMessageRepository;
import com.agentum.notification.infrastructure.NotificationReceiptRepository;
import com.agentum.notification.interfaces.NotificationApi;
import com.agentum.organization.domain.UserMembershipEntity;
import com.agentum.organization.infrastructure.UserMembershipRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.pagination.PageQuery;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.shared.pagination.PageableFactory;
import com.agentum.shared.pagination.SortWhitelist;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final SortWhitelist NOTIFICATION_SORT = SortWhitelist.of("createdAt");
    private static final String ACTIVE_STATUS = "active";

    private final NotificationMessageRepository messageRepository;
    private final NotificationReceiptRepository receiptRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserMembershipRepository userMembershipRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final Clock clock;

    public NotificationService(
        NotificationMessageRepository messageRepository,
        NotificationReceiptRepository receiptRepository,
        UserAccountRepository userAccountRepository,
        UserMembershipRepository userMembershipRepository,
        UserRoleAssignmentRepository userRoleAssignmentRepository,
        Clock clock
    ) {
        this.messageRepository = messageRepository;
        this.receiptRepository = receiptRepository;
        this.userAccountRepository = userAccountRepository;
        this.userMembershipRepository = userMembershipRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        return receiptRepository.countByUserIdAndReadAtIsNull(principal.userId());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationApi.NotificationRow> list(CurrentUserPrincipal principal, String status, int page, int size) {
        ensureAuthenticated(principal);
        String normalizedStatus = normalizeStatus(status);
        Pageable pageable = PageableFactory.from(PageQuery.of(page, size, "createdAt,desc"), NOTIFICATION_SORT);
        Page<NotificationReceiptEntity> receipts = receiptRepository.searchByUser(principal.userId(), normalizedStatus, pageable);
        Set<UUID> messageIds = receipts.getContent().stream().map(NotificationReceiptEntity::getMessageId).collect(Collectors.toSet());
        Map<UUID, NotificationMessageEntity> messagesById = messageRepository.findByIdIn(messageIds).stream()
            .collect(Collectors.toMap(NotificationMessageEntity::getId, Function.identity()));
        Set<UUID> publisherIds = messagesById.values().stream()
            .map(NotificationMessageEntity::getCreatedBy)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        Map<UUID, UserAccount> publishersById = loadUsers(publisherIds);

        return PageResponse.from(receipts.map(receipt -> toRow(
            receipt,
            messagesById.get(receipt.getMessageId()),
            publishersById
        )));
    }

    @Transactional
    public NotificationApi.NotificationUnreadCount markAllRead(CurrentUserPrincipal principal) {
        ensureAuthenticated(principal);
        int changed = receiptRepository.markAllRead(principal.userId());
        log.info("消息全部已读 userId={} changed={} requestId={}", principal.userId(), changed, RequestIds.current());
        return new NotificationApi.NotificationUnreadCount(0);
    }

    @Transactional
    public void markRead(CurrentUserPrincipal principal, UUID messageId) {
        ensureAuthenticated(principal);
        Instant now = clock.instant();
        for (NotificationReceiptEntity receipt : receiptRepository.findByUserIdAndMessageId(principal.userId(), messageId)) {
            receipt.markRead(now);
            receiptRepository.save(receipt);
        }
    }

    @Transactional
    public NotificationApi.NotificationRow publishAnnouncement(CurrentUserPrincipal principal, NotificationApi.PublishAnnouncementRequest request) {
        ensureAuthenticated(principal);
        String scope = normalizeAnnouncementScope(request == null ? null : request.scope());
        String title = normalizeRequired(request == null ? null : request.title(), "NOTIFICATION_TITLE_REQUIRED", "请输入公告标题", 180);
        String content = normalizeRequired(request == null ? null : request.contentMarkdown(), "NOTIFICATION_CONTENT_REQUIRED", "请输入公告内容", 8000);
        UUID targetTenantId = "tenant".equals(scope) ? requireTenantAnnouncementTenant(principal, request == null ? null : request.tenantId()) : null;
        if ("global".equals(scope) && !"system_admin".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOTIFICATION_GLOBAL_FORBIDDEN", "只有系统管理员可以发布全局公告");
        }
        if ("tenant".equals(scope) && !"tenant_admin".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOTIFICATION_TENANT_FORBIDDEN", "只有租户管理员可以发布当前租户公告");
        }

        Instant now = clock.instant();
        NotificationMessageEntity message = messageRepository.save(NotificationMessageEntity.create(
            targetTenantId,
            scope,
            NotificationMessageEntity.CATEGORY_SYSTEM_NOTICE,
            title,
            content,
            "announcement",
            null,
            principal.userId(),
            now
        ));
        Set<UUID> recipientIds = "global".equals(scope)
            ? allActiveUserIds()
            : tenantActiveUserIds(targetTenantId);
        createReceipts(message, recipientIds, now);
        log.info(
            "公告消息已发布 scope={} tenantId={} userId={} recipientCount={} requestId={}",
            scope,
            targetTenantId,
            principal.userId(),
            recipientIds.size(),
            RequestIds.current()
        );
        return toRow(NotificationReceiptEntity.unread(message.getId(), targetTenantId, principal.userId(), now), message, loadUsers(Set.of(principal.userId())));
    }

    @Transactional
    public void publishScheduleResult(
        UUID tenantId,
        UUID recipientId,
        String title,
        String contentMarkdown,
        UUID scheduleId,
        UUID publisherId
    ) {
        if (recipientId == null) {
            return;
        }
        Instant now = clock.instant();
        NotificationMessageEntity message = messageRepository.save(NotificationMessageEntity.create(
            tenantId,
            "user",
            NotificationMessageEntity.CATEGORY_SCHEDULE_RESULT,
            normalizeRequired(title, "NOTIFICATION_TITLE_REQUIRED", "消息标题不能为空", 180),
            normalizeRequired(contentMarkdown, "NOTIFICATION_CONTENT_REQUIRED", "消息内容不能为空", 8000),
            "workflow_schedule",
            scheduleId,
            publisherId,
            now
        ));
        createReceipts(message, Set.of(recipientId), now);
    }

    private void createReceipts(NotificationMessageEntity message, Collection<UUID> recipientIds, Instant now) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return;
        }
        List<NotificationReceiptEntity> receipts = recipientIds.stream()
            .filter(id -> id != null)
            .distinct()
            .map(userId -> NotificationReceiptEntity.unread(message.getId(), message.getTenantId(), userId, now))
            .toList();
        receiptRepository.saveAll(receipts);
    }

    private Set<UUID> allActiveUserIds() {
        return userAccountRepository.findAll().stream()
            .filter(user -> ACTIVE_STATUS.equals(user.getStatus()))
            .map(UserAccount::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> tenantActiveUserIds(UUID tenantId) {
        return userMembershipRepository.findByTenantIdAndStatus(tenantId, ACTIVE_STATUS).stream()
            .map(UserMembershipEntity::getUserId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private UUID requireTenantAnnouncementTenant(CurrentUserPrincipal principal, UUID requestedTenantId) {
        UUID activeTenantId = principal.tenantId();
        if (activeTenantId == null || requestedTenantId == null || !activeTenantId.equals(requestedTenantId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOTIFICATION_TENANT_CONTEXT_INVALID", "租户公告只能发布到当前登录租户");
        }
        boolean hasTenantAdminAssignment = userRoleAssignmentRepository
            .findByUserIdAndRoleAndTenantId(principal.userId(), "tenant_admin", activeTenantId)
            .isPresent();
        if (!hasTenantAdminAssignment) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOTIFICATION_TENANT_FORBIDDEN", "当前账号不是该租户管理员");
        }
        return activeTenantId;
    }

    private NotificationApi.NotificationRow toRow(
        NotificationReceiptEntity receipt,
        NotificationMessageEntity message,
        Map<UUID, UserAccount> publishersById
    ) {
        if (message == null) {
            return new NotificationApi.NotificationRow(
                receipt.getMessageId(),
                "system_notice",
                "user",
                "消息已删除",
                "该消息主体已不存在。",
                receipt.getReadAt() == null,
                "System",
                receipt.getCreatedAt(),
                receipt.getReadAt()
            );
        }
        UserAccount publisher = message.getCreatedBy() == null ? null : publishersById.get(message.getCreatedBy());
        return new NotificationApi.NotificationRow(
            message.getId(),
            message.getCategory(),
            message.getScope(),
            message.getTitle(),
            message.getContentMarkdown(),
            receipt.getReadAt() == null,
            publisher == null ? "System" : publisher.getDisplayName(),
            message.getCreatedAt(),
            receipt.getReadAt()
        );
    }

    private Map<UUID, UserAccount> loadUsers(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userAccountRepository.findAllById(userIds).stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private String normalizeStatus(String status) {
        if ("read".equals(status) || "unread".equals(status)) {
            return status;
        }
        return "all";
    }

    private String normalizeAnnouncementScope(String scope) {
        if ("tenant".equals(scope)) {
            return "tenant";
        }
        return "global";
    }

    private String normalizeRequired(String value, String code, String message, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private void ensureAuthenticated(CurrentUserPrincipal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
        }
    }
}
