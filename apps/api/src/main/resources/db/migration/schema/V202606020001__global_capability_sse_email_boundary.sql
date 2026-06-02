-- 全局能力边界收敛：
-- 1. MCP 统一使用 SSE 接入，不再保存 stdio 命令、参数和工作目录。
-- 2. 交付能力当前阶段仅保留邮箱通道，SMTP 密码由后端服务加密保存。
-- 3. 提示词模板功能保留；这里只删除 capabilities/prompt-templates 源码目录，不删除能力治理数据。

UPDATE system_capabilities
SET config = jsonb_build_object(
    'transport', 'sse',
    'sseUrl', COALESCE(NULLIF(config ->> 'sseUrl', ''), 'http://localhost:18080/sse'),
    'toolCatalogUrl', COALESCE(NULLIF(config ->> 'toolCatalogUrl', ''), 'http://localhost:18080/agentum/tools')
)
WHERE capability_type = 'mcp';

COMMENT ON COLUMN system_capabilities.capability_type IS '系统级能力类型：skill、mcp、prompt_template、delivery；MCP 当前统一使用 SSE 接入';
