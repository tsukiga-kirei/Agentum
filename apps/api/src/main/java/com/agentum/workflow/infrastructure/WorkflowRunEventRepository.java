package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowRunEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunEventRepository extends JpaRepository<WorkflowRunEventEntity, UUID> {

    List<WorkflowRunEventEntity> findByRunIdOrderByEventTimeAsc(UUID runId);
}
