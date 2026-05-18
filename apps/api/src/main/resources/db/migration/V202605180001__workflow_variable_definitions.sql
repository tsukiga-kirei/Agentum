-- 工作流变量声明表。
-- 节点上的输入 / 输出数组只表达引用关系；变量本身的类型、敏感性和交付属性需要独立沉淀，后续发布版本和运行快照都依赖这份定义。
CREATE TABLE workflow_variable_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    variable_key VARCHAR(120) NOT NULL,
    variable_type VARCHAR(40) NOT NULL,
    source_node_key VARCHAR(120) NOT NULL,
    description TEXT,
    json_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    deliverable BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_variables_workflow_key ON workflow_variable_definitions (workflow_id, variable_key);
CREATE INDEX idx_workflow_variables_workflow_sort ON workflow_variable_definitions (workflow_id, sort_order);

COMMENT ON TABLE workflow_variable_definitions IS '工作流设计态变量声明表，保存变量来源、类型、敏感性和交付属性';
COMMENT ON COLUMN workflow_variable_definitions.variable_key IS '变量标识，遵循小写字母开头的 snake_case';
COMMENT ON COLUMN workflow_variable_definitions.source_node_key IS '声明该变量的来源节点标识';
COMMENT ON COLUMN workflow_variable_definitions.json_schema IS '变量结构约束，后续用于模型输出解析、发布校验和运行态快照';
