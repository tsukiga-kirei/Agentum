-- 分配卡片需要业务名称承载管理意图，标题不再由对象或资源列表临时拼接。
ALTER TABLE page_grants ADD COLUMN grant_group_name VARCHAR(120);
UPDATE page_grants SET grant_group_name = '未命名页签分配' WHERE grant_group_name IS NULL;
ALTER TABLE page_grants ALTER COLUMN grant_group_name SET NOT NULL;

ALTER TABLE resource_grants ADD COLUMN grant_group_name VARCHAR(120);
UPDATE resource_grants SET grant_group_name = '未命名能力分配' WHERE grant_group_name IS NULL;
ALTER TABLE resource_grants ALTER COLUMN grant_group_name SET NOT NULL;

COMMENT ON COLUMN page_grants.grant_group_name IS '页签分配卡片业务名称';
COMMENT ON COLUMN resource_grants.grant_group_name IS '能力分配卡片业务名称';
