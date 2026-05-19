-- 租户自建能力资产表。
-- 系统通用能力仍由 system_capabilities -> tenant_capability_grants -> resource_grants 表达；
-- 本表只承接租户成员自己沉淀的业务能力，后续可按资产类型拆分到 agent_templates、skills、mcp_services 等专表。
CREATE TABLE tenant_asset_capabilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    asset_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(100) NOT NULL,
    version VARCHAR(40) NOT NULL DEFAULT 'v1',
    description TEXT,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    visibility VARCHAR(30) NOT NULL DEFAULT 'private',
    source_type VARCHAR(30) NOT NULL DEFAULT 'custom',
    base_system_capability_id UUID REFERENCES system_capabilities (id) ON DELETE SET NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_tenant_asset_capabilities_code_version
    ON tenant_asset_capabilities (tenant_id, code, version);
CREATE INDEX idx_tenant_asset_capabilities_owner
    ON tenant_asset_capabilities (tenant_id, created_by, updated_at DESC);
CREATE INDEX idx_tenant_asset_capabilities_status
    ON tenant_asset_capabilities (tenant_id, asset_type, status);

COMMENT ON TABLE tenant_asset_capabilities IS '租户自建能力资产表，承接用户在能力资产页创建的智能体模板、Skill、MCP、提示词模板和交付能力记录';
COMMENT ON COLUMN tenant_asset_capabilities.asset_type IS '资产类型：agent_template、skill、mcp、prompt_template、delivery';
COMMENT ON COLUMN tenant_asset_capabilities.visibility IS '可见范围：private 表示仅创建者维护，tenant 表示租户内治理后可复用';
COMMENT ON COLUMN tenant_asset_capabilities.source_type IS '资产来源：custom 为用户自建，derived 表示基于系统能力派生';
COMMENT ON COLUMN tenant_asset_capabilities.base_system_capability_id IS '派生自系统通用能力时关联 system_capabilities，用于追溯系统管理到业务资产的来源';
