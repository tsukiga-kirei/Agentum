package com.agentum.mcp.infrastructure;
import com.agentum.mcp.domain.McpDatasetEntity;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface McpDatasetRepository extends JpaRepository<McpDatasetEntity, UUID> {
    Optional<McpDatasetEntity> findByIdAndTenantIdAndRunId(UUID id, UUID tenantId, UUID runId);
    List<McpDatasetEntity> findByNodeRunIdOrderByCreatedAtAsc(UUID nodeRunId);
    @Query(value = "SELECT d.* FROM mcp_datasets d WHERE NOT EXISTS (SELECT 1 FROM workflow_runs r WHERE r.id = d.run_id) ORDER BY d.created_at LIMIT 100", nativeQuery = true)
    List<McpDatasetEntity> findOrphanedRunResults();
}
