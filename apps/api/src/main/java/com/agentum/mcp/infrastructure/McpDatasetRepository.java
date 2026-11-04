package com.agentum.mcp.infrastructure;
import com.agentum.mcp.domain.McpDatasetEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface McpDatasetRepository extends JpaRepository<McpDatasetEntity, UUID> {
    Optional<McpDatasetEntity> findByIdAndTenantIdAndRunId(UUID id, UUID tenantId, UUID runId);
    List<McpDatasetEntity> findByNodeRunIdOrderByCreatedAtAsc(UUID nodeRunId);
    List<McpDatasetEntity> findTop100ByExpiresAtBefore(Instant now);
}
