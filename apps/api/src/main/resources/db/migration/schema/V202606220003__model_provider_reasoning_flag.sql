ALTER TABLE model_providers
    ADD COLUMN reasoning_model BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE model_providers
SET reasoning_model = FALSE;

COMMENT ON COLUMN model_providers.reasoning_model IS '是否为支持显式深度推理开关与推理内容返回的模型';
