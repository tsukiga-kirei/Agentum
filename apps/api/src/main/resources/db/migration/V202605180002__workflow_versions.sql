-- 工作流发布版本表。
-- 草稿允许继续修改，运行态后续只能引用这里冻结后的版本快照，避免历史执行协议被设计态覆盖。
CREATE TABLE workflow_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    definition_snapshot JSONB NOT NULL,
    node_count INT NOT NULL,
    pause_point_count INT NOT NULL,
    published_by UUID REFERENCES users (id) ON DELETE SET NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_versions_workflow_number ON workflow_versions (workflow_id, version_number);
CREATE INDEX idx_workflow_versions_tenant_published ON workflow_versions (tenant_id, published_at DESC);

COMMENT ON TABLE workflow_versions IS '工作流不可变发布版本表，供后续运行实例引用';
COMMENT ON COLUMN workflow_versions.definition_snapshot IS '发布时冻结的节点、边和变量声明快照';
