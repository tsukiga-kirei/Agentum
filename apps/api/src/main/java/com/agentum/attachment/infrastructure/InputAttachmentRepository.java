package com.agentum.attachment.infrastructure;

import com.agentum.attachment.domain.InputAttachmentEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import java.time.Instant;

public interface InputAttachmentRepository extends JpaRepository<InputAttachmentEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attachment from InputAttachmentEntity attachment where attachment.id = :id")
    Optional<InputAttachmentEntity> findByIdForUpdate(@Param("id") UUID id);

    List<InputAttachmentEntity> findByTenantIdAndRunIdAndNodeRunIdAndFieldIdOrderByCreatedAtAsc(UUID tenantId, UUID runId, UUID nodeRunId, String fieldId);
    List<InputAttachmentEntity> findByIdIn(Collection<UUID> ids);
    Optional<InputAttachmentEntity> findByIdAndTenantIdAndRunIdAndNodeRunId(UUID id, UUID tenantId, UUID runId, UUID nodeRunId);
    long countByTenantIdAndRunIdAndNodeRunIdAndFieldId(UUID tenantId, UUID runId, UUID nodeRunId, String fieldId);
    List<InputAttachmentEntity> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant now, Pageable pageable);
}
