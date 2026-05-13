package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowNodeDefinitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowNodeDefinitionRepository extends JpaRepository<WorkflowNodeDefinitionEntity, UUID> {

    List<WorkflowNodeDefinitionEntity> findByWorkflowIdOrderBySortOrderAsc(UUID workflowId);

    void deleteByWorkflowId(UUID workflowId);
}
