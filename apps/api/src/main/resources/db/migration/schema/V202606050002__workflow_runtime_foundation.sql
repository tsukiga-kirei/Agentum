-- 工作流运行态基础表。
-- 第一版把发布版本快照转成可追踪任务链路：运行实例、节点运行、等待事件和运行事件分表保存。
CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    workflow_version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    workflow_name VARCHAR(180) NOT NULL,
    state VARCHAR(30) NOT NULL,
    current_node_key VARCHAR(120),
    current_node_name VARCHAR(160),
    current_node_type VARCHAR(40),
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    total_node_count INT NOT NULL DEFAULT 0,
    completed_node_count INT NOT NULL DEFAULT 0,
    progress_percent INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_workflow_runs_tenant_updated ON workflow_runs (tenant_id, updated_at DESC);
CREATE INDEX idx_workflow_runs_owner_updated ON workflow_runs (tenant_id, created_by, updated_at DESC);
CREATE INDEX idx_workflow_runs_state ON workflow_runs (tenant_id, state, updated_at DESC);

CREATE TABLE workflow_node_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    node_key VARCHAR(120) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    state VARCHAR(30) NOT NULL,
    state_label VARCHAR(80) NOT NULL,
    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_node_runs_run_key ON workflow_node_runs (run_id, node_key);
CREATE INDEX idx_workflow_node_runs_run_sort ON workflow_node_runs (run_id, sort_order);
CREATE INDEX idx_workflow_node_runs_state ON workflow_node_runs (tenant_id, state, updated_at DESC);

CREATE TABLE workflow_waiting_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    node_key VARCHAR(120) NOT NULL,
    title VARCHAR(180) NOT NULL,
    waiting_reason VARCHAR(300) NOT NULL,
    waiting_for_type VARCHAR(30) NOT NULL,
    waiting_for_id UUID,
    action_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_workflow_waiting_events_visible ON workflow_waiting_events (tenant_id, status, waiting_for_type, waiting_for_id, created_at DESC);
CREATE INDEX idx_workflow_waiting_events_run ON workflow_waiting_events (run_id, status, created_at DESC);

CREATE TABLE workflow_run_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(600) NOT NULL,
    node_key VARCHAR(120),
    operator_id UUID REFERENCES users (id) ON DELETE SET NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    event_time TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_run_events_run_time ON workflow_run_events (run_id, event_time ASC);
CREATE INDEX idx_workflow_run_events_tenant_time ON workflow_run_events (tenant_id, event_time DESC);

COMMENT ON TABLE workflow_runs IS '工作流运行实例表，记录某次业务任务引用的不可变流程版本和当前状态';
COMMENT ON TABLE workflow_node_runs IS '工作流节点运行表，保存每个节点在运行实例中的状态、输入输出和配置快照';
COMMENT ON TABLE workflow_waiting_events IS '工作流等待事件表，是业务工作台待办的事实来源';
COMMENT ON TABLE workflow_run_events IS '工作流运行事件表，用于任务详情和后续审计聚合';
COMMENT ON COLUMN workflow_runs.workflow_version_id IS '运行实例引用的不可变发布版本，禁止回读可变草稿';
COMMENT ON COLUMN workflow_node_runs.input_snapshot IS '节点输入快照，后续变量系统接入后写入真实变量值';
COMMENT ON COLUMN workflow_node_runs.output_snapshot IS '节点输出快照，模型/MCP/交付接入前可保存占位摘要';
COMMENT ON COLUMN workflow_waiting_events.waiting_for_type IS '等待对象类型，第一版使用 user，后续扩展 department/role';
COMMENT ON COLUMN workflow_waiting_events.action_type IS '待办处理动作，例如提交输入、提交审核、确认交付';
