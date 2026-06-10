package com.agentum.workflow.infrastructure;

import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunExecutionJobRepository extends JpaRepository<WorkflowRunExecutionJobEntity, UUID> {

    Optional<WorkflowRunExecutionJobEntity> findByIdempotencyKey(String idempotencyKey);

    List<WorkflowRunExecutionJobEntity> findByRunIdAndStatusIn(UUID runId, Collection<String> statuses);

    Optional<WorkflowRunExecutionJobEntity> findFirstByRunIdOrderByEnqueuedAtDesc(UUID runId);

    Optional<WorkflowRunExecutionJobEntity> findFirstByNodeRunIdOrderByAttemptDesc(UUID nodeRunId);

    List<WorkflowRunExecutionJobEntity> findByStatusInAndEnqueuedAtBefore(Collection<String> statuses, Instant before);
}
