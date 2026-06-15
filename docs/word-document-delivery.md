# Word 文档交付说明

本文说明系统内置 `Word 文档交付` 的初版设计、配置口径和接口用法。该能力用于把 AI 节点产生的 Markdown 输出转换为 `.docx` 文件，并在运行态写入交付记录供下载。

## 能力边界

- 交付类型：系统内置交付能力，`deliveryChannel=document`，`documentKind=word`。
- 输入内容：优先使用交付节点配置的 `contentVariable`，也可使用 `markdownContent` 作为兜底 Markdown 模板。
- 输出结果：`.docx` 文件、`delivery_records` 成功记录、运行节点输出中的 `deliveryRecordId` 和 `deliveryResult.downloadUrl`。
- 当前渲染范围：标题、正文、加粗、斜体、行内代码、代码块、引用、无序列表、有序列表和 Markdown 表格。
- 当前存储：本地 `.agentum/deliveries`，可通过 `agentum.delivery.document.storage-root` 覆盖。后续可替换为 MinIO/S3 对象存储。

## 配置分层

Word 文档交付沿用系统能力池模型：

- 系统管理员在系统管理中创建或启用 `Word 文档交付`，配置默认字体、字号、行距、首行缩进、段后间距、页边距、文件大小和保留天数策略。
- 系统管理员把该能力加入租户可用能力池。
- 租户管理员在租户管理中把能力分配给用户、部门或角色。
- 流程设计者在交付节点选择该能力后，配置正文来源变量、文件名模板和节点级样式。节点级样式会随流程草稿和发布版本保存，运行时按快照生成文件。

系统级默认值用于治理和兜底，节点级配置用于具体业务模板。这样可以避免系统管理员替每个流程维护字号和缩进，也避免流程设计者绕过租户未开放的交付能力。

## 交付节点配置字段

常用字段：

| 字段 | 说明 |
| --- | --- |
| `deliveryMode` | 使用交付能力时为 `capability` |
| `deliveryCapabilityId` | 系统内置 Word 文档交付能力 ID |
| `deliveryType` | `word_document` |
| `documentKind` | `word` |
| `contentVariable` | 正文来源变量名，优先从上游 AI / 集群节点输出读取 |
| `markdownContent` | 未选择正文变量时的 Markdown 兜底模板 |
| `fileNameTemplate` | 文件名模板，可使用 `{{runId}}` 等变量 |
| `documentStyle` | 节点级样式快照 |
| `previewMarkdown` | 设计态预览样例，不影响正式运行 |

`documentStyle` 支持：

```json
{
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
```

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
    "bodyFontSize": 12,
    "lineSpacing": 1.5,
    "firstLineIndentChars": 2
  }
}
```

响应为 `application/vnd.openxmlformats-officedocument.wordprocessingml.document` 二进制文件，`Content-Disposition` 中包含文件名。

## 运行态下载接口

交付节点运行成功后，节点输出会包含：

```json
{
  "deliveryRecordId": "交付记录 ID",
  "deliveryStatus": "success",
  "deliveryResult": {
    "adapter": "word_document",
    "fileName": "交付文档.docx",
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

## 后续演进

- 使用 MinIO/S3 替换本地文件存储，并补保留期清理任务。
- 接入 reference.docx 模板、页眉页脚、目录、图片和附件。
- 将复杂文档生成迁移到 Worker，避免大文档阻塞 API 线程。
- 与高风险交付审批、运行审计页和交付物资源范围进一步勾稽。
