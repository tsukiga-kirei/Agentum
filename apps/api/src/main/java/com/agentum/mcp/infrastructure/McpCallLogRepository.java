package com.agentum.mcp.infrastructure;

import com.agentum.mcp.domain.McpCallLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpCallLogRepository extends JpaRepository<McpCallLogEntity, UUID> {

    List<McpCallLogEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<McpCallLogEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);
}
