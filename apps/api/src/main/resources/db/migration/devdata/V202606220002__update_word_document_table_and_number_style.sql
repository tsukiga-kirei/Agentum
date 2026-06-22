-- 为本地 Word 文档交付能力补充数字字体和可控表格格式。
-- 数字字体从同层级已有西文字体复制，避免升级后演示能力的既有字体心智发生变化。
UPDATE system_capabilities
SET config = jsonb_set(
  config,
  '{defaultStyle}',
  COALESCE(config -> 'defaultStyle', '{}'::jsonb) || jsonb_build_object(
    'numberFont', COALESCE(config #>> '{defaultStyle,latinFont}', 'Times New Roman'),
    'heading1NumberFont', COALESCE(config #>> '{defaultStyle,heading1LatinFont}', ''),
    'heading2NumberFont', COALESCE(config #>> '{defaultStyle,heading2LatinFont}', ''),
    'heading3NumberFont', COALESCE(config #>> '{defaultStyle,heading3LatinFont}', ''),
    'tableNumberFont', COALESCE(config #>> '{defaultStyle,tableLatinFont}', ''),
    'tableHeaderBold', false,
    'tableBorders', true,
    'tableBorderWidthPt', 0.5,
    'tableLineSpacingMode', 'multiple',
    'tableLineSpacing', 1.0,
    'tableLineSpacingPt', 12
  ),
  true
)
WHERE id = '00000000-0000-0000-0000-000000000613'
  AND code = 'word_document_delivery';
