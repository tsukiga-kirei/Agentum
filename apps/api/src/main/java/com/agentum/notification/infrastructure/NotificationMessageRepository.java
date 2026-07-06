package com.agentum.notification.infrastructure;

import com.agentum.notification.domain.NotificationMessageEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationMessageRepository extends JpaRepository<NotificationMessageEntity, UUID> {

    List<NotificationMessageEntity> findByIdIn(Collection<UUID> ids);
}
