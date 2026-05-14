-- Agentum 第一版平台基础表。
-- 目标是先支撑租户、人员、部门、角色、权限、模型和交付能力，后续工作流运行都必须带上这些上下文。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    code VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    contact_name VARCHAR(100),
    contact_email VARCHAR(255),
    model_quota JSONB NOT NULL DEFAULT '{}'::jsonb,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_tenants_code ON tenants (code);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    avatar_url VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    locale VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_users_username ON users (username);

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    parent_id UUID REFERENCES departments (id) ON DELETE SET NULL,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(80),
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_departments_tenant_id ON departments (tenant_id);
CREATE INDEX idx_departments_parent_id ON departments (parent_id);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    scope VARCHAR(30) NOT NULL,
    description TEXT,
    built_in BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_roles_tenant_code ON roles (tenant_id, code);

CREATE TABLE user_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    department_id UUID REFERENCES departments (id) ON DELETE SET NULL,
    space_code VARCHAR(80) NOT NULL DEFAULT 'default',
    is_default BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_memberships_tenant_id ON user_memberships (tenant_id);
CREATE INDEX idx_user_memberships_user_id ON user_memberships (user_id);

CREATE TABLE user_membership_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    membership_id UUID NOT NULL REFERENCES user_memberships (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_membership_roles_membership_id ON user_membership_roles (membership_id);
CREATE INDEX idx_user_membership_roles_role_id ON user_membership_roles (role_id);
CREATE UNIQUE INDEX uk_user_membership_roles_active ON user_membership_roles (membership_id, role_id) WHERE status = 'active';

CREATE TABLE permission_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    resource_type VARCHAR(80) NOT NULL,
    actions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    effect VARCHAR(20) NOT NULL DEFAULT 'allow',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_permission_policies_role_id ON permission_policies (role_id);

CREATE TABLE resource_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID NOT NULL,
    principal_type VARCHAR(30) NOT NULL,
    principal_id UUID NOT NULL,
    actions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_resource_grants_resource ON resource_grants (resource_type, resource_id);
CREATE INDEX idx_resource_grants_principal ON resource_grants (principal_type, principal_id);

CREATE TABLE sensitive_action_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    action_code VARCHAR(100) NOT NULL,
    requires_approval BOOLEAN NOT NULL DEFAULT false,
    requires_reauth BOOLEAN NOT NULL DEFAULT false,
    audit_required BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_sensitive_action_tenant_code ON sensitive_action_policies (tenant_id, action_code);

CREATE TABLE model_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(80) NOT NULL,
    base_url VARCHAR(500),
    credential_ref VARCHAR(200),
    default_model VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE system_capabilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capability_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(100) NOT NULL,
    version VARCHAR(40) NOT NULL DEFAULT 'v1',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_system_capabilities_code_version ON system_capabilities (code, version);

CREATE TABLE tenant_capability_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    capability_id UUID NOT NULL REFERENCES system_capabilities (id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL DEFAULT 'enabled',
    quota JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_tenant_capability_grants ON tenant_capability_grants (tenant_id, capability_id);

CREATE TABLE prompt_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    version VARCHAR(40) NOT NULL DEFAULT 'v1',
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    content TEXT NOT NULL,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE delivery_capabilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    version VARCHAR(40) NOT NULL DEFAULT 'v1',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'medium',
    requires_approval BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tenants IS '租户表，平台最高业务隔离边界';
COMMENT ON TABLE users IS '用户账号表';
COMMENT ON TABLE departments IS '租户部门表，用于人员组织、待办分派和审核范围';
COMMENT ON TABLE roles IS '角色表，系统级或租户级角色';
COMMENT ON TABLE user_memberships IS '用户租户成员关系表，记录用户在租户、部门、空间中的身份';
COMMENT ON TABLE user_membership_roles IS '用户成员关系与租户角色的多对多关系表';
COMMENT ON TABLE permission_policies IS '角色权限策略表';
COMMENT ON TABLE resource_grants IS '资源级授权表';
COMMENT ON TABLE sensitive_action_policies IS '敏感动作策略表';
COMMENT ON TABLE model_providers IS '模型供应商配置表';
COMMENT ON TABLE system_capabilities IS '系统级能力表，包含全局 MCP、Skills、提示词模板和交付能力开关';
COMMENT ON TABLE tenant_capability_grants IS '租户能力授权表';
COMMENT ON TABLE prompt_templates IS '提示词模板资产表';
COMMENT ON TABLE delivery_capabilities IS '交付能力资产表';

COMMENT ON COLUMN tenants.code IS '租户唯一编码';
COMMENT ON COLUMN tenants.model_quota IS '租户模型额度、并发和成本限制配置';
COMMENT ON COLUMN users.password_hash IS '密码哈希，禁止存储明文密码';
COMMENT ON COLUMN roles.scope IS '角色范围：system、tenant、space、business';
COMMENT ON COLUMN permission_policies.actions IS '允许或拒绝的动作列表';
COMMENT ON COLUMN resource_grants.principal_type IS '授权主体类型：user、department、role、agent、workflow';
COMMENT ON COLUMN system_capabilities.capability_type IS '能力类型：model、skill、mcp、prompt_template、delivery';
COMMENT ON COLUMN delivery_capabilities.channel IS '交付通道：document、email、oa、im、webhook、database';
