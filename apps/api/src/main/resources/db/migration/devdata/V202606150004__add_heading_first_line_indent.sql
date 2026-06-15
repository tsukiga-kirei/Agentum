-- 为 Word 文档交付演示能力补充标题首行缩进配置项。
UPDATE system_capabilities
SET config = jsonb_set(
  config,
  '{defaultStyle,headingFirstLineIndent}',
  'false'::jsonb,
  true
)
WHERE id = '00000000-0000-0000-0000-000000000613'
  AND code = 'word_document_delivery';
