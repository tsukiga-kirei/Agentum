-- 任务中心优化：运行实例增加“是否已保存”和“运行编号”，用于区分草稿、待办与任务记录。
ALTER TABLE workflow_runs
    ADD COLUMN saved BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN run_number VARCHAR(40) NOT NULL DEFAULT '';

-- 历史数据视为已保存，并基于开始时间补运行编号，避免旧任务从待办/记录中消失。
UPDATE workflow_runs
SET saved = true,
    run_number = to_char(started_at AT TIME ZONE 'UTC', 'YYYYMMDD') || '-' || upper(substring(replace(id::text, '-', ''), 1, 8))
WHERE run_number = '';

CREATE INDEX idx_workflow_runs_active ON workflow_runs (tenant_id, saved, state, updated_at DESC);

COMMENT ON COLUMN workflow_runs.saved IS '是否已主动保存；未保存草稿退出时删除，不进入待办列表';
COMMENT ON COLUMN workflow_runs.run_number IS '运行编号，含日期前缀，用于待办和任务记录展示';
