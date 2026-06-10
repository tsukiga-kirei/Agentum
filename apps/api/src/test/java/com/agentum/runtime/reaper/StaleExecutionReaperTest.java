package com.agentum.runtime.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import com.agentum.workflow.infrastructure.WorkflowRunExecutionJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 僵死/超时作业回收测试：超时、租约失联与消息丢失三类异常都必须把作业终态化，
 * 否则节点会永远假装「执行中」，前端也看不到「恢复进度」入口。
 */
class StaleExecutionReaperTest {

    private static final Instant NOW = Instant.parse("2026-06-10T08:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID NODE_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");

    private WorkflowRunExecutionJobRepository jobRepository;
    private WorkbenchRuntimeService workbenchRuntimeService;
    private RunExecutionLeaseService leaseService;
    private RunCancellationGuard cancellationGuard;
    private RunProgressStreamWriter streamWriter;
    private StaleExecutionReaper reaper;

    @BeforeEach
    void setUp() {
        jobRepository = mock(WorkflowRunExecutionJobRepository.class);
        workbenchRuntimeService = mock(WorkbenchRuntimeService.class);
        leaseService = mock(RunExecutionLeaseService.class);
        cancellationGuard = mock(RunCancellationGuard.class);
        streamWriter = mock(RunProgressStreamWriter.class);
        reaper = new StaleExecutionReaper(
            jobRepository,
            workbenchRuntimeService,
            leaseService,
            cancellationGuard,
            streamWriter,
            new RuntimeExecutionProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private WorkflowRunExecutionJobEntity runningJob(Instant enqueuedAt, Instant startedAt, Instant deadlineAt) {
        WorkflowRunExecutionJobEntity job = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, RUN_ID, NODE_RUN_ID, 1, null, "req-1", deadlineAt, enqueuedAt
        );
        job.markRunning("worker-a", startedAt);
        return job;
    }

    @Test
    void shouldFailRunningJobPastDeadlineAndRequestCancel() {
        WorkflowRunExecutionJobEntity job = runningJob(
            NOW.minusSeconds(4000), NOW.minusSeconds(3900), NOW.minusSeconds(10)
        );
        when(jobRepository.findByStatusInAndEnqueuedAtBefore(anyList(), any())).thenReturn(List.of(job));
        when(workbenchRuntimeService.failNodeIfActive(eq(RUN_ID), eq(NODE_RUN_ID), anyString(), anyString())).thenReturn(true);

        reaper.reap();

        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo("WORKBENCH_NODE_EXECUTION_TIMEOUT");
        verify(cancellationGuard).requestCancel(RUN_ID);
        verify(streamWriter).append(eq(RUN_ID), eq("node_failed"), any(Map.class));
        verify(streamWriter).append(RUN_ID, "message", "[DONE]");
    }

    @Test
    void shouldFailRunningJobWhenLeaseLostBeyondStaleThreshold() {
        // 启动 200s 前（> 默认 stale 阈值 120s），且租约已消失：执行进程死亡。
        WorkflowRunExecutionJobEntity job = runningJob(
            NOW.minusSeconds(300), NOW.minusSeconds(200), NOW.plusSeconds(3600)
        );
        when(jobRepository.findByStatusInAndEnqueuedAtBefore(anyList(), any())).thenReturn(List.of(job));
        when(leaseService.hasActiveLease(RUN_ID)).thenReturn(false);
        when(workbenchRuntimeService.failNodeIfActive(eq(RUN_ID), eq(NODE_RUN_ID), anyString(), anyString())).thenReturn(true);

        reaper.reap();

        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo("WORKBENCH_NODE_EXECUTION_STALE");
    }

    @Test
    void shouldKeepRunningJobWithActiveLease() {
        WorkflowRunExecutionJobEntity job = runningJob(
            NOW.minusSeconds(300), NOW.minusSeconds(200), NOW.plusSeconds(3600)
        );
        when(jobRepository.findByStatusInAndEnqueuedAtBefore(anyList(), any())).thenReturn(List.of(job));
        when(leaseService.hasActiveLease(RUN_ID)).thenReturn(true);

        reaper.reap();

        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_RUNNING);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void shouldFailQueuedJobStuckBeyondNodeTimeout() {
        // queued 超过节点超时（默认 1800s）仍未被消费：MQ 消息丢失或 Worker 长期不可用。
        WorkflowRunExecutionJobEntity job = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, RUN_ID, NODE_RUN_ID, 1, null, "req-1", NOW.plusSeconds(3600), NOW.minusSeconds(2000)
        );
        when(jobRepository.findByStatusInAndEnqueuedAtBefore(anyList(), any())).thenReturn(List.of(job));
        when(workbenchRuntimeService.failNodeIfActive(eq(RUN_ID), eq(NODE_RUN_ID), anyString(), anyString())).thenReturn(true);

        reaper.reap();

        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo("WORKBENCH_NODE_EXECUTION_STALE");
    }

    @Test
    void shouldKeepRecentQueuedJob() {
        WorkflowRunExecutionJobEntity job = WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, RUN_ID, NODE_RUN_ID, 1, null, "req-1", NOW.plusSeconds(3600), NOW.minusSeconds(10)
        );
        when(jobRepository.findByStatusInAndEnqueuedAtBefore(anyList(), any())).thenReturn(List.of(job));

        reaper.reap();

        assertThat(job.getStatus()).isEqualTo(WorkflowRunExecutionJobEntity.STATUS_QUEUED);
        verify(jobRepository, never()).save(any());
    }
}
