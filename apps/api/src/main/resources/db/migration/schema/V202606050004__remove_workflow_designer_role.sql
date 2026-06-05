-- 下线 workflow_designer 内置角色：流程设计入口统一由 page_grants 的 designer 页签控制。
DELETE FROM user_membership_roles
WHERE role_id IN (
    SELECT id FROM roles WHERE code = 'workflow_designer' AND tenant_id IS NOT NULL
);

DELETE FROM page_grants
WHERE principal_type = 'role'
  AND principal_id IN (
      SELECT id FROM roles WHERE code = 'workflow_designer' AND tenant_id IS NOT NULL
  );

DELETE FROM resource_grants
WHERE principal_type = 'role'
  AND principal_id IN (
      SELECT id FROM roles WHERE code = 'workflow_designer' AND tenant_id IS NOT NULL
  );

DELETE FROM permission_policies
WHERE role_id IN (
    SELECT id FROM roles WHERE code = 'workflow_designer' AND tenant_id IS NOT NULL
);

DELETE FROM roles
WHERE code = 'workflow_designer'
  AND tenant_id IS NOT NULL;
