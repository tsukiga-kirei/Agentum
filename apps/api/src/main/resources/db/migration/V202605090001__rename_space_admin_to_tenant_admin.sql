-- 将早期“空间管理”演示身份平滑升级为“租户管理”。
-- 不能直接改已发布的 V202605080001，否则已初始化的本地库会触发 Flyway checksum mismatch。

UPDATE roles
SET
    code = 'tenant_admin',
    name = '租户管理员',
    scope = 'tenant',
    description = '管理租户成员、角色权限、资源授权和需求配置',
    updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000213'
  AND code = 'space_admin';

UPDATE users
SET
    username = 'tenantadmin',
    display_name = '租户管理员',
    email = 'tenantadmin@agentum.dev',
    updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000004'
  AND username = 'spaceadmin';
