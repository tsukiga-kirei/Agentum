-- 本地演示数据：补充系统内置 Word 文档交付能力，并默认开放给云程科技租户。
-- 生产环境应由系统管理员在「系统管理 / 能力管理」中注册、测试并开放给目标租户。

INSERT INTO system_capabilities (id, capability_type, name, code, version, risk_level, status, description, config)
VALUES (
    '00000000-0000-0000-0000-000000000613',
    'delivery',
    'Word 文档交付',
    'word_document_delivery',
    'v1',
    'medium',
    'active',
    '系统内置 Word 文档交付：将 AI Markdown 按流程节点样式配置导出为 .docx 文件。',
    '{
      "sourceType": "builtin",
      "deliveryChannel": "document",
      "documentKind": "word",
      "allowNodeStyleOverride": true,
      "maxFileSizeMb": 20,
      "retentionDays": 180,
      "defaultStyle": {
        "chineseFont": "宋体",
        "latinFont": "Times New Roman",
        "bodyFontSize": 12,
        "heading1FontSize": 16,
        "heading2FontSize": 14,
        "heading3FontSize": 13,
        "lineSpacing": 1.5,
        "firstLineIndentChars": 2,
        "paragraphSpacingAfter": 6,
        "marginTopCm": 2.54,
        "marginBottomCm": 2.54,
        "marginLeftCm": 3.18,
        "marginRightCm": 3.18
      }
    }'::jsonb
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_capability_grants (id, tenant_id, capability_id, status, quota)
VALUES (
    '00000000-0000-0000-0000-000000000623',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000613',
    'enabled',
    '{}'::jsonb
)
ON CONFLICT (id) DO NOTHING;
