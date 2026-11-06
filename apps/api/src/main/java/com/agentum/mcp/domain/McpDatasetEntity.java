package com.agentum.mcp.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 元信息可进入运行快照；完整数据单独保存在对象存储，避免污染模型上下文和事件流。 */
@Entity
@Table(name = "mcp_datasets")
public class McpDatasetEntity {
    @Id public UUID id;
    @Column(name = "tenant_id", nullable = false) public UUID tenantId;
    @Column(name = "run_id", nullable = false) public UUID runId;
    @Column(name = "node_run_id", nullable = false) public UUID nodeRunId;
    @Column(name = "capability_id", nullable = false) public UUID capabilityId;
    @Column(name = "call_log_id", nullable = false) public UUID callLogId;
    @Column(name = "dataset_key", nullable = false) public String datasetKey;
    @Column(name = "storage_key", nullable = false) public String storageKey;
    @Column(name = "row_count", nullable = false) public int rowCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_policy", nullable = false, columnDefinition = "jsonb") public Map<String, Object> policy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    public McpDatasetEntity() {}
}
