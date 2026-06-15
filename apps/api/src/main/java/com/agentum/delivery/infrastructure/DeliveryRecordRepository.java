package com.agentum.delivery.infrastructure;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecordEntity, UUID> {

    Optional<DeliveryRecordEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DeliveryRecordEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<DeliveryRecordEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);

    @Query(value = """
        select *
        from delivery_records
        where status = 'success'
          and (result_snapshot ->> 'adapter') = 'word_document'
          and (result_snapshot ->> 'storageKey') is not null
          and (result_snapshot ->> 'expiresAt') <= :nowIso
        order by completed_at asc nulls first
        limit :limit
        """, nativeQuery = true)
    List<DeliveryRecordEntity> findExpiredWordDocumentRecords(@Param("nowIso") String nowIso, @Param("limit") int limit);
}
