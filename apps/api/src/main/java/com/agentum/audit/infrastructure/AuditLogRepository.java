package com.agentum.audit.infrastructure;

import com.agentum.audit.domain.AuditLogEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    @Query("SELECT a FROM AuditLogEntity a WHERE a.tenantId = :tenantId " +
           "AND (:actionType IS NULL OR a.actionType = :actionType) " +
           "AND (:operatorId IS NULL OR a.operatorId = :operatorId)")
    Page<AuditLogEntity> findWithFilters(
        @Param("tenantId") UUID tenantId,
        @Param("actionType") String actionType,
        @Param("operatorId") UUID operatorId,
        Pageable pageable
    );
}
