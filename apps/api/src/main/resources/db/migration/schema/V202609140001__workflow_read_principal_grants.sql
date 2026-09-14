ALTER TABLE workflow_access_grants
    ADD COLUMN principal_type VARCHAR(20),
    ADD COLUMN principal_id UUID;

UPDATE workflow_access_grants
SET principal_type = 'user',
    principal_id = grantee_user_id;

ALTER TABLE workflow_access_grants
    ALTER COLUMN principal_type SET NOT NULL,
    ALTER COLUMN principal_id SET NOT NULL;

DROP INDEX IF EXISTS uk_workflow_access_grants_workflow_user_level;
DROP INDEX IF EXISTS idx_workflow_access_grants_grantee_level;

ALTER TABLE workflow_access_grants
    DROP COLUMN grantee_user_id,
    ADD CONSTRAINT ck_workflow_access_principal_type
        CHECK (principal_type IN ('role', 'department', 'user'));

CREATE UNIQUE INDEX uk_workflow_access_grants_workflow_principal_level
    ON workflow_access_grants (workflow_id, principal_type, principal_id, access_level);

CREATE INDEX idx_workflow_access_grants_principal_level
    ON workflow_access_grants (tenant_id, principal_type, principal_id, access_level, created_at DESC);

COMMENT ON TABLE workflow_access_grants IS '工作流协作授权主体明细，读取权限支持角色、部门和人员，编辑权限仅支持人员';
COMMENT ON COLUMN workflow_access_grants.principal_type IS '授权主体类型：role、department 或 user';
COMMENT ON COLUMN workflow_access_grants.principal_id IS '授权主体 ID';
