package com.agentum.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.mcp.domain.McpCallLogEntity;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallLogFailureLatencyTest {

    private static final Instant STARTED_AT = Instant.parse("2026-06-20T08:00:00Z");

    @Test
    void shouldRecordElapsedTimeWhenModelCallFails() {
        WorkflowRunEntity run = run();
        WorkflowNodeRunEntity nodeRun = nodeRun();
        ModelCallLogEntity log = ModelCallLogEntity.started(
            run,
            nodeRun,
            UUID.randomUUID(),
            "openai-compatible",
            "gpt-test",
            Map.of(),
            STARTED_AT
        );

        log.fail("MODEL_CALL_FAILED", "模型调用失败", STARTED_AT.plusMillis(137));

        assertThat(log.getStatus()).isEqualTo("failed");
        assertThat(log.getLatencyMs()).isEqualTo(137L);
    }

    @Test
    void shouldRecordElapsedTimeWhenMcpCallFails() {
        WorkflowRunEntity run = run();
        WorkflowNodeRunEntity nodeRun = nodeRun();
        SystemCapabilityEntity capability = mock(SystemCapabilityEntity.class);
        when(capability.getId()).thenReturn(UUID.randomUUID());
        when(capability.getCode()).thenReturn("financial_report");
        McpCallLogEntity log = McpCallLogEntity.started(
            run,
            nodeRun,
            capability,
            "get_financial_report",
            Map.of(),
            STARTED_AT
        );

        log.fail("MCP_CALL_FAILED", "MCP 调用失败", STARTED_AT.plusMillis(842));

        assertThat(log.getStatus()).isEqualTo("failed");
        assertThat(log.getLatencyMs()).isEqualTo(842L);
    }

    private WorkflowRunEntity run() {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getTenantId()).thenReturn(UUID.randomUUID());
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getWorkflowId()).thenReturn(UUID.randomUUID());
        when(run.getWorkflowVersionId()).thenReturn(UUID.randomUUID());
        return run;
    }

    private WorkflowNodeRunEntity nodeRun() {
        WorkflowNodeRunEntity nodeRun = mock(WorkflowNodeRunEntity.class);
        when(nodeRun.getId()).thenReturn(UUID.randomUUID());
        return nodeRun;
    }
}
