package com.agentum.schedule.infrastructure;

import com.agentum.schedule.domain.WorkflowScheduleExecutionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowScheduleExecutionRepository extends JpaRepository<WorkflowScheduleExecutionEntity, UUID> {

    Page<WorkflowScheduleExecutionEntity> findByTenantIdAndScheduleIdOrderByStartedAtDesc(UUID tenantId, UUID scheduleId, Pageable pageable);

    List<WorkflowScheduleExecutionEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    List<WorkflowScheduleExecutionEntity> findByScheduleIdAndStatus(UUID scheduleId, String status);

    List<WorkflowScheduleExecutionEntity> findByRunId(UUID runId);
}
