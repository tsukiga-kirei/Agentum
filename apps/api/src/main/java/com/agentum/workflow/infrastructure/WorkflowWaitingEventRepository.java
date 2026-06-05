package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowWaitingEventEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowWaitingEventRepository extends JpaRepository<WorkflowWaitingEventEntity, UUID> {

    Optional<WorkflowWaitingEventEntity> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);

    List<WorkflowWaitingEventEntity> findByRunIdAndStatusOrderByCreatedAtDesc(UUID runId, String status);

    List<WorkflowWaitingEventEntity> findByRunIdInAndStatus(Collection<UUID> runIds, String status);

    @Query("""
        select count(event) from WorkflowWaitingEventEntity event
        where event.tenantId = :tenantId
          and event.status = 'open'
          and (:tenantManager = true or (event.waitingForType = 'user' and event.waitingForId = :operatorUserId))
        """)
    long countVisibleOpenTodos(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager
    );

    @Query("""
        select event from WorkflowWaitingEventEntity event
        where event.tenantId = :tenantId
          and event.status = 'open'
          and (:tenantManager = true or (event.waitingForType = 'user' and event.waitingForId = :operatorUserId))
        order by event.createdAt desc
        """)
    List<WorkflowWaitingEventEntity> findVisibleOpenTodos(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        Pageable pageable
    );
}
