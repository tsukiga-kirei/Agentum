package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowVariableDefinitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVariableDefinitionRepository extends JpaRepository<WorkflowVariableDefinitionEntity, UUID> {

    List<WorkflowVariableDefinitionEntity> findByWorkflowIdOrderBySortOrderAsc(UUID workflowId);

    void deleteByWorkflowId(UUID workflowId);
}
