package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowEdgeDefinitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEdgeDefinitionRepository extends JpaRepository<WorkflowEdgeDefinitionEntity, UUID> {

    List<WorkflowEdgeDefinitionEntity> findByWorkflowIdOrderBySortOrderAsc(UUID workflowId);

    void deleteByWorkflowId(UUID workflowId);
}
