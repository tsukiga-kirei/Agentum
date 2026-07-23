package com.agentum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.auth.application.AuthTokenClaims;
import com.agentum.auth.application.AuthTokenService;
import com.agentum.shared.logging.LogContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class BearerTokenAuthenticationFilterTest {

    @AfterEach
    void clearThreadContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldExposeTrustedTenantContextOnlyDuringAuthenticatedRequest() throws Exception {
        AuthTokenService tokenService = mock(AuthTokenService.class);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(tokenService.parse("valid-token")).thenReturn(new AuthTokenClaims(
            userId,
            "operator",
            tenantId,
            "business",
            "business",
            UUID.randomUUID(),
            Instant.parse("2026-07-23T01:00:00Z"),
            Instant.parse("2026-07-23T02:00:00Z")
        ));
        BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(tokenService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants/" + tenantId + "/workbench/summary");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> contextDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
            contextDuringRequest.set(MDC.getCopyOfContextMap())
        );

        assertThat(contextDuringRequest.get())
            .containsEntry(LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE)
            .containsEntry(LogContext.TENANT_ID_KEY, tenantId.toString())
            .containsEntry(LogContext.USER_ID_KEY, userId.toString())
            .containsEntry(LogContext.ROLE_KEY, "business");
        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
        assertThat(MDC.get(LogContext.SCOPE_KEY)).isNull();
    }
}
