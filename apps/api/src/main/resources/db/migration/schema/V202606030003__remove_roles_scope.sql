ALTER TABLE roles
    DROP COLUMN IF EXISTS scope;

COMMENT ON TABLE roles IS '租户与平台角色表；平台角色 tenant_id 为空，租户内角色由租户管理员维护';
