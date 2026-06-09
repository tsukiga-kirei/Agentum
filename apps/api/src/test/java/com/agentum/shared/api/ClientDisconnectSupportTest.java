package com.agentum.shared.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ClientDisconnectSupportTest {

    @Test
    void shouldDetectBrokenPipeAndClientAbort() {
        assertTrue(ClientDisconnectSupport.isClientDisconnect(new AsyncRequestNotUsableException("broken pipe", new IOException("Broken pipe"))));
        assertTrue(ClientDisconnectSupport.isClientDisconnect(new ClientAbortException("connection reset")));
        assertFalse(ClientDisconnectSupport.isClientDisconnect(new IllegalStateException("业务状态异常")));
    }
}
