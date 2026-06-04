package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowAccessGrantEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowAccessGrantRepository extends JpaRepository<WorkflowAccessGrantEntity, UUID> {

    List<WorkflowAccessGrantEntity> findByWorkflowId(UUID workflowId);

    List<WorkflowAccessGrantEntity> findByWorkflowIdInAndGranteeUserId(Collection<UUID> workflowIds, UUID granteeUserId);

    void deleteByWorkflowId(UUID workflowId);
}
