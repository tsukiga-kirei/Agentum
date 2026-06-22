-- 为本地 Word 文档交付能力补充多级标题、正文对齐、段距单位与表格单元格垂直排版等新增默认项。
-- 这些字段在前后端均有代码级兜底，缺失时也能正常渲染；此处把演示能力的系统级默认值落库，
-- 保证「系统管理 / 能力管理」回填的默认项与新版样式心智一致。四五级标题字号/字体默认继承三级，
-- 逐段个性化规则为节点级配置，故均不写入系统级 defaultStyle。
UPDATE system_capabilities
SET config = jsonb_set(
  config,
  '{defaultStyle}',
  COALESCE(config -> 'defaultStyle', '{}'::jsonb) || jsonb_build_object(
    'bodyAlignment', 'left',
    'heading1Bold', true,
    'heading2Bold', true,
    'heading3Bold', true,
    'heading4Bold', true,
    'heading5Bold', true,
    'tableCellVerticalAlignment', 'center',
    'tableCellPaddingVerticalPt', 1.5,
    'paragraphSpacingUnit', 'pt'
  ),
  true
)
WHERE id = '00000000-0000-0000-0000-000000000613'
  AND code = 'word_document_delivery';
