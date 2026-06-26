-- 租户企业认证方式扩展：在 OIDC 之外支持入站 Basic 单点入口。
-- Basic 仍只作为身份来源，Agentum 本地用户、租户入口角色和资源权限继续作为授权边界。

ALTER TABLE tenant_sso_providers
    ADD COLUMN encrypted_basic_password TEXT,
    ADD COLUMN allowed_ip_ranges TEXT,
    ADD COLUMN allowed_domains TEXT;

ALTER TABLE tenant_sso_providers
    ALTER COLUMN issuer DROP NOT NULL,
    ALTER COLUMN client_id DROP NOT NULL,
    ALTER COLUMN authorization_endpoint DROP NOT NULL,
    ALTER COLUMN token_endpoint DROP NOT NULL,
    ALTER COLUMN jwks_uri DROP NOT NULL;

COMMENT ON COLUMN tenant_sso_providers.encrypted_basic_password IS 'Basic 单点入口共享密码密文，由系统管理员配置，业务系统调用时作为 Basic 密码';
COMMENT ON COLUMN tenant_sso_providers.allowed_ip_ranges IS '允许调用 Basic 单点入口的来源 IP，逗号分隔；为空表示不按 IP 限制';
COMMENT ON COLUMN tenant_sso_providers.allowed_domains IS '允许调用 Basic 单点入口的 Origin/Referer 域名，逗号分隔；为空表示不按域名限制';
