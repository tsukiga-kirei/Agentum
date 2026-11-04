-- MCP 明细独立保存，变量与调用日志只保留引用及受限预览。
CREATE TABLE mcp_datasets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL,
    node_run_id UUID NOT NULL,
    capability_id UUID NOT NULL REFERENCES system_capabilities(id),
    call_log_id UUID NOT NULL,
    dataset_key VARCHAR(160) NOT NULL,
    storage_key TEXT NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    result_policy JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_mcp_datasets_run ON mcp_datasets(tenant_id, run_id);
CREATE INDEX idx_mcp_datasets_node ON mcp_datasets(node_run_id);
CREATE INDEX idx_mcp_datasets_expiry ON mcp_datasets(expires_at);
