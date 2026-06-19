-- 模型调用用量增加可查询的标准字段；原始 usage JSON 继续保留供应商明细。
-- 当前处于开发阶段，不为旧演示记录反推用量，历史行按 0 处理。
ALTER TABLE model_call_logs
    ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE model_call_logs
    ADD CONSTRAINT ck_model_call_logs_token_usage_non_negative
        CHECK (input_tokens >= 0 AND output_tokens >= 0 AND total_tokens >= 0);

CREATE INDEX idx_model_call_logs_tenant_tokens
    ON model_call_logs (tenant_id, created_at DESC, total_tokens);

COMMENT ON COLUMN model_call_logs.input_tokens IS '标准化输入 Token 数，兼容供应商 prompt_tokens/input_tokens 字段';
COMMENT ON COLUMN model_call_logs.output_tokens IS '标准化输出 Token 数，兼容供应商 completion_tokens/output_tokens 字段';
COMMENT ON COLUMN model_call_logs.total_tokens IS '本次模型调用总 Token 数';
