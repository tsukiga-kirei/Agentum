package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowClusterAgentRunEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowClusterAgentRunRepository extends JpaRepository<WorkflowClusterAgentRunEntity, UUID> {

    List<WorkflowClusterAgentRunEntity> findByNodeRunIdOrderByAgentIndexAsc(UUID nodeRunId);

    Optional<WorkflowClusterAgentRunEntity> findByNodeRunIdAndAgentIndex(UUID nodeRunId, int agentIndex);

    void deleteByNodeRunId(UUID nodeRunId);

    void deleteByNodeRunIdAndStatusNot(UUID nodeRunId, String status);

    void deleteByRunIdAndNodeRunIdIn(UUID runId, List<UUID> nodeRunIds);
}
