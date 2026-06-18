package com.agentum.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.NodeExecutionService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NodeExecuteCommandListenerTest {

    @Test
    void shouldRestoreRequestIdIntoWorkerMdc() {
        NodeExecutionService executionService = mock(NodeExecutionService.class);
        AtomicReference<String> requestIdDuringExecution = new AtomicReference<>();
        doAnswer(invocation -> {
            requestIdDuringExecution.set(RequestIds.current());
            return null;
        }).when(executionService).execute(any());
        NodeExecuteCommandListener listener = new NodeExecuteCommandListener(executionService);
        NodeExecuteCommand command = NodeExecuteCommand.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            UUID.randomUUID(),
            "req_worker_trace",
            1,
            Instant.parse("2026-06-18T08:00:00Z")
        );

        listener.onNodeExecuteCommand(command);

        assertThat(requestIdDuringExecution).hasValue("req_worker_trace");
        assertThat(RequestIds.current()).isEqualTo("req_unknown");
    }
}
