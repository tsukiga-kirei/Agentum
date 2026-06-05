package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowVariableSnapshotEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVariableSnapshotRepository extends JpaRepository<WorkflowVariableSnapshotEntity, UUID> {

    List<WorkflowVariableSnapshotEntity> findByRunIdOrderByCreatedAtAsc(UUID runId);

    void deleteByRunIdAndNodeRunIdIn(UUID runId, Collection<UUID> nodeRunIds);
}
