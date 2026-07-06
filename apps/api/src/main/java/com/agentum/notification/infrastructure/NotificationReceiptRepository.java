package com.agentum.notification.infrastructure;

import com.agentum.notification.domain.NotificationReceiptEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationReceiptRepository extends JpaRepository<NotificationReceiptEntity, UUID> {

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Query("""
        select receipt from NotificationReceiptEntity receipt
        where receipt.userId = :userId
          and (:status = 'all'
            or (:status = 'unread' and receipt.readAt is null)
            or (:status = 'read' and receipt.readAt is not null))
        """)
    Page<NotificationReceiptEntity> searchByUser(
        @Param("userId") UUID userId,
        @Param("status") String status,
        Pageable pageable
    );

    @Modifying
    @Query("update NotificationReceiptEntity receipt set receipt.readAt = CURRENT_TIMESTAMP where receipt.userId = :userId and receipt.readAt is null")
    int markAllRead(@Param("userId") UUID userId);

    List<NotificationReceiptEntity> findByUserIdAndMessageId(UUID userId, UUID messageId);
}
