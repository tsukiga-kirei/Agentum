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

    java.util.List<WorkflowRunEntity> findTop100ByAutoExecutionTrueAndManualTakeoverFalseAndStateInOrderByUpdatedAtAsc(Collection<String> states);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRunEntity r where r.id = :id")
    Optional<WorkflowRunEntity> lockById(@Param("id") UUID id);

    java.util.List<WorkflowRunEntity> findTop100BySavedFalseAndTemporaryLastSeenAtBeforeOrderByTemporaryLastSeenAtAsc(java.time.Instant cutoff);

    // 不通过实体 save 更新心跳，避免 Worker 保存旧快照时覆盖页面的最新连接时间。
    @org.springframework.data.jpa.repository.Modifying
    @Query("update WorkflowRunEntity r set r.temporaryLastSeenAt = :now where r.id = :id and r.saved = false")
    int touchTemporaryRun(@Param("id") UUID id, @Param("now") java.time.Instant now);

    boolean existsByWorkflowId(UUID workflowId);

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
          and run.saved = true
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
          and (:state = '' or run.state = :state)
          and (:triggerSource = '' or run.triggerSource = :triggerSource)
        """)
    Page<WorkflowRunEntity> searchVisibleActiveRuns(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        @Param("keyword") String keyword,
        @Param("state") String state,
        @Param("triggerSource") String triggerSource,
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
          and (:triggerSource = '' or run.triggerSource = :triggerSource)
        """)
    Page<WorkflowRunEntity> searchVisibleCompletedRuns(
        @Param("tenantId") UUID tenantId,
        @Param("operatorUserId") UUID operatorUserId,
        @Param("tenantManager") boolean tenantManager,
        @Param("keyword") String keyword,
        @Param("triggerSource") String triggerSource,
        Pageable pageable
    );

    @Query("""
        select run from WorkflowRunEntity run
        where run.tenantId = :tenantId
          and run.saved = true
          and (
            :keyword = ''
            or lower(run.title) like lower(concat('%', :keyword, '%'))
            or lower(run.workflowName) like lower(concat('%', :keyword, '%'))
            or lower(run.runNumber) like lower(concat('%', :keyword, '%'))
          )
          and (:state = '' or run.state = :state)
          and (:triggerSource = '' or run.triggerSource = :triggerSource)
        """)
    Page<WorkflowRunEntity> searchAllRunsForAudit(
        @Param("tenantId") UUID tenantId,
        @Param("keyword") String keyword,
        @Param("state") String state,
        @Param("triggerSource") String triggerSource,
        Pageable pageable
    );
}
