package com.agentum.agent.domain;

import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.agent.application.TokenUsage;
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

// 模型调用日志只记录脱敏后的提示词、响应和用量，严禁保存 API Key 或供应商敏感原始头。
@Entity
@Table(name = "model_call_logs")
public class ModelCallLogEntity {

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

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "provider_type", nullable = false, length = 80)
    private String providerType;

    @Column(name = "model_name", nullable = false, length = 160)
    private String modelName;

    @Column(nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> promptSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> responseSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> tokenUsage;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

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

    protected ModelCallLogEntity() {
    }

    public static ModelCallLogEntity started(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity nodeRun,
        UUID providerId,
        String providerType,
        String modelName,
        Map<String, Object> promptSnapshot,
        Instant now
    ) {
        ModelCallLogEntity entity = new ModelCallLogEntity();
        entity.id = UUID.randomUUID();
        entity.tenantId = run.getTenantId();
        entity.runId = run.getId();
        entity.nodeRunId = nodeRun.getId();
        entity.workflowId = run.getWorkflowId();
        entity.workflowVersionId = run.getWorkflowVersionId();
        entity.providerId = providerId;
        entity.providerType = providerType;
        entity.modelName = modelName;
        entity.status = "running";
        entity.promptSnapshot = promptSnapshot == null ? new HashMap<>() : new HashMap<>(promptSnapshot);
        entity.responseSnapshot = new HashMap<>();
        entity.tokenUsage = new HashMap<>();
        entity.createdAt = now;
        return entity;
    }

    public void succeed(Map<String, Object> responseSnapshot, Map<String, Object> tokenUsage, long latencyMs, Instant now) {
        this.status = "success";
        this.responseSnapshot = responseSnapshot == null ? new HashMap<>() : new HashMap<>(responseSnapshot);
        this.tokenUsage = tokenUsage == null ? new HashMap<>() : new HashMap<>(tokenUsage);
        TokenUsage normalizedUsage = TokenUsage.fromProviderUsage(tokenUsage);
        this.inputTokens = normalizedUsage.inputTokens();
        this.outputTokens = normalizedUsage.outputTokens();
        this.totalTokens = normalizedUsage.totalTokens();
        this.latencyMs = latencyMs;
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

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getNodeRunId() {
        return nodeRunId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getWorkflowVersionId() {
        return workflowVersionId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getModelName() {
        return modelName;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getPromptSnapshot() {
        return promptSnapshot;
    }

    public Map<String, Object> getResponseSnapshot() {
        return responseSnapshot;
    }

    public Map<String, Object> getTokenUsage() {
        return tokenUsage;
    }

    public TokenUsage getNormalizedTokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, totalTokens);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
