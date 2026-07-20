-- Word 列表默认继承正文首行缩进；需要传统列表版式时可在系统能力或流程节点中改为悬挂缩进。
UPDATE system_capabilities
SET config = jsonb_set(
    config,
    '{defaultStyle}',
    COALESCE(config -> 'defaultStyle', '{}'::jsonb) || jsonb_build_object(
      'orderedListIndentMode', 'body',
      'orderedListLeftIndentChars', 3,
      'orderedListHangingIndentChars', 1.5,
      'unorderedListIndentMode', 'body',
      'unorderedListLeftIndentChars', 3,
      'unorderedListHangingIndentChars', 1.5
    ),
    true
  )
WHERE capability_type = 'delivery'
  AND code = 'word_document_delivery';
