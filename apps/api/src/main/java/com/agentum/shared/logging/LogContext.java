package com.agentum.shared.logging;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.runtime.messaging.NodeExecuteCommand;
import com.agentum.shared.api.RequestIds;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * 统一维护运行日志的系统 / 租户范围与链路字段。
 *
 * <p>MDC 绑定在线程上，因此每次写入都必须通过 {@link Scope} 在 finally 阶段恢复，
 * 避免 Servlet 或 RabbitMQ 线程复用后把上一租户上下文带入下一次任务。</p>
 */
public final class LogContext {

    public static final String SCOPE_KEY = "logScope";
    public static final String SYSTEM_SCOPE = "SYSTEM";
    public static final String TENANT_SCOPE = "TENANT";
    public static final String TENANT_ID_KEY = "tenantId";
    public static final String USER_ID_KEY = "userId";
    public static final String ROLE_KEY = "role";
    public static final String RUN_ID_KEY = "runId";
    public static final String JOB_ID_KEY = "jobId";
    public static final String NODE_RUN_ID_KEY = "nodeRunId";
    private static final String[] MANAGED_KEYS = {
        SCOPE_KEY,
        TENANT_ID_KEY,
        USER_ID_KEY,
        ROLE_KEY,
        RUN_ID_KEY,
        JOB_ID_KEY,
        NODE_RUN_ID_KEY,
        RequestIds.MDC_KEY
    };

    private LogContext() {
    }

    public static Scope openForPrincipal(CurrentUserPrincipal principal) {
        if (principal == null) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(SCOPE_KEY, SYSTEM_SCOPE);
            putUuid(values, TENANT_ID_KEY, null);
            putUuid(values, USER_ID_KEY, null);
            putText(values, ROLE_KEY, null);
            putUuid(values, RUN_ID_KEY, null);
            putUuid(values, JOB_ID_KEY, null);
            putUuid(values, NODE_RUN_ID_KEY, null);
            return open(values);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SCOPE_KEY, principal.tenantId() == null ? SYSTEM_SCOPE : TENANT_SCOPE);
        putUuid(values, TENANT_ID_KEY, principal.tenantId());
        putUuid(values, USER_ID_KEY, principal.userId());
        putText(values, ROLE_KEY, principal.role());
        putUuid(values, RUN_ID_KEY, null);
        putUuid(values, JOB_ID_KEY, null);
        putUuid(values, NODE_RUN_ID_KEY, null);
        return open(values);
    }

    public static Scope openForExecution(NodeExecuteCommand command) {
        if (command == null) {
            return openTenantOperation(null, null, null, null, null, null);
        }
        return openTenantOperation(
            command.tenantId(),
            command.operatorUserId(),
            command.runId(),
            command.jobId(),
            command.nodeRunId(),
            command.requestId()
        );
    }

    public static Scope openTenantOperation(
        UUID tenantId,
        UUID userId,
        UUID runId,
        UUID jobId,
        UUID nodeRunId,
        String requestId
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SCOPE_KEY, tenantId == null ? SYSTEM_SCOPE : TENANT_SCOPE);
        putUuid(values, TENANT_ID_KEY, tenantId);
        putUuid(values, USER_ID_KEY, userId);
        putText(values, ROLE_KEY, null);
        putUuid(values, RUN_ID_KEY, runId);
        putUuid(values, JOB_ID_KEY, jobId);
        putUuid(values, NODE_RUN_ID_KEY, nodeRunId);
        putText(values, RequestIds.MDC_KEY, requestId);
        return open(values);
    }

    /** 捕获可跨线程传递的脱敏链路字段，不复制第三方库可能写入 MDC 的未知内容。 */
    public static Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String value = MDC.get(key);
            if (value != null && !value.isBlank()) {
                values.put(key, value);
            }
        }
        return Map.copyOf(values);
    }

    /** 在异步线程恢复父线程快照；缺失字段会先清空，防止线程池残留旧租户信息。 */
    public static Scope openSnapshot(Map<String, String> snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            values.put(key, snapshot == null ? null : snapshot.get(key));
        }
        return open(values);
    }

    static Scope open(Map<String, String> values) {
        Map<String, String> previousValues = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            previousValues.put(key, MDC.get(key));
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
        return new Scope(previousValues);
    }

    private static void putUuid(Map<String, String> values, String key, UUID value) {
        values.put(key, value == null ? null : value.toString());
    }

    private static void putText(Map<String, String> values, String key, String value) {
        values.put(key, value == null || value.isBlank() ? null : value);
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previousValues;
        private boolean closed;

        private Scope(Map<String, String> previousValues) {
            this.previousValues = previousValues;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            previousValues.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
            closed = true;
        }
    }
}
