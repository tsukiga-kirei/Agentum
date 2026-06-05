package com.agentum.delivery.infrastructure;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecordEntity, UUID> {

    List<DeliveryRecordEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<DeliveryRecordEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);
}
