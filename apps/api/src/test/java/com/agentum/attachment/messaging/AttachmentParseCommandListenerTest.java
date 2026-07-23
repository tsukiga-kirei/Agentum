package com.agentum.attachment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agentum.attachment.application.AttachmentParseService;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.logging.LogContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AttachmentParseCommandListenerTest {

    @Test
    void shouldRestoreTenantAndRunContextDuringParsing() {
        AttachmentParseService parseService = mock(AttachmentParseService.class);
        AtomicReference<Map<String, String>> contextDuringParsing = new AtomicReference<>();
        doAnswer(invocation -> {
            contextDuringParsing.set(MDC.getCopyOfContextMap());
            return null;
        }).when(parseService).parse(any());
        AttachmentParseCommandListener listener = new AttachmentParseCommandListener(parseService);
        UUID attachmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID nodeRunId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        listener.onCommand(new AttachmentParseCommand(
            attachmentId,
            tenantId,
            runId,
            nodeRunId,
            userId,
            "req_attachment_trace"
        ));

        assertThat(contextDuringParsing.get())
            .containsEntry(RequestIds.MDC_KEY, "req_attachment_trace")
            .containsEntry(LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE)
            .containsEntry(LogContext.TENANT_ID_KEY, tenantId.toString())
            .containsEntry(LogContext.USER_ID_KEY, userId.toString())
            .containsEntry(LogContext.RUN_ID_KEY, runId.toString())
            .containsEntry(LogContext.NODE_RUN_ID_KEY, nodeRunId.toString());
        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
    }
}
