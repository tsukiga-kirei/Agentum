-- 流程业务入口开关：与 design status 解耦，允许设计态继续演进同时保留已发布版本供业务发起。
ALTER TABLE workflow_definitions
    ADD COLUMN launch_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN workflow_definitions.launch_enabled IS '是否允许业务工作台发起；false 表示已收回入口，历史版本快照仍保留';
