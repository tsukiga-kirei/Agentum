-- 为 Word 文档交付演示能力补充首行缩进模式和厘米缩进配置项。
UPDATE system_capabilities
SET config = jsonb_set(
  jsonb_set(
    config,
    '{defaultStyle,firstLineIndentMode}',
    '"chars"'::jsonb,
    true
  ),
  '{defaultStyle,firstLineIndentCm}',
  '0.75'::jsonb,
  true
)
WHERE id = '00000000-0000-0000-0000-000000000613'
  AND code = 'word_document_delivery';
