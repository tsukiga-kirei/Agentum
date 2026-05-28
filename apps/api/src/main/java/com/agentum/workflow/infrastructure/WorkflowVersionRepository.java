package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowVersionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, UUID> {

    Optional<WorkflowVersionEntity> findTopByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);

    /**
     * 批量查询多个工作流各自的最新发布版本，供业务工作台“可发起流程”一次性聚合，
     * 避免按 workflow 逐个回查触发 N+1。
     */
    @Query("""
        select version from WorkflowVersionEntity version
        where version.workflowId in :workflowIds
          and version.versionNumber = (
            select max(latest.versionNumber)
            from WorkflowVersionEntity latest
            where latest.workflowId = version.workflowId
          )
        """)
    List<WorkflowVersionEntity> findLatestByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);
}
