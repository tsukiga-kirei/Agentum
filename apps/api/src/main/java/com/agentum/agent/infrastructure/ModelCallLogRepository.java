package com.agentum.agent.infrastructure;

import com.agentum.agent.domain.ModelCallLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelCallLogRepository extends JpaRepository<ModelCallLogEntity, UUID> {

    List<ModelCallLogEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);

    List<ModelCallLogEntity> findByNodeRunIdOrderByCreatedAtDesc(UUID nodeRunId);

    @Query("""
        select m from ModelCallLogEntity m
        where m.tenantId = :tenantId
          and (:status = '' or m.status = :status)
          and (:keyword = '' or lower(m.modelName) like lower(concat('%', :keyword, '%')))
    """)
    Page<ModelCallLogEntity> findWithFilters(
        @Param("tenantId") UUID tenantId,
        @Param("status") String status,
        @Param("keyword") String keyword,
        Pageable pageable
    );
}

