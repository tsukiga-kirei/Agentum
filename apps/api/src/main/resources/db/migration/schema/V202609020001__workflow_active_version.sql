-- 将“最近发布版本”和“当前业务可用版本”拆开：历史版本保持不可变，切换业务版本只更新指针。
ALTER TABLE workflow_definitions
    ADD COLUMN active_version_id UUID;

UPDATE workflow_definitions definition
SET active_version_id = latest.id
FROM (
    SELECT DISTINCT ON (workflow_id) id, workflow_id
    FROM workflow_versions
    ORDER BY workflow_id, version_number DESC
) latest
WHERE latest.workflow_id = definition.id;

ALTER TABLE workflow_definitions
    ADD CONSTRAINT fk_workflow_definitions_active_version
    FOREIGN KEY (active_version_id) REFERENCES workflow_versions (id) ON DELETE SET NULL;

CREATE INDEX idx_workflow_definitions_active_version
    ON workflow_definitions (active_version_id)
    WHERE active_version_id IS NOT NULL;

COMMENT ON COLUMN workflow_definitions.active_version_id IS '当前业务入口用于新发起任务的不可变工作流版本 ID';
