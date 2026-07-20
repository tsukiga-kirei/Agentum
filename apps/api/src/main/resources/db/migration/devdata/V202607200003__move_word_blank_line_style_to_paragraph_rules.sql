-- 空行高度仅属于节点个性化段落规则；系统默认样式只保留 Word 原生段落属性。
UPDATE system_capabilities
SET config = jsonb_set(
    config,
    '{defaultStyle}',
    COALESCE(config -> 'defaultStyle', '{}'::jsonb) - 'blankLineHeightMode' - 'blankLineHeightPt',
    true
  )
WHERE capability_type = 'delivery'
  AND code = 'word_document_delivery';
