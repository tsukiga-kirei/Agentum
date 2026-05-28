-- 授权卡片是一等业务概念：一次分配可以覆盖多个主体和多个页签/能力，明细行继续服务运行时快速判权。
ALTER TABLE page_grants ADD COLUMN grant_group_id UUID;
UPDATE page_grants SET grant_group_id = id WHERE grant_group_id IS NULL;
ALTER TABLE page_grants ALTER COLUMN grant_group_id SET NOT NULL;
CREATE INDEX idx_page_grants_group ON page_grants (tenant_id, grant_group_id);

ALTER TABLE resource_grants ADD COLUMN grant_group_id UUID;
UPDATE resource_grants SET grant_group_id = id WHERE grant_group_id IS NULL;
ALTER TABLE resource_grants ALTER COLUMN grant_group_id SET NOT NULL;
CREATE INDEX idx_resource_grants_group ON resource_grants (tenant_id, grant_group_id);

COMMENT ON COLUMN page_grants.grant_group_id IS '页签分配卡片 ID，同一批主体和页签的交叉明细共享该值';
COMMENT ON COLUMN resource_grants.grant_group_id IS '能力分配卡片 ID，同一批主体和能力的交叉明细共享该值';
