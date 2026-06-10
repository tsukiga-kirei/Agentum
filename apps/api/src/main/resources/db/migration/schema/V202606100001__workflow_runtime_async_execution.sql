-- 运行态异步执行基础表。
-- 1) workflow_run_execution_jobs：每次「执行节点」动作的作业事实记录，是前端 activeJob 与超时回收的判定依据。
-- 2) workflow_cluster_agent_runs：智能体集群子智能体的逐个落库结果，支撑真并发执行与「恢复进度」时只重跑失败子智能体。
CREATE TABLE workflow_run_execution_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(200) NOT NULL,
    operator_id UUID REFERENCES users (id) ON DELETE SET NULL,
    request_id VARCHAR(64),
    error_code VARCHAR(80),
    error_message VARCHAR(600),
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    worker_id VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_run_execution_jobs_idempotency
    ON workflow_run_execution_jobs (idempotency_key);
CREATE INDEX idx_run_execution_jobs_run_status
    ON workflow_run_execution_jobs (run_id, status, enqueued_at DESC);
CREATE INDEX idx_run_execution_jobs_stale
    ON workflow_run_execution_jobs (status, started_at)
    WHERE status IN ('queued', 'running');

COMMENT ON TABLE workflow_run_execution_jobs IS '工作流节点执行作业表，记录 MQ 投递的每次节点执行命令及其终态';
COMMENT ON COLUMN workflow_run_execution_jobs.status IS '作业状态：queued / running / succeeded / failed / canceled';
COMMENT ON COLUMN workflow_run_execution_jobs.idempotency_key IS '幂等键，格式 runId:nodeRunId:attempt，防止 MQ 重复消费';
COMMENT ON COLUMN workflow_run_execution_jobs.deadline_at IS '执行截止时间，超时由回收器中止并标记失败';

CREATE TABLE workflow_cluster_agent_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    agent_index INT NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    output JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    error_message VARCHAR(600),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_cluster_agent_runs_node_index
    ON workflow_cluster_agent_runs (node_run_id, agent_index);
CREATE INDEX idx_cluster_agent_runs_run
    ON workflow_cluster_agent_runs (run_id, node_run_id);

COMMENT ON TABLE workflow_cluster_agent_runs IS '智能体集群子智能体运行表，逐个落库结果以支撑并发执行与失败部分恢复';
COMMENT ON COLUMN workflow_cluster_agent_runs.agent_index IS '子智能体在节点配置 clusterAgents 中的下标，作为节点内身份标识';
COMMENT ON COLUMN workflow_cluster_agent_runs.status IS '子智能体状态：running / succeeded / failed / canceled';
