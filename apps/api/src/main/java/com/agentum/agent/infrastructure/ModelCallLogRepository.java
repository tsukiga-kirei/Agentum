package com.agentum.agent.infrastructure;

import com.agentum.agent.domain.ModelCallLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallLogRepository extends JpaRepository<ModelCallLogEntity, UUID> {

    List<ModelCallLogEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<ModelCallLogEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);
}
