package com.agentum.schedule.infrastructure;

import com.agentum.schedule.domain.WorkflowScheduleEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowScheduleRepository extends JpaRepository<WorkflowScheduleEntity, UUID> {

    Optional<WorkflowScheduleEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByWorkflowId(UUID workflowId);

    @Query("""
        select schedule from WorkflowScheduleEntity schedule
        where schedule.tenantId = :tenantId
          and (:tenantManager = true or schedule.ownerId = :ownerId)
          and (:status = '' or schedule.status = :status)
          and (
            :keyword = ''
            or lower(schedule.name) like lower(concat('%', :keyword, '%'))
            or lower(schedule.workflowName) like lower(concat('%', :keyword, '%'))
          )
        """)
    Page<WorkflowScheduleEntity> searchVisible(
        @Param("tenantId") UUID tenantId,
        @Param("ownerId") UUID ownerId,
        @Param("tenantManager") boolean tenantManager,
        @Param("keyword") String keyword,
        @Param("status") String status,
        Pageable pageable
    );

    @Query("""
        select schedule from WorkflowScheduleEntity schedule
        where schedule.status = 'active'
          and schedule.nextRunAt is not null
          and schedule.nextRunAt <= :now
        order by schedule.nextRunAt asc
        """)
    List<WorkflowScheduleEntity> findDueSchedules(@Param("now") Instant now, Pageable pageable);
}
