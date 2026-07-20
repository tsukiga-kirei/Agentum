-- 用按级标题对齐替代“首个内容段居中”，并让可见空行默认继承目标段落字号与正文行距。
UPDATE system_capabilities
SET config = jsonb_set(
    config,
    '{defaultStyle}',
    COALESCE(config -> 'defaultStyle', '{}'::jsonb) || jsonb_build_object(
      'heading1Alignment', 'left',
      'heading2Alignment', 'left',
      'heading3Alignment', 'left',
      'heading4Alignment', 'left',
      'heading5Alignment', 'left',
      'blankLineHeightMode', 'target',
      'blankLineHeightPt', 18
    ),
    true
  )
WHERE capability_type = 'delivery'
  AND code = 'word_document_delivery';
