-- 新增配置与权限改动的全局操作审计日志表
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    operator_id UUID REFERENCES users (id) ON DELETE SET NULL,
    operator_name VARCHAR(120) NOT NULL,
    action_type VARCHAR(60) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id VARCHAR(100),
    target_name VARCHAR(200),
    description VARCHAR(500) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    client_ip VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_tenant_time ON audit_logs (tenant_id, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (tenant_id, action_type, created_at DESC);

COMMENT ON TABLE audit_logs IS '全局管理与配置变更操作审计表，记录流程定义变动和管理授权流水';
COMMENT ON COLUMN audit_logs.id IS '操作审计日志主键 UUID';
COMMENT ON COLUMN audit_logs.tenant_id IS '关联租户 ID';
COMMENT ON COLUMN audit_logs.operator_id IS '操作人用户 ID';
COMMENT ON COLUMN audit_logs.operator_name IS '操作人展示姓名';
COMMENT ON COLUMN audit_logs.action_type IS '操作动作类型，如 CREATE_WORKFLOW, PUBLISH_VERSION, ASSIGN_CAPABILITY';
COMMENT ON COLUMN audit_logs.target_type IS '操作目标类型，如 WORKFLOW_DEFINITION, CAPABILITY_GRANT, USER_MEMBER';
COMMENT ON COLUMN audit_logs.target_id IS '操作目标标识 ID';
COMMENT ON COLUMN audit_logs.target_name IS '操作目标展示名称';
COMMENT ON COLUMN audit_logs.description IS '操作描述，简述本次改动';
COMMENT ON COLUMN audit_logs.payload IS '具体修改的内容快照（JSON 格式）';
COMMENT ON COLUMN audit_logs.client_ip IS '操作人客户端 IP 地址';
COMMENT ON COLUMN audit_logs.created_at IS '日志记录时间';
