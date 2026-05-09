-- 系统管理演示数据：模型供应商、全局能力与租户授权，便于阶段 A 工作台联调；生产环境应改为管理员界面创建。
INSERT INTO model_providers (id, name, provider_type, base_url, status, credential_ref, default_model, settings)
VALUES (
    '00000000-0000-0000-0000-000000000601',
    'OpenAI 兼容供应商',
    'openai-compatible',
    'http://localhost:11434/v1',
    'active',
    NULL,
    'gpt-4o-mini',
    '{}'::jsonb
);

INSERT INTO system_capabilities (id, capability_type, name, code, version, risk_level, status, config)
VALUES
    ('00000000-0000-0000-0000-000000000611', 'mcp', '文件读取 MCP', 'file_read_mcp', 'v1', 'medium', 'active', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000612', 'skill', '需求拆解 Skill', 'requirement_breakdown', 'v1', 'low', 'active', '{}'::jsonb);

INSERT INTO tenant_capability_grants (id, tenant_id, capability_id, status, quota)
VALUES
    (
        '00000000-0000-0000-0000-000000000621',
        '00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000611',
        'enabled',
        '{}'::jsonb
    ),
    (
        '00000000-0000-0000-0000-000000000622',
        '00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000612',
        'enabled',
        '{}'::jsonb
    );
