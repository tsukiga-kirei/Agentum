-- 收敛“我的能力”的生产边界：
-- 业务用户只创建提示词模板草稿和智能体模板草稿，Skill、MCP 与交付能力只作为系统管理登记并经租户管理分配后的底层材料。
ALTER TABLE tenant_asset_capabilities
    ADD COLUMN published_at TIMESTAMPTZ;

ALTER TABLE tenant_asset_capabilities
    ADD CONSTRAINT chk_tenant_asset_capabilities_user_asset_type
    CHECK (asset_type IN ('agent_template', 'prompt_template')) NOT VALID;

ALTER TABLE tenant_asset_capabilities
    ADD CONSTRAINT chk_tenant_asset_capabilities_status
    CHECK (status IN ('draft', 'published', 'disabled')) NOT VALID;

COMMENT ON TABLE tenant_asset_capabilities IS '租户自建能力资产表，承接用户在能力资产页创建的提示词模板草稿和智能体模板草稿；Skill、MCP、交付能力不开放业务用户自建';
COMMENT ON COLUMN tenant_asset_capabilities.asset_type IS '用户可创建资产类型：agent_template、prompt_template';
COMMENT ON COLUMN tenant_asset_capabilities.config IS '草稿配置：提示词模板保存 promptContent，智能体模板保存 systemPrompt、skillIds、mcpIds；引用的 Skill/MCP 必须来自当前主体已开放能力池';
COMMENT ON COLUMN tenant_asset_capabilities.published_at IS '草稿发布为正式能力的时间';
