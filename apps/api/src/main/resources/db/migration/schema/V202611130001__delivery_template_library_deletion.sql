-- 模板库删除不销毁固定版本：运行、重试和审计仍需读取原件；新选择列表过滤已删除记录。
ALTER TABLE delivery_template_versions ADD COLUMN deleted_at timestamptz;
COMMENT ON COLUMN delivery_template_versions.deleted_at IS '从模板库删除的时间；保留版本供已有流程引用';
