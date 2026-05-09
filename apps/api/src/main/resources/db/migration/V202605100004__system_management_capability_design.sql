-- 系统管理能力治理调整：
-- 1. 模型供应商类型改为数据库字典，前端下拉来自后端。
-- 2. 全局能力不再承载 model，模型供应商和租户模型分配独立治理。
-- 3. 租户能力授权收敛到系统管理租户抽屉中的“能力配置 / 模型分配”。

CREATE TABLE model_provider_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    auth_scheme VARCHAR(40) NOT NULL DEFAULT 'api_key',
    default_base_url VARCHAR(500),
    model_list_endpoint VARCHAR(200),
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_model_provider_types_code ON model_provider_types (code);

COMMENT ON TABLE model_provider_types IS '模型供应商类型字典，供系统管理新建供应商时下拉选择';
COMMENT ON COLUMN model_provider_types.code IS '供应商类型编码，如 openai-compatible、azure-openai、qwen-compatible';
COMMENT ON COLUMN model_provider_types.auth_scheme IS '认证方式，如 api_key、bearer、custom_header';
COMMENT ON COLUMN model_provider_types.model_list_endpoint IS '模型列表路径，用于后续测试连接后同步可用模型';

INSERT INTO model_provider_types (code, name, description, auth_scheme, default_base_url, model_list_endpoint, settings)
VALUES
    ('openai-compatible', 'OpenAI 兼容', '兼容 OpenAI Chat Completions / Responses 风格的模型供应商或本地网关。', 'bearer', 'https://api.openai.com/v1', '/models', '{"headers":["Authorization"]}'::jsonb),
    ('azure-openai', 'Azure OpenAI', 'Azure OpenAI 企业部署，通常需要 endpoint、deployment 和 api-version。', 'api_key', NULL, '/openai/models', '{"required":["api-version","deployment"]}'::jsonb),
    ('qwen-compatible', '通义千问兼容', '阿里云百炼 / 通义千问兼容模式，适合国内模型供应商接入。', 'api_key', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '/models', '{}'::jsonb),
    ('anthropic-compatible', 'Anthropic 兼容', 'Anthropic 或兼容 Claude 消息协议的代理服务。', 'api_key', 'https://api.anthropic.com/v1', '/models', '{"headers":["x-api-key","anthropic-version"]}'::jsonb);

DELETE FROM tenant_capability_grants
WHERE capability_id IN (
    SELECT id FROM system_capabilities WHERE capability_type = 'model'
);

DELETE FROM system_capabilities
WHERE capability_type = 'model';

COMMENT ON COLUMN system_capabilities.capability_type IS '能力类型：skill、mcp、prompt_template、delivery；模型供应商不再作为全局能力登记';

-- 迁移演示能力的最小测试配置，避免系统管理页“测试连通性”只能看到空配置失败。
-- 后续接入真实 MCP 网关后，这些占位配置应替换为能力源码目录中的 manifest 和部署配置。
UPDATE system_capabilities
SET config = '{"transport":"stdio","command":"node capabilities/mcp-servers/file-read/server.js","args":"--readonly","workingDir":"capabilities/mcp-servers/file-read","sseUrl":""}'::jsonb
WHERE code = 'file_read_mcp'
  AND config = '{}'::jsonb;

UPDATE system_capabilities
SET config = '{"sourcePath":"capabilities/skills/requirement-breakdown/SKILL.md","manifestPath":"capabilities/skills/requirement-breakdown/skill.yaml"}'::jsonb
WHERE code = 'requirement_breakdown'
  AND config = '{}'::jsonb;

CREATE TABLE tenant_model_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider_id UUID NOT NULL REFERENCES model_providers (id) ON DELETE CASCADE,
    default_model VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'enabled',
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_tenant_model_assignments ON tenant_model_assignments (tenant_id, provider_id);

COMMENT ON TABLE tenant_model_assignments IS '单租户模型分配表，由系统管理的租户抽屉“模型分配”维护';
COMMENT ON COLUMN tenant_model_assignments.default_model IS '该租户使用此供应商时的默认模型，可覆盖供应商默认模型';
