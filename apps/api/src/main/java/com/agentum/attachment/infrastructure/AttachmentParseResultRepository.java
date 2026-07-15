package com.agentum.attachment.infrastructure;

import com.agentum.attachment.domain.AttachmentParseResultEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentParseResultRepository extends JpaRepository<AttachmentParseResultEntity, UUID> {
    Optional<AttachmentParseResultEntity> findByAttachmentId(UUID attachmentId);
    List<AttachmentParseResultEntity> findByAttachmentIdIn(Collection<UUID> attachmentIds);
}
