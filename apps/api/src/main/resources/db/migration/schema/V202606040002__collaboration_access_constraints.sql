-- 已应用的双权限迁移保持不可变；补充旧索引清理和数据库枚举约束。
DROP INDEX idx_tenant_asset_shares_grantee;

ALTER TABLE tenant_asset_capabilities
    ADD CONSTRAINT ck_tenant_asset_read_scope CHECK (read_scope IN ('self', 'specified', 'all')),
    ADD CONSTRAINT ck_tenant_asset_edit_scope CHECK (edit_scope IN ('self', 'specified', 'all'));
ALTER TABLE tenant_asset_access_grants
    ADD CONSTRAINT ck_tenant_asset_access_level CHECK (access_level IN ('read', 'edit'));
ALTER TABLE workflow_definitions
    ADD CONSTRAINT ck_workflow_read_scope CHECK (read_scope IN ('self', 'specified', 'all')),
    ADD CONSTRAINT ck_workflow_edit_scope CHECK (edit_scope IN ('self', 'specified', 'all'));
ALTER TABLE workflow_access_grants
    ADD CONSTRAINT ck_workflow_access_level CHECK (access_level IN ('read', 'edit'));
