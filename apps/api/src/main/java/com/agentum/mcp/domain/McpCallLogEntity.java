package com.agentum.mcp.domain;

import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// MCP 调用日志记录工具级证据链，便于追溯工具参数、结果脱敏摘要和失败原因。
@Entity
@Table(name = "mcp_call_logs")
public class McpCallLogEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_run_id", nullable = false)
    private UUID nodeRunId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(name = "capability_id")
    private UUID capabilityId;

    @Column(name = "capability_code", nullable = false, length = 100)
    private String capabilityCode;

    @Column(name = "tool_name", nullable = false, length = 160)
    private String toolName;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> responsePayload;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected McpCallLogEntity() {
    }

    public static McpCallLogEntity started(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity nodeRun,
        SystemCapabilityEntity capability,
        String toolName,
        Map<String, Object> requestPayload,
        Instant now
    ) {
        McpCallLogEntity entity = new McpCallLogEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = run.getTenantId();
        entity.runId = run.getId();
        entity.nodeRunId = nodeRun.getId();
        entity.workflowId = run.getWorkflowId();
        entity.workflowVersionId = run.getWorkflowVersionId();
        entity.capabilityId = capability.getId();
        entity.capabilityCode = capability.getCode();
        entity.toolName = toolName == null || toolName.isBlank() ? capability.getCode() : toolName;
        entity.status = "running";
        entity.requestPayload = requestPayload == null ? new HashMap<>() : new HashMap<>(requestPayload);
        entity.responsePayload = new HashMap<>();
        entity.createdAt = now;
        return entity;
    }

    public void succeed(Map<String, Object> responsePayload, long latencyMs, Instant now) {
        this.status = "success";
        this.responsePayload = responsePayload == null ? new HashMap<>() : new HashMap<>(responsePayload);
        this.latencyMs = latencyMs;
        this.completedAt = now;
    }

    public void skipped(String reason, Instant now) {
        this.status = "skipped";
        this.responsePayload = Map.of("summary", reason);
        this.latencyMs = 0L;
        this.completedAt = now;
    }

    public void fail(String errorCode, String errorMessage, long latencyMs, Instant now) {
        this.status = "failed";
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);
        this.latencyMs = latencyMs;
        this.completedAt = now;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public UUID getId() {
        return id;
    }
}
