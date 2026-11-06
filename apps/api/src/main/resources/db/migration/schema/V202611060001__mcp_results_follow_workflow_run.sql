-- 工具完整结果归属于流程运行；取消与流程无关的独立到期，流程删除后由对象清理任务回收。
DROP INDEX IF EXISTS idx_mcp_datasets_expiry;
ALTER TABLE mcp_datasets DROP COLUMN expires_at;

-- 删除规则中已取消的保留配置，避免草稿再次保存时带回无效字段。
UPDATE system_capabilities c
SET config = jsonb_set(c.config, '{toolResultPolicies}', COALESCE((
    SELECT jsonb_object_agg(rule.key, rule.value - 'retentionDays')
    FROM jsonb_each(c.config -> 'toolResultPolicies') rule
), '{}'::jsonb))
WHERE jsonb_typeof(c.config -> 'toolResultPolicies') = 'object';
UPDATE mcp_datasets SET result_policy = result_policy - 'retentionDays';
