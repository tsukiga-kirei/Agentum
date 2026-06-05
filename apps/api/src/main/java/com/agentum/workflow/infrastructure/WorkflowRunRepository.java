package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowRunEntity;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    Optional<WorkflowRunEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
        select count(run) from WorkflowRunEntity run
        where run.tenantId = :tenantId
          and run.saved = true
          and run.state <> 'completed'
          and (:tenantManager = true or run.createdBy = :operatorUserId)
        """)
    long countVisibleActiveRuns(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager
    );

    @Query("""
        select count(run) from WorkflowRunEntity run
        where run.tenantId = :tenantId
          and run.state in :states
          and (:tenantManager = true or run.createdBy = :operatorUserId)
        """)
    long countVisibleByStateIn(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        @Param("states") Collection<String> states
    );

    @Query("""
        select run from WorkflowRunEntity run
        where run.tenantId = :tenantId
          and run.saved = true
          and run.state <> 'completed'
          and (:tenantManager = true or run.createdBy = :operatorUserId)
          and (
            :keyword = ''
            or lower(run.title) like lower(concat('%', :keyword, '%'))
            or lower(run.runNumber) like lower(concat('%', :keyword, '%'))
            or lower(run.workflowName) like lower(concat('%', :keyword, '%'))
          )
        """)
    Page<WorkflowRunEntity> searchVisibleActiveRuns(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        @Param("keyword") String keyword,
        Pageable pageable
    );

    @Query("""
        select run from WorkflowRunEntity run
        where run.tenantId = :tenantId
          and run.saved = true
          and run.state = 'completed'
          and (:tenantManager = true or run.createdBy = :operatorUserId)
          and (
            :keyword = ''
            or lower(run.title) like lower(concat('%', :keyword, '%'))
            or lower(run.runNumber) like lower(concat('%', :keyword, '%'))
            or lower(run.workflowName) like lower(concat('%', :keyword, '%'))
          )
        """)
    Page<WorkflowRunEntity> searchVisibleCompletedRuns(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        @Param("keyword") String keyword,
        Pageable pageable
    );
}
