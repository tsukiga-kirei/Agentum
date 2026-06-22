# Word 文档交付说明

本文说明系统内置 `Word 文档交付` 的初版设计、配置口径和接口用法。该能力用于把 AI 节点产生的 Markdown 输出转换为 `.docx` 文件，并在运行态写入交付记录供下载。

## 能力边界

- 交付类型：系统内置交付能力，`deliveryChannel=document`，`documentKind=word`。
- 输入内容：交付节点的 `markdownContent` 就是最终 Markdown 模板，运行时将模板变量替换后转换为 Word。
- 输出结果：`.docx` 文件、`delivery_records` 成功记录、运行节点输出中的 `deliveryRecordId` 和 `deliveryResult.downloadUrl`。
- 当前渲染范围：标题、正文、加粗、斜体、行内代码、代码块、引用、无序列表、有序列表和 Markdown 表格。
- 当前存储：MinIO/S3 兼容对象存储。默认 bucket 为 `agentum`，对象前缀为 `deliveries/documents`，可通过 `MINIO_BUCKET` 和 `MINIO_OBJECT_PREFIX` 覆盖。设计态预览只即时下载，不写入 MinIO。

## 配置分层

Word 文档交付沿用系统能力池模型：

- 系统管理员在系统管理中创建或启用 `Word 文档交付`，配置默认中西文字体、正文/标题/表格数字字体、字号、行距、表格格式、首行缩进、段后间距、页边距、首行标题对齐、文件大小和保留天数策略。
- 系统管理员把该能力加入租户可用能力池。
- 租户管理员在租户管理中把能力分配给用户、部门或角色。
- 流程设计者在交付节点选择该能力后，配置交付正文模板、文件名模板和节点级样式。节点级样式会随流程草稿和发布版本保存，运行时按快照生成文件。

系统级默认值用于治理和兜底，节点级配置用于具体业务模板。这样可以避免系统管理员替每个流程维护字号和缩进，也避免流程设计者绕过租户未开放的交付能力。

## 交付节点配置字段

常用字段：

| 字段 | 说明 |
| --- | --- |
| `deliveryMode` | 使用交付能力时为 `capability` |
| `deliveryCapabilityId` | 系统内置 Word 文档交付能力 ID |
| `deliveryType` | `word_document` |
| `documentKind` | `word` |
| `markdownContent` | 最终交付正文模板，可使用 `{{变量名}}` 引用上游输出 |
| `fileNameTemplate` | 文件名模板，默认 `交付文档-{{runNumber}}.docx`，可使用 `{{runNumber}}`、`{{date}}`、`{{dateCompact}}` 和上游变量 |
| `documentStyle` | 节点级样式快照 |
| `previewMarkdown` | 设计态导出样例，不替换变量，不影响正式运行 |

`documentStyle` 支持：

```json
{
  "chineseFont": "宋体",
  "latinFont": "Times New Roman",
  "numberFont": "Times New Roman",
  "bodyFontSize": "小四",
  "heading1FontSize": "三号",
  "heading2FontSize": "四号",
  "heading3FontSize": 13,
  "heading1NumberFont": "Times New Roman",
  "heading2NumberFont": "Times New Roman",
  "heading3NumberFont": "Times New Roman",
  "tableNumberFont": "Times New Roman",
  "tableHeaderBold": false,
  "tableBorders": true,
  "tableBorderWidthPt": 0.5,
  "tableLineSpacingMode": "multiple",
  "tableLineSpacing": 1.0,
  "lineSpacing": 1.5,
  "firstLineIndentChars": 2,
  "paragraphSpacingAfter": 6,
  "marginTopCm": 2.54,
  "marginBottomCm": 2.54,
  "marginLeftCm": 3.18,
  "marginRightCm": 3.18,
  "titleCentered": true
}
```

数字字体按正文、一级标题、二级标题、三级标题和表格分别配置；标题与表格留空时继承正文数字字体。渲染器会把数字字符拆成独立 Word Run，确保配置实际生效，而不是仅保存到节点快照。

表格不再附加首行底色等默认样式。`tableHeaderBold` 控制首行是否加粗；`tableBorders=false` 时不输出框线，开启时统一使用全边框，`tableBorderWidthPt` 默认 `0.5` 磅；表格行距由 `tableLineSpacingMode`、`tableLineSpacing` 和 `tableLineSpacingPt` 独立控制，不继承正文行距。

字号字段支持 pt 数字，也支持中文字号名：`初号`、`小初`、`一号`、`小一`、`二号`、`小二`、`三号`、`小三`、`四号`、`小四`、`五号`、`小五`、`六号`。

系统级 `maxFileSizeMb` 会在运行态 docx 生成后校验文件大小，超过限制时交付失败并记录错误；`retentionDays` 会写入交付结果的 `expiresAt`，后台清理任务按该时间删除 MinIO 对象并把交付记录标记为 `expired`。

## 设计态预览接口

```http
POST /api/tenants/{tenantId}/document-deliveries/preview
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
{
  "capabilityId": "00000000-0000-0000-0000-000000000613",
  "markdown": "# 交付文档\n\n这是一段正文。",
  "fileName": "交付文档-preview.docx",
  "title": "交付文档",
  "style": {
    "chineseFont": "宋体",
    "latinFont": "Times New Roman",
    "bodyFontSize": "小四",
    "lineSpacing": 1.5,
    "firstLineIndentChars": 2,
    "titleCentered": true
  }
}
```

响应为 `application/vnd.openxmlformats-officedocument.wordprocessingml.document` 二进制文件，`Content-Disposition` 中包含文件名。

设计态预览接口只返回即时生成的二进制文件，不写入 MinIO，也不会生成正式交付记录。正式运行交付节点时才会把 docx 持久化到 MinIO。

## 运行态下载接口

交付节点运行成功后，节点输出会包含：

```json
{
  "deliveryRecordId": "交付记录 ID",
  "deliveryStatus": "success",
  "deliveryResult": {
    "adapter": "word_document",
    "fileName": "交付文档.docx",
    "storageProvider": "minio",
    "storageKey": "deliveries/documents/{tenantId}/{recordId}/交付文档.docx",
    "retentionDays": 180,
    "expiresAt": "2026-12-12T02:00:00Z",
    "downloadUrl": "/api/tenants/{tenantId}/delivery-records/{recordId}/download"
  }
}
```

下载接口：

```http
GET /api/tenants/{tenantId}/delivery-records/{recordId}/download
Authorization: Bearer <token>
```

下载时后端会重新校验工作台访问权限和租户边界，并只读取当前租户下对应交付记录的文件。

MinIO 默认配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MINIO_ENDPOINT` | `http://localhost:9000` | 后端直连 MinIO 的 endpoint；Docker Compose API 容器中覆盖为 `http://minio:9000` |
| `MINIO_ACCESS_KEY` | `agentum` | MinIO 访问账号 |
| `MINIO_SECRET_KEY` | `agentum_dev_password` | MinIO 访问密钥 |
| `MINIO_BUCKET` | `agentum` | 交付文档所在 bucket |
| `MINIO_OBJECT_PREFIX` | `deliveries/documents` | Word 文档对象前缀 |
| `MINIO_AUTO_CREATE_BUCKET` | `true` | bucket 不存在时是否由后端自动创建 |

## 后续演进

- 补对象存储生命周期策略，与应用侧保留期清理互为兜底。
- 接入 reference.docx 模板、页眉页脚、目录、图片和附件。
- 将复杂文档生成迁移到 Worker，避免大文档阻塞 API 线程。
- 与高风险交付审批、运行审计页和交付物资源范围进一步勾稽。
