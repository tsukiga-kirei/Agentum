-- 权限架构升级：引入统一角色分配表。
-- 一个用户可以在多个租户拥有不同的系统角色，支持角色切换而无需重新登录。

-- 1. 创建用户系统级角色分配表
CREATE TABLE user_role_assignments (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(30) NOT NULL,
    tenant_id  UUID        REFERENCES tenants (id) ON DELETE CASCADE,
    label      VARCHAR(200),
    is_default BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ura_user_id ON user_role_assignments (user_id);
CREATE INDEX idx_ura_tenant_id ON user_role_assignments (tenant_id);

COMMENT ON TABLE user_role_assignments IS '用户系统级角色分配表，一个用户可在多个租户有不同角色';
COMMENT ON COLUMN user_role_assignments.role IS '系统角色：business / tenant_admin / system_admin';
COMMENT ON COLUMN user_role_assignments.tenant_id IS '关联租户（system_admin 角色时为 NULL）';
COMMENT ON COLUMN user_role_assignments.label IS '前端展示标签，如"云程科技 - 业务用户"';
COMMENT ON COLUMN user_role_assignments.is_default IS '是否为用户默认角色';

-- 2. 创建租户内自定义角色表（第二层权限，控制业务用户的页签可见性）
CREATE TABLE tenant_org_roles (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name             VARCHAR(120) NOT NULL,
    description      TEXT,
    page_permissions JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_system        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(30)  NOT NULL DEFAULT 'active',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenant_org_roles_tenant_id ON tenant_org_roles (tenant_id);

COMMENT ON TABLE tenant_org_roles IS '租户内自定义角色表，用于第二层细粒度页签和资源权限分配';
COMMENT ON COLUMN tenant_org_roles.page_permissions IS '页签权限列表，如 ["workbench","designer","assets","audit"]';
COMMENT ON COLUMN tenant_org_roles.is_system IS '是否为系统预置角色（不可删除）';

-- 3. 从现有数据迁移角色分配

-- 3a. 系统管理员：从 system_user_roles 迁移
INSERT INTO user_role_assignments (user_id, role, tenant_id, label, is_default)
SELECT sur.user_id, 'system_admin', NULL, '系统管理员', TRUE
FROM system_user_roles sur
JOIN users u ON u.id = sur.user_id AND u.status = 'active';

-- 3b. 租户内角色：从 user_memberships + user_membership_roles + roles 推导系统角色
INSERT INTO user_role_assignments (user_id, role, tenant_id, label, is_default)
SELECT DISTINCT um.user_id,
    CASE WHEN r.code = 'tenant_admin' THEN 'tenant_admin' ELSE 'business' END,
    um.tenant_id,
    t.name || ' - ' || r.name,
    um.is_default
FROM user_memberships um
JOIN user_membership_roles umr ON umr.membership_id = um.id AND umr.status = 'active'
JOIN roles r ON r.id = umr.role_id AND r.status = 'active'
JOIN tenants t ON t.id = um.tenant_id AND t.status = 'active'
WHERE um.status = 'active';

-- 3c. 为租户管理员额外创建一条 business 角色，支持切换到业务视图
INSERT INTO user_role_assignments (user_id, role, tenant_id, label, is_default)
SELECT ura.user_id, 'business', ura.tenant_id,
    (SELECT t.name FROM tenants t WHERE t.id = ura.tenant_id) || ' - 业务用户',
    FALSE
FROM user_role_assignments ura
WHERE ura.role = 'tenant_admin'
  AND NOT EXISTS (
      SELECT 1 FROM user_role_assignments existing
      WHERE existing.user_id = ura.user_id
        AND existing.tenant_id = ura.tenant_id
        AND existing.role = 'business'
  );
