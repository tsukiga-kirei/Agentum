-- 运行态真实执行日志与变量快照。
-- 这些表把 WorkflowRun / NodeRun 与模型、MCP、交付和变量结果串起来，避免审计只能依赖节点 JSON 字段。

CREATE TABLE variable_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID REFERENCES workflow_node_runs (id) ON DELETE SET NULL,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    variable_name VARCHAR(120) NOT NULL,
    value_type VARCHAR(40) NOT NULL,
    value_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_node_key VARCHAR(120),
    sensitive BOOLEAN NOT NULL DEFAULT false,
    delivery_visible BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_variable_snapshots_run_name ON variable_snapshots (run_id, variable_name, created_at DESC);
CREATE INDEX idx_variable_snapshots_tenant_time ON variable_snapshots (tenant_id, created_at DESC);

CREATE TABLE model_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    provider_id UUID REFERENCES model_providers (id) ON DELETE SET NULL,
    provider_type VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    prompt_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    token_usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_model_call_logs_run ON model_call_logs (run_id, created_at DESC);
CREATE INDEX idx_model_call_logs_node ON model_call_logs (node_run_id, created_at DESC);
CREATE INDEX idx_model_call_logs_provider ON model_call_logs (tenant_id, provider_id, created_at DESC);

CREATE TABLE mcp_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    capability_id UUID REFERENCES system_capabilities (id) ON DELETE SET NULL,
    capability_code VARCHAR(100) NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_mcp_call_logs_run ON mcp_call_logs (run_id, created_at DESC);
CREATE INDEX idx_mcp_call_logs_capability ON mcp_call_logs (tenant_id, capability_id, created_at DESC);

CREATE TABLE delivery_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    capability_id UUID REFERENCES system_capabilities (id) ON DELETE SET NULL,
    delivery_type VARCHAR(40) NOT NULL,
    target VARCHAR(300),
    title VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_delivery_records_run ON delivery_records (run_id, created_at DESC);
CREATE INDEX idx_delivery_records_tenant_status ON delivery_records (tenant_id, status, created_at DESC);

COMMENT ON TABLE variable_snapshots IS '运行变量快照表，按节点输出记录变量值、敏感标记和交付可见性';
COMMENT ON TABLE model_call_logs IS '模型调用日志表，记录运行节点使用的租户模型分配、提示词摘要、响应和 Token 用量';
COMMENT ON TABLE mcp_call_logs IS 'MCP 调用日志表，记录工具名称、参数、结果、失败原因和关联节点';
COMMENT ON TABLE delivery_records IS '交付记录表，记录文件、邮件、OA、Webhook 等交付动作的结果和失败原因';
COMMENT ON COLUMN model_call_logs.prompt_snapshot IS '提示词快照，仅保存业务上下文和脱敏消息，不保存模型 API Key';
COMMENT ON COLUMN mcp_call_logs.request_payload IS 'MCP 调用参数快照，运行服务写入前必须做敏感字段脱敏';
COMMENT ON COLUMN delivery_records.result_snapshot IS '交付结果摘要，例如邮件收件人、适配器响应或站内交付编号';
