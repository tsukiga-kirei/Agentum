-- 开发期清理：移除已取消的设计态暂停点计数与权限升级后的废弃表。

ALTER TABLE workflow_definitions
    DROP COLUMN IF EXISTS pause_point_count;

ALTER TABLE workflow_versions
    DROP COLUMN IF EXISTS pause_point_count;

DROP TABLE IF EXISTS system_user_roles;
