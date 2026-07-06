-- 定时任务与消息中心基础结构。
-- 定时任务由业务用户配置，运行实例仍落到 workflow_runs；trigger_source 用于任务中心和审计区分手工/系统触发。
ALTER TABLE workflow_runs
    ADD COLUMN trigger_source VARCHAR(30) NOT NULL DEFAULT 'manual',
    ADD COLUMN trigger_schedule_id UUID,
    ADD COLUMN trigger_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_workflow_runs_trigger_source ON workflow_runs (tenant_id, trigger_source, updated_at DESC);
CREATE INDEX idx_workflow_runs_schedule ON workflow_runs (tenant_id, trigger_schedule_id, updated_at DESC);

COMMENT ON COLUMN workflow_runs.trigger_source IS '运行触发来源：manual 手工创建，schedule 定时任务创建';
COMMENT ON COLUMN workflow_runs.trigger_schedule_id IS '定时任务触发时关联的 workflow_schedules.id';
COMMENT ON COLUMN workflow_runs.trigger_payload IS '触发配置快照，定时任务保存输入节点预置值等脱敏配置';

CREATE TABLE workflow_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    workflow_version_number INT NOT NULL,
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    workflow_name VARCHAR(180) NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    shortcut_key VARCHAR(40),
    shortcut_label VARCHAR(80),
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    input_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_run_id UUID REFERENCES workflow_runs (id) ON DELETE SET NULL,
    last_run_state VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_schedules_owner ON workflow_schedules (tenant_id, owner_id, updated_at DESC);
CREATE INDEX idx_workflow_schedules_due ON workflow_schedules (status, next_run_at);
CREATE INDEX idx_workflow_schedules_workflow ON workflow_schedules (tenant_id, workflow_id, updated_at DESC);

COMMENT ON TABLE workflow_schedules IS '业务用户定时任务配置表，用于自动按权限执行已发布流程';
COMMENT ON COLUMN workflow_schedules.workflow_version_id IS '创建/更新配置时锁定的最新发布版本，用于校验输入字段和审计展示';
COMMENT ON COLUMN workflow_schedules.cron_expression IS 'Spring cron 表达式，统一按服务器默认时区计算下一次执行时间';
COMMENT ON COLUMN workflow_schedules.input_payload IS '输入节点预置值；保存配置时必须覆盖发布快照中的必填输入字段';
COMMENT ON COLUMN workflow_schedules.status IS '定时任务状态：active 启用，paused 暂停';

CREATE TABLE workflow_schedule_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    schedule_id UUID NOT NULL REFERENCES workflow_schedules (id) ON DELETE CASCADE,
    run_id UUID REFERENCES workflow_runs (id) ON DELETE SET NULL,
    workflow_id UUID NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    message VARCHAR(600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_schedule_executions_schedule ON workflow_schedule_executions (schedule_id, scheduled_at DESC);
CREATE INDEX idx_workflow_schedule_executions_owner ON workflow_schedule_executions (tenant_id, owner_id, scheduled_at DESC);
CREATE INDEX idx_workflow_schedule_executions_running ON workflow_schedule_executions (status, updated_at DESC);

COMMENT ON TABLE workflow_schedule_executions IS '定时任务每次触发的执行记录，记录成功、中止和关联运行实例';
COMMENT ON COLUMN workflow_schedule_executions.status IS '执行状态：running 执行中，succeeded 成功，aborted 中止';
COMMENT ON COLUMN workflow_schedule_executions.message IS '执行结果摘要或中止原因，禁止写入密钥和外部原始敏感响应';

CREATE TABLE notification_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    scope VARCHAR(30) NOT NULL,
    category VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    content_markdown TEXT NOT NULL,
    source_type VARCHAR(60),
    source_id UUID,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_messages_tenant_time ON notification_messages (tenant_id, created_at DESC);
CREATE INDEX idx_notification_messages_source ON notification_messages (source_type, source_id);

COMMENT ON TABLE notification_messages IS '消息中心消息主体，支持系统公告、租户公告和定时任务结果消息';
COMMENT ON COLUMN notification_messages.scope IS '消息范围：global 全局，tenant 租户，user 单用户';
COMMENT ON COLUMN notification_messages.category IS '消息分类：system_notice 系统通知，schedule_result 定时任务结果';
COMMENT ON COLUMN notification_messages.content_markdown IS 'Markdown 正文，前端按只读 Markdown 渲染';

CREATE TABLE notification_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES notification_messages (id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_receipts_message_user UNIQUE (message_id, user_id)
);

CREATE INDEX idx_notification_receipts_user_unread ON notification_receipts (user_id, read_at, created_at DESC);
CREATE INDEX idx_notification_receipts_user_time ON notification_receipts (user_id, created_at DESC);

COMMENT ON TABLE notification_receipts IS '消息中心用户回执，记录每个用户自己的未读/已读状态';
COMMENT ON COLUMN notification_receipts.read_at IS '用户读取时间，NULL 表示未读';
