-- 为已有演示模型供应商补全 maxTokens，避免升级后智能体运行因缺少配置而失败。
UPDATE model_providers
SET settings = settings || '{"maxTokens": 8192}'::jsonb
WHERE NOT (settings ? 'maxTokens');
