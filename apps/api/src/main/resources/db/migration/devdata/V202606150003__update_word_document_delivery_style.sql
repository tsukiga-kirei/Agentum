-- 将 Word 文档交付演示能力的默认样式改为中文字号，并补充 titleCentered 配置项。
UPDATE system_capabilities
SET config = '{
  "sourceType": "builtin",
  "deliveryChannel": "document",
  "documentKind": "word",
  "allowNodeStyleOverride": true,
  "maxFileSizeMb": 20,
  "retentionDays": 180,
  "defaultStyle": {
    "chineseFont": "宋体",
    "latinFont": "Times New Roman",
    "bodyFontSize": "小四",
    "heading1FontSize": "三号",
    "heading2FontSize": "四号",
    "heading3FontSize": "小四",
    "lineSpacing": 1.5,
    "firstLineIndentChars": 2,
    "paragraphSpacingAfter": 6,
    "marginTopCm": 2.54,
    "marginBottomCm": 2.54,
    "marginLeftCm": 3.18,
    "marginRightCm": 3.18,
    "titleCentered": false
  }
}'::jsonb
WHERE id = '00000000-0000-0000-0000-000000000613'
  AND code = 'word_document_delivery';
