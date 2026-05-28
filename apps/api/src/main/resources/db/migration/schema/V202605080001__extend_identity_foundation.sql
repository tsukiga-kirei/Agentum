-- 认证闭环补充：系统管理员不绑定租户，因此需要独立的系统角色关系表。
CREATE TABLE system_user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_system_user_roles ON system_user_roles (user_id, role_id);
CREATE UNIQUE INDEX uk_roles_system_code ON roles (code) WHERE tenant_id IS NULL;

COMMENT ON TABLE system_user_roles IS '系统级用户角色关系表，系统管理员不绑定具体租户';
