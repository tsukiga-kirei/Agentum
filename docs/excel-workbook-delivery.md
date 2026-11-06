# Excel 工作簿交付说明

本文说明系统内置 `Excel 工作簿交付` 的设计、配置口径和运行边界。该能力用于把模型输出、上游变量或 Markdown 表格按多 Sheet 模板转换为 `.xlsx` 文件，并在运行态写入交付记录供下载。

## 能力边界

- 交付类型：系统内置交付能力，`deliveryChannel=document`，`documentKind=excel`。
- 输入内容：交付节点的 `excelSheets[].bodyTemplate`。每个 Sheet 都可以使用 `{{变量名}}` 引用上游模型输出或运行变量。
- 输出结果：`.xlsx` 文件、`delivery_records` 成功记录、运行节点输出中的 `deliveryRecordId` 和 `deliveryResult.downloadUrl`。
- 当前解析范围：Markdown 表格、fenced `csv` / `tsv` / `json` 代码块、编号列表、项目符号列表、键值块和普通文本。
- 当前存储：复用 MinIO/S3 兼容对象存储。正式运行交付节点时才会持久化文件；系统能力可选择永久保存或按天保存，永久保存不生成到期时间，按天保存由清理任务删除到期对象。

## 设计心智

Excel 交付不是强制业务人员维护 JSON Schema。第一版采用更适合模型输出的半结构化渲染方式：

```text
Sheet 模板
  -> 渲染 {{变量}}
  -> 宽容识别表格 / 列表 / 键值 / 文本
  -> 命中列、行、单元格规则时尝试类型转换
  -> 转换失败自动回退纯文本
  -> 生成 xlsx 并写入交付记录
```

也就是说，模型可以输出标准 Markdown 表格：

```md
| 风险类型 | 等级 | 金额 |
| --- | --- | --- |
| 司法风险 | 高 | 120000 |
```

也可以输出编号列表：

```md
1. 风险类型：司法风险，等级：高，说明：存在被执行记录
2. 风险类型：经营风险，等级：中，说明：近一年变更频繁
```

渲染器会尽量把它们转成表格。无法识别时按普通文本逐行写入，避免模型轻微格式漂移导致交付失败。

## 交付节点配置字段

常用字段：

| 字段 | 说明 |
| --- | --- |
| `deliveryMode` | 使用交付能力时为 `capability` |
| `deliveryCapabilityId` | 系统内置 Excel 工作簿交付能力 ID |
| `deliveryType` | `excel_workbook` |
| `documentKind` | `excel` |
| `fileNameTemplate` | 文件名模板，默认 `交付表格-{{runNumber}}.xlsx` |
| `excelSheets` | Sheet 模板列表 |

系统能力配置中的 `retentionPolicy` 可选 `permanent` 或 `days`。选择 `days` 时，`retentionDays` 范围为 1～3650 天；历史能力未配置策略时继续按原有保留天数执行。

示例：

```json
{
  "deliveryMode": "capability",
  "deliveryType": "excel_workbook",
  "documentKind": "excel",
  "fileNameTemplate": "风险明细-{{runNumber}}.xlsx",
  "excelSheets": [
    {
      "name": "风险明细",
      "startCell": "A1",
      "defaultCellType": "text",
      "bodyTemplate": "{{risk_table}}",
      "tableStyle": {
        "headerBold": true,
        "freezeHeader": true,
        "autoFilter": true
      },
      "columnRules": [
        {
          "target": "letter",
          "letter": "C",
          "applyFromRow": 2,
          "type": "number",
          "format": "#,##0.00",
          "width": 16
        }
      ],
      "rowRules": [
        {
          "target": "header",
          "bold": true,
          "backgroundColor": "grey"
        }
      ],
      "cellRules": [
        {
          "cell": "A1",
          "bold": true
        }
      ]
    }
  ]
}
```

## 样式与回退规则

覆盖优先级从低到高：

```text
Sheet 默认样式
  < 表格样式
  < 列规则
  < 行规则
  < 单元格规则
```

列规则支持两种定位方式：

- `target: letter`：直接指定 Excel 列字母（A/B/C…），适用于列位置固定的表格。
- `target: name`：按表格第 1 行的列名称精确匹配列。
- `applyFromRow`：从表格第几行开始向下应用列格式（从 1 开始，默认 `2`）；例如第 1 行是表头、从第 2 行开始格式化数据时填 `2`。

默认单元格类型是 `text`。只有列、行或单元格规则声明了 `number`、`currency`、`percent`、`date`、`datetime`、`boolean` 等类型时，后端才尝试转换。转换失败不会让交付失败，该单元格会按文本写入。

为避免公式注入，单元格默认不会写入公式。文本以 `=`、`+`、`-`、`@` 开头时会按文本处理；只有单元格规则显式配置 `allowFormula=true` 时才允许公式。

## 后续演进

- 设计态 Excel 预览下载。
- 上传客户 Excel 模板并按命名区域填充。
- 更严格的列完整性、枚举和必填校验模式。
- 大文件或复杂格式迁移到 Worker。


## 新增：填写文件模板

交付项选择“填写文件模板”后，使用 `generationMode=file_template`，固定引用已发布的 `templateVersionId`，用 `templateBindings` 保存字段来源及明细列映射，用 `templateLocationStyles` 保存本交付项的位置样式覆盖。不设置此模式时，本文已有的内容生成配置与执行方式不变。

模板上传、字段及 Sheet 格式、结构化结果、多来源组装、试填与边界统一见 [系统介绍：新增文件模板交付](./system-overview.md#新增文件模板交付)。模板接口前缀为 `/api/tenants/{tenantId}/delivery-templates`，原件、试填、下载均经过服务端租户和资源授权；具体请求模型见共享 OpenAPI 与 `file-template.schema.json`。


## 独立数据区域与 MCP 数据集

内容生成方式支持 `excelSheets[].regions`，每个区域指定 `startCell`、`sourceNodeId`、`sourcePath`、`header` 和 `columns`。来源路径相对于该上游节点输出，例如 `mcp_datasets.credit_details`；无来源节点时相对于交付变量，但数据集引用必须绑定明确的上游节点。列配置使用 field（来源字段路径）、label、type（text/number/date/boolean）和可选 format。正文可留空，仅使用数据区域生成工作表。

文件模板方式在模板 Spec 设置 `excelRegions: [{sheet, field, startCell}]`，field 为已有明细字段，起点必须与原件样板行的填写标记位置及列顺序一致。在交付字段绑定处选择“绑定 MCP 数据集”，指定上游单智能体节点和 datasetKey，程序从节点 `mcp_datasets` 中读取引用并应用 columnMappings。设置明细 maxItems 时应覆盖预期数据量，最多 100000 条。

两种方式使用相同区域写入器，支持同一 Sheet 左右不同条数的列表。区域可配置 `direction=vertical`（默认，每条记录一行）或 `horizontal`（每条记录一列，字段名竖排）。模板会自动识别同行的多个连续列表、横向样板列，上传和读取已有模板时生成独立区域；字段位置须与方向一致，切换方向不会自动转置原件。模板沿用样板单元格样式及位置覆盖；不支持区域内合并单元格、自动搬移小计/固定内容或扩展独立区域的公式范围。冲突、路径或类型错误明确失败。数据集/区域交付重新执行会重新生成文件，避免复用已变化来源的旧附件。
