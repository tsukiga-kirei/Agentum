-- 页签授权独立于能力资源授权：页签控制模块入口，能力授权控制具体 MCP / Skill / 提示词模板 / 交付能力。
CREATE TABLE page_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    page_key VARCHAR(80) NOT NULL,
    principal_type VARCHAR(30) NOT NULL,
    principal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_page_grants_principal_page
    ON page_grants (tenant_id, principal_type, principal_id, page_key);
CREATE INDEX idx_page_grants_tenant_created
    ON page_grants (tenant_id, created_at DESC);

COMMENT ON TABLE page_grants IS '租户内页签授权表，支持按角色、部门、人员授权业务侧模块入口';
COMMENT ON COLUMN page_grants.page_key IS '业务侧页签标识，例如 workbench、designer、assets、audit';
COMMENT ON COLUMN page_grants.principal_type IS '授权主体类型：role、department、user';
