ALTER TABLE workflow_runs ADD COLUMN auto_execution boolean NOT NULL DEFAULT false;
ALTER TABLE workflow_runs ADD COLUMN manual_takeover boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN workflow_runs.auto_execution IS '发起人选择自动确认并由后端连续推进';
COMMENT ON COLUMN workflow_runs.manual_takeover IS '用户主动接管，当前节点保留但停止自动推进';
