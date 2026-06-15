-- 补充交付记录字段注释，明确 result_snapshot 语义与 status 枚举值。
COMMENT ON COLUMN delivery_records.result_snapshot IS '交付结果摘要，例如文件对象信息、邮件收件人或适配器响应';
COMMENT ON COLUMN delivery_records.status IS '状态：running、success、failed、expired';
