-- 第二层租户内角色权限应由租户管理员在租户管理中配置，不由平台迁移预置固定模板。
-- 保留 tenant_org_roles 表结构，便于后续实现按用户、部门、租户自定义角色分配菜单页签和具体动作。
DELETE FROM tenant_org_roles
WHERE is_system = TRUE
  AND name IN ('默认成员', '流程设计者', '审计员', '全功能用户');
