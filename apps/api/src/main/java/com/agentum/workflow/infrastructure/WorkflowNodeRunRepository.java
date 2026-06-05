package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRunEntity, UUID> {

    List<WorkflowNodeRunEntity> findByRunIdOrderBySortOrderAsc(UUID runId);

    Optional<WorkflowNodeRunEntity> findByIdAndRunId(UUID id, UUID runId);
}
