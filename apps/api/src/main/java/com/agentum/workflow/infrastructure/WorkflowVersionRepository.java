package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowVersionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, UUID> {

    Optional<WorkflowVersionEntity> findTopByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);
}
