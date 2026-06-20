-- 旧失败分支把耗时固定写成 0；利用已有开始/结束时间回填，避免审计页面把历史未知值误解为即时失败。
UPDATE model_call_logs
SET latency_ms = GREATEST(
    0,
    FLOOR(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)::BIGINT
)
WHERE status = 'failed'
  AND latency_ms = 0
  AND completed_at IS NOT NULL;

UPDATE mcp_call_logs
SET latency_ms = GREATEST(
    0,
    FLOOR(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)::BIGINT
)
WHERE status = 'failed'
  AND latency_ms = 0
  AND completed_at IS NOT NULL;
