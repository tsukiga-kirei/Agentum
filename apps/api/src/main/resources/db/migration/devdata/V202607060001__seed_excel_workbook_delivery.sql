-- 本地演示数据：补充系统内置 Excel 工作簿交付能力，并默认开放给云程科技租户。
-- 生产环境应由系统管理员在「系统管理 / 能力管理」中注册、测试并开放给目标租户。

INSERT INTO system_capabilities (id, capability_type, name, code, version, risk_level, status, description, config)
VALUES (
    '00000000-0000-0000-0000-000000000614',
    'delivery',
    'Excel 工作簿交付',
    'excel_workbook_delivery',
    'v1',
    'medium',
    'active',
    '系统内置 Excel 工作簿交付：按 Sheet 模板宽容识别 AI 输出中的 Markdown 表格、列表、键值块和普通文本，并导出为 .xlsx 文件。',
    '{
      "sourceType": "builtin",
      "deliveryChannel": "document",
      "documentKind": "excel",
      "maxFileSizeMb": 20,
      "retentionDays": 180
    }'::jsonb
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_capability_grants (id, tenant_id, capability_id, status, quota)
VALUES (
    '00000000-0000-0000-0000-000000000624',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000614',
    'enabled',
    '{}'::jsonb
)
ON CONFLICT (id) DO NOTHING;
