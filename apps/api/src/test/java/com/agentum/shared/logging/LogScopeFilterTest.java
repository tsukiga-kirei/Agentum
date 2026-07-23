package com.agentum.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogScopeFilterTest {

    @Test
    void shouldRouteTrustedTenantEventOnlyToTenantFile() {
        LoggingEvent event = event(Map.of(
            LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE,
            LogContext.TENANT_ID_KEY, "tenant-1"
        ));

        assertThat(filter(LogContext.TENANT_SCOPE).decide(event)).isEqualTo(FilterReply.ACCEPT);
        assertThat(filter(LogContext.SYSTEM_SCOPE).decide(event)).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldRouteUnscopedOrIncompleteTenantEventToSystemFile() {
        LoggingEvent unscoped = event(Map.of());
        LoggingEvent missingTenantId = event(Map.of(LogContext.SCOPE_KEY, LogContext.TENANT_SCOPE));

        assertThat(filter(LogContext.SYSTEM_SCOPE).decide(unscoped)).isEqualTo(FilterReply.ACCEPT);
        assertThat(filter(LogContext.SYSTEM_SCOPE).decide(missingTenantId)).isEqualTo(FilterReply.ACCEPT);
        assertThat(filter(LogContext.TENANT_SCOPE).decide(missingTenantId)).isEqualTo(FilterReply.DENY);
    }

    private static LogScopeFilter filter(String scope) {
        LogScopeFilter filter = new LogScopeFilter();
        filter.setTargetScope(scope);
        return filter;
    }

    private static LoggingEvent event(Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
