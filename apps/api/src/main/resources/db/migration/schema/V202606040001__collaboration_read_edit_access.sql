-- 自建能力和流程分别维护读取范围、编辑范围；编辑权限在业务层自动包含读取权限。
ALTER TABLE tenant_asset_capabilities
    RENAME COLUMN visibility TO read_scope;

UPDATE tenant_asset_capabilities
SET read_scope = CASE
    WHEN read_scope = 'shared' THEN 'specified'
    ELSE 'self'
END;

ALTER TABLE tenant_asset_capabilities
    ADD COLUMN edit_scope VARCHAR(30) NOT NULL DEFAULT 'self';

ALTER TABLE tenant_asset_shares
    RENAME TO tenant_asset_access_grants;

DROP INDEX uk_tenant_asset_shares_asset_user;
ALTER TABLE tenant_asset_access_grants
    ADD COLUMN access_level VARCHAR(20) NOT NULL DEFAULT 'read';
CREATE UNIQUE INDEX uk_tenant_asset_access_grants_asset_user_level
    ON tenant_asset_access_grants (asset_id, grantee_user_id, access_level);
CREATE INDEX idx_tenant_asset_access_grants_grantee_level
    ON tenant_asset_access_grants (tenant_id, grantee_user_id, access_level, created_at DESC);

ALTER TABLE workflow_definitions
    ADD COLUMN read_scope VARCHAR(30) NOT NULL DEFAULT 'self',
    ADD COLUMN edit_scope VARCHAR(30) NOT NULL DEFAULT 'self';

CREATE TABLE workflow_access_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    grantee_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    access_level VARCHAR(20) NOT NULL,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_workflow_access_grants_workflow_user_level
    ON workflow_access_grants (workflow_id, grantee_user_id, access_level);
CREATE INDEX idx_workflow_access_grants_grantee_level
    ON workflow_access_grants (tenant_id, grantee_user_id, access_level, created_at DESC);

COMMENT ON COLUMN tenant_asset_capabilities.read_scope IS '能力读取/使用范围：self、specified、all';
COMMENT ON COLUMN tenant_asset_capabilities.edit_scope IS '能力内容编辑范围：self、specified、all；编辑权限自动包含读取权限';
COMMENT ON TABLE tenant_asset_access_grants IS '租户自建能力指定成员授权明细，access_level 为 read 或 edit';
COMMENT ON COLUMN workflow_definitions.read_scope IS '流程读取/使用范围：self、specified、all';
COMMENT ON COLUMN workflow_definitions.edit_scope IS '流程内容编辑范围：self、specified、all；编辑权限自动包含读取权限';
COMMENT ON TABLE workflow_access_grants IS '工作流指定成员授权明细，access_level 为 read 或 edit';
