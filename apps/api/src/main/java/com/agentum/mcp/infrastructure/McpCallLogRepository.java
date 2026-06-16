package com.agentum.mcp.infrastructure;

import com.agentum.mcp.domain.McpCallLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpCallLogRepository extends JpaRepository<McpCallLogEntity, UUID> {

    List<McpCallLogEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<McpCallLogEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);

    @Query("""
        select m from McpCallLogEntity m
        where m.tenantId = :tenantId
          and (:status = '' or m.status = :status)
          and (:keyword = '' or lower(m.toolName) like lower(concat('%', :keyword, '%')) or lower(m.capabilityCode) like lower(concat('%', :keyword, '%')))
    """)
    Page<McpCallLogEntity> findWithFilters(
        @Param("tenantId") UUID tenantId,
        @Param("status") String status,
        @Param("keyword") String keyword,
        Pageable pageable
    );
}

