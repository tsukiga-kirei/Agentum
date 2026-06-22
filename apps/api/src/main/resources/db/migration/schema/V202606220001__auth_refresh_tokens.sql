-- Refresh Token 只保存不可逆摘要，原文仅通过 HttpOnly Cookie 交给浏览器。
-- 每次刷新都会吊销旧令牌并签发新令牌，避免同一 Refresh Token 被长期重复使用。
CREATE TABLE auth_refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_assignment_id UUID NOT NULL REFERENCES user_role_assignments (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_auth_refresh_tokens_hash ON auth_refresh_tokens (token_hash);
CREATE INDEX idx_auth_refresh_tokens_user_id ON auth_refresh_tokens (user_id);
CREATE INDEX idx_auth_refresh_tokens_expires_at ON auth_refresh_tokens (expires_at);

COMMENT ON TABLE auth_refresh_tokens IS '可轮换、可吊销的登录刷新令牌；数据库只保存 SHA-256 摘要';
COMMENT ON COLUMN auth_refresh_tokens.id IS '刷新令牌记录主键，由应用生成 UUID';
COMMENT ON COLUMN auth_refresh_tokens.user_id IS '令牌所属用户 ID；用户删除时级联删除其全部刷新令牌';
COMMENT ON COLUMN auth_refresh_tokens.role_assignment_id IS '令牌绑定的活跃角色分配 ID，用于刷新时恢复租户、入口和角色上下文';
COMMENT ON COLUMN auth_refresh_tokens.token_hash IS 'Refresh Token 的 SHA-256 摘要，禁止保存或记录令牌原文';
COMMENT ON COLUMN auth_refresh_tokens.expires_at IS '刷新令牌绝对过期时间，超过该时间后不得再换取 Access Token';
COMMENT ON COLUMN auth_refresh_tokens.revoked_at IS '刷新令牌吊销时间；非空表示令牌已登出、已轮换或被主动失效';
COMMENT ON COLUMN auth_refresh_tokens.created_at IS '刷新令牌记录创建时间';
COMMENT ON COLUMN auth_refresh_tokens.last_used_at IS '刷新令牌最后成功使用或吊销时间，用于会话追踪和安全审计';
