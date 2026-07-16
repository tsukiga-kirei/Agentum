package com.agentum.workbench.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.agent.application.AgentRuntimeService;
import com.agentum.delivery.application.DeliveryRuntimeService;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.agentum.runtime.messaging.NodeExecuteCommand;
import com.agentum.runtime.messaging.NodeExecuteCommandPublisher;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import com.agentum.workflow.infrastructure.WorkflowClusterAgentRunRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunExecutionJobRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 节点 Worker 竞态测试：中断/restart 后旧 job 已终态化时，迟到的 Worker 不得落库成功结果。
 */
class NodeExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T08:50:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID NODE_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private WorkbenchRuntimeService workbenchRuntimeService;
    private WorkflowRunRepository workflowRunRepository;
    private WorkflowNodeRunRepository workflowNodeRunRepository;
    private WorkflowRunExecutionJobRepository jobRepository;
    private WorkflowRuntimeExecutor workflowRuntimeExecutor;
    private RunProgressStreamWriter streamWriter;
    private RunExecutionLeaseService leaseService;
    private RunCancellationGuard cancellationGuard;
    private NodeExecutionService service;

    @BeforeEach
    void setUp() {
        workbenchRuntimeService = mock(WorkbenchRuntimeService.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        workflowNodeRunRepository = mock(WorkflowNodeRunRepository.class);
        jobRepository = mock(WorkflowRunExecutionJobRepository.class);
        workflowRuntimeExecutor = mock(WorkflowRuntimeExecutor.class);
        streamWriter = mock(RunProgressStreamWriter.class);
        leaseService = mock(RunExecutionLeaseService.class);
        cancellationGuard = mock(RunCancellationGuard.class);
        service = new NodeExecutionService(
            workbenchRuntimeService,
            workflowRunRepository,
            workflowNodeRunRepository,
            jobRepository,
            mock(WorkflowClusterAgentRunRepository.class),
            mock(AgentRuntimeService.class),
            mock(DeliveryRuntimeService.class),
            workflowRuntimeExecutor,
            streamWriter,
            leaseService,
            cancellationGuard,
            mock(NodeExecuteCommandPublisher.class),
            new RuntimeExecutionProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldNotPersistSuccessWhenJobAlreadyCanceled() {
        WorkflowRunExecutionJobEntity job = queuedJob();
        UUID jobId = job.getId();

        WorkflowRunEntity run = runningRun();
        WorkflowNodeRunEntity node = runningNode();

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(leaseService.tryAcquire(eq(RUN_ID), anyString())).thenReturn(true);
        when(workflowNodeRunRepository.findById(NODE_RUN_ID)).thenReturn(Optional.of(node));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(RUN_ID)).thenReturn(List.of(node));
        when(workflowRuntimeExecutor.execute(any())).thenAnswer(invocation -> {
            // 模拟模型长耗时期间用户中断：dispatch 返回时 job 已被标为 canceled。
            job.markCanceled(NOW);
            return new WorkflowRuntimeExecutor.ExecutionResult(Map.of("summary", "旧 Worker 输出"));
        });

        NodeExecuteCommand command = NodeExecuteCommand.of(
            jobId,
            TENANT_ID,
            RUN_ID,
            NODE_RUN_ID,
            "condition",
            OPERATOR_ID,
            "req-stale",
            1,
            NOW
        );

        service.execute(command);

        verify(workbenchRuntimeService, never()).saveNodeSuccess(eq(RUN_ID), eq(NODE_RUN_ID), any(), any());
        verify(streamWriter).appendIfActiveJob(eq(RUN_ID), eq(jobId), eq("node_started"), any());
        verify(streamWriter, never()).appendIfActiveJob(eq(RUN_ID), eq(jobId), eq("node_completed"), any());
        verify(streamWriter, never()).appendIfActiveJob(RUN_ID, jobId, "message", "[DONE]");
        verify(streamWriter, never()).append(eq(RUN_ID), anyString(), any());
    }

    private WorkflowRunExecutionJobEntity queuedJob() {
        return WorkflowRunExecutionJobEntity.queued(
            TENANT_ID, RUN_ID, NODE_RUN_ID, 1, OPERATOR_ID, "req-1", NOW.plusSeconds(1800), NOW
        );
    }

    private WorkflowRunEntity runningRun() {
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "竞态任务",
            "测试流程",
            OPERATOR_ID,
            1,
            "20260610-TEST",
            NOW
        );
        run.markRunning("agent_step", "智能体", "agent", 0, NOW);
        return run;
    }

    private WorkflowNodeRunEntity runningNode() {
        WorkflowNodeRunEntity node = WorkflowNodeRunEntity.pending(
            RUN_ID,
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent_step",
            "condition",
            "条件",
            Map.of(),
            Map.of(),
            Map.of(),
            0,
            NOW
        );
        node.start(NOW);
        return node;
    }
}
