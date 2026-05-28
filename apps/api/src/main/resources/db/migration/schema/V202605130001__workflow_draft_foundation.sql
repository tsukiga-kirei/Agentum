-- 工作流设计态基础表。
-- 第一阶段先落草稿、节点和边，运行态、变量快照、暂停恢复会在后续迁移中独立建模。
CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name VARCHAR(180) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    node_count INT NOT NULL DEFAULT 0,
    pause_point_count INT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_definitions_tenant_updated ON workflow_definitions (tenant_id, updated_at DESC);

CREATE TABLE workflow_node_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    node_key VARCHAR(120) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    position_x NUMERIC(12, 2) NOT NULL DEFAULT 0,
    position_y NUMERIC(12, 2) NOT NULL DEFAULT 0,
    input_variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    output_variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_nodes_workflow_key ON workflow_node_definitions (workflow_id, node_key);
CREATE INDEX idx_workflow_nodes_workflow_sort ON workflow_node_definitions (workflow_id, sort_order);

CREATE TABLE workflow_edge_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    edge_key VARCHAR(120) NOT NULL,
    source_node_key VARCHAR(120) NOT NULL,
    target_node_key VARCHAR(120) NOT NULL,
    label VARCHAR(120),
    condition_expression TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_edges_workflow_key ON workflow_edge_definitions (workflow_id, edge_key);
CREATE INDEX idx_workflow_edges_workflow_sort ON workflow_edge_definitions (workflow_id, sort_order);

COMMENT ON TABLE workflow_definitions IS '工作流定义草稿表，发布后会生成不可变版本';
COMMENT ON TABLE workflow_node_definitions IS '工作流设计态节点表，保存固定节点类型、布局和节点配置';
COMMENT ON TABLE workflow_edge_definitions IS '工作流设计态边表，保存节点连线和条件标签';
COMMENT ON COLUMN workflow_definitions.status IS '草稿状态：draft、review、published。第一阶段主要使用 draft';
COMMENT ON COLUMN workflow_node_definitions.config IS '节点配置 JSON，运行态执行前必须再次做类型和权限校验';
