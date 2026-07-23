package com.agentum.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 把日志事件互斥地路由到系统文件或租户文件。
 *
 * <p>只有同时具备 {@code logScope=TENANT} 和可信 {@code tenantId} 的事件才进入租户文件；
 * 上下文缺失时降级进入系统文件，避免租户日志因错误上下文被写入无法定位的记录。</p>
 */
public class LogScopeFilter extends Filter<ILoggingEvent> {

    private String targetScope;

    public void setTargetScope(String targetScope) {
        this.targetScope = targetScope;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event == null || targetScope == null) {
            return FilterReply.NEUTRAL;
        }
        String eventScope = event.getMDCPropertyMap().get(LogContext.SCOPE_KEY);
        String tenantId = event.getMDCPropertyMap().get(LogContext.TENANT_ID_KEY);
        boolean isTrustedTenantEvent = LogContext.TENANT_SCOPE.equals(eventScope)
            && tenantId != null
            && !tenantId.isBlank();

        if (LogContext.TENANT_SCOPE.equals(targetScope)) {
            return isTrustedTenantEvent ? FilterReply.ACCEPT : FilterReply.DENY;
        }
        if (LogContext.SYSTEM_SCOPE.equals(targetScope)) {
            return isTrustedTenantEvent ? FilterReply.DENY : FilterReply.ACCEPT;
        }
        return FilterReply.DENY;
    }
}
