package com.agentum.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agentum.shared.api.RequestIds;
import com.agentum.shared.logging.LogContext;
import com.agentum.workbench.application.NodeExecutionService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class NodeExecuteCommandListenerTest {

    @Test
    void shouldRestoreRequestIdIntoWorkerMdc() {
        NodeExecutionService executionService = mock(NodeExecutionService.class);
        AtomicReference<Map<String, String>> contextDuringExecution = new AtomicReference<>();
        doAnswer(invocation -> {
            contextDuringExecution.set(MDC.getCopyOfContextMap());
            return null;
        }).when(executionService).execute(any());
        NodeExecuteCommandListener listener = new NodeExecuteCommandListener(executionService);
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID nodeRunId = UUID.randomUUID();
        UUID operatorUserId = UUID.randomUUID();
        NodeExecuteCommand command = NodeExecuteCommand.of(
            jobId,
            tenantId,
            runId,
            nodeRunId,
            "agent",
            operatorUserId,
            "req_worker_trace",
            1,
            Instant.parse("2026-06-18T08:00:00Z")
        );

        listener.onNodeExecuteCommand(command);

        assertThat(contextDuringExecution.get())
            .containsEntry(RequestIds.MDC_KEY, "req_worker_trace")
            .containsEntry(LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE)
            .containsEntry(LogContext.TENANT_ID_KEY, tenantId.toString())
            .containsEntry(LogContext.USER_ID_KEY, operatorUserId.toString())
            .containsEntry(LogContext.RUN_ID_KEY, runId.toString())
            .containsEntry(LogContext.JOB_ID_KEY, jobId.toString())
            .containsEntry(LogContext.NODE_RUN_ID_KEY, nodeRunId.toString());
        assertThat(RequestIds.current()).isEqualTo("req_unknown");
        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
    }
}
