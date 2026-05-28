-- 租户内资源授权从“页面可见”扩展到具体能力资源。
-- 系统管理先把全局 MCP、Skill、提示词模板和交付能力启用给租户；
-- 租户管理再通过该字段分配给租户内角色，业务运行时只消费授权结果。
ALTER TABLE tenant_org_roles
    ADD COLUMN IF NOT EXISTS resource_permissions JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN tenant_org_roles.resource_permissions IS '资源权限列表，控制当前租户已启用的 MCP、Skill、提示词模板和交付能力';
