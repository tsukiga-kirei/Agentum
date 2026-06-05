-- 企业 SSO 接入基础表。
-- SSO 只证明外部身份，Agentum 仍按 user_role_assignments、租户成员关系和资源权限做业务授权。

CREATE TABLE tenant_sso_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider_type VARCHAR(30) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'enabled',
    issuer VARCHAR(500) NOT NULL,
    client_id VARCHAR(200) NOT NULL,
    encrypted_client_secret TEXT,
    authorization_endpoint VARCHAR(800) NOT NULL,
    token_endpoint VARCHAR(800) NOT NULL,
    jwks_uri VARCHAR(800) NOT NULL,
    logout_endpoint VARCHAR(800),
    email_domain VARCHAR(160),
    auto_bind_email BOOLEAN NOT NULL DEFAULT true,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenant_sso_providers_tenant_id ON tenant_sso_providers (tenant_id);
CREATE UNIQUE INDEX uk_tenant_sso_provider_name ON tenant_sso_providers (tenant_id, name);

CREATE TABLE user_external_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider_id UUID NOT NULL REFERENCES tenant_sso_providers (id) ON DELETE CASCADE,
    subject VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(160),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_user_external_identity_subject ON user_external_identities (provider_id, subject);
CREATE INDEX idx_user_external_identities_user_id ON user_external_identities (user_id);
CREATE INDEX idx_user_external_identities_tenant_email ON user_external_identities (tenant_id, email);

COMMENT ON TABLE tenant_sso_providers IS '租户企业 SSO 身份源配置，当前优先支持标准 OIDC';
COMMENT ON COLUMN tenant_sso_providers.provider_type IS '身份源类型：oidc / saml，当前实现 oidc';
COMMENT ON COLUMN tenant_sso_providers.encrypted_client_secret IS 'OIDC client secret 密文，禁止回显明文';
COMMENT ON COLUMN tenant_sso_providers.auto_bind_email IS '未绑定外部 subject 时，是否允许用已验证邮箱绑定已有 Agentum 用户';
COMMENT ON TABLE user_external_identities IS '外部身份与 Agentum 用户绑定表';
COMMENT ON COLUMN user_external_identities.subject IS 'OIDC sub，必须作为外部身份稳定唯一标识';
