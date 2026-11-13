-- 页面保活与业务更新时间分开，避免后台执行使无人管理的临时任务永不过期。
ALTER TABLE workflow_runs ADD COLUMN temporary_last_seen_at TIMESTAMPTZ;
UPDATE workflow_runs SET temporary_last_seen_at = CURRENT_TIMESTAMP WHERE saved = false;
CREATE INDEX idx_workflow_runs_temporary_presence ON workflow_runs (temporary_last_seen_at) WHERE saved = false;
