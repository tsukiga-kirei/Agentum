package com.agentum.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.auth.application.CurrentUserPrincipal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldOpenTenantContextAndRestorePreviousValues() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MDC.put(LogContext.SCOPE_KEY, LogContext.SYSTEM_SCOPE);
        MDC.put(LogContext.USER_ID_KEY, "previous-user");

        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            userId,
            "operator",
            tenantId,
            "business",
            "business",
            UUID.randomUUID()
        );

        try (LogContext.Scope ignored = LogContext.openForPrincipal(principal)) {
            assertThat(MDC.get(LogContext.SCOPE_KEY)).isEqualTo(LogContext.TENANT_SCOPE);
            assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isEqualTo(tenantId.toString());
            assertThat(MDC.get(LogContext.USER_ID_KEY)).isEqualTo(userId.toString());
            assertThat(MDC.get(LogContext.ROLE_KEY)).isEqualTo("business");
        }

        assertThat(MDC.get(LogContext.SCOPE_KEY)).isEqualTo(LogContext.SYSTEM_SCOPE);
        assertThat(MDC.get(LogContext.USER_ID_KEY)).isEqualTo("previous-user");
        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
        assertThat(MDC.get(LogContext.ROLE_KEY)).isNull();
    }

    @Test
    void shouldUseSystemScopeForSystemAdministrator() {
        MDC.put(LogContext.TENANT_ID_KEY, UUID.randomUUID().toString());
        MDC.put(LogContext.RUN_ID_KEY, UUID.randomUUID().toString());
        CurrentUserPrincipal principal = new CurrentUserPrincipal(
            UUID.randomUUID(),
            "admin",
            null,
            "system_admin",
            "system_admin",
            UUID.randomUUID()
        );

        try (LogContext.Scope ignored = LogContext.openForPrincipal(principal)) {
            assertThat(MDC.get(LogContext.SCOPE_KEY)).isEqualTo(LogContext.SYSTEM_SCOPE);
            assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
            assertThat(MDC.get(LogContext.RUN_ID_KEY)).isNull();
        }

        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNotNull();
        assertThat(MDC.get(LogContext.RUN_ID_KEY)).isNotNull();
    }

    @Test
    void shouldRestoreCapturedContextInAnotherThreadAndClearMissingFields() {
        UUID tenantId = UUID.randomUUID();
        MDC.put(LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE);
        MDC.put(LogContext.TENANT_ID_KEY, tenantId.toString());
        Map<String, String> snapshot = LogContext.snapshot();
        MDC.clear();
        MDC.put(LogContext.USER_ID_KEY, "stale-user");

        try (LogContext.Scope ignored = LogContext.openSnapshot(snapshot)) {
            assertThat(MDC.get(LogContext.SCOPE_KEY)).isEqualTo(LogContext.TENANT_SCOPE);
            assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isEqualTo(tenantId.toString());
            assertThat(MDC.get(LogContext.USER_ID_KEY)).isNull();
        }

        assertThat(MDC.get(LogContext.TENANT_ID_KEY)).isNull();
        assertThat(MDC.get(LogContext.USER_ID_KEY)).isEqualTo("stale-user");
    }
}
