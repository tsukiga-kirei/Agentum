# Excel 工作簿交付说明

本文说明系统内置 `Excel 工作簿交付` 的设计、配置口径和运行边界。该能力用于把模型输出、上游变量或 Markdown 表格按多 Sheet 模板转换为 `.xlsx` 文件，并在运行态写入交付记录供下载。

## 能力边界

- 交付类型：系统内置交付能力，`deliveryChannel=document`，`documentKind=excel`。
- 输入内容：交付节点的 `excelSheets[].bodyTemplate`。每个 Sheet 都可以使用 `{{变量名}}` 引用上游模型输出或运行变量。
- 输出结果：`.xlsx` 文件、`delivery_records` 成功记录、运行节点输出中的 `deliveryRecordId` 和 `deliveryResult.downloadUrl`。
- 当前解析范围：Markdown 表格、fenced `csv` / `tsv` / `json` 代码块、编号列表、项目符号列表、键值块和普通文本。
- 当前存储：复用 MinIO/S3 兼容对象存储。设计态暂不提供 Excel 预览接口，正式运行交付节点时才会持久化文件。

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
          "match": "金额",
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

默认单元格类型是 `text`。只有列、行或单元格规则声明了 `number`、`currency`、`percent`、`date`、`datetime`、`boolean` 等类型时，后端才尝试转换。转换失败不会让交付失败，该单元格会按文本写入。

为避免公式注入，单元格默认不会写入公式。文本以 `=`、`+`、`-`、`@` 开头时会按文本处理；只有单元格规则显式配置 `allowFormula=true` 时才允许公式。

## 后续演进

- 设计态 Excel 预览下载。
- 上传客户 Excel 模板并按命名区域填充。
- 更严格的列完整性、枚举和必填校验模式。
- 大文件或复杂格式迁移到 Worker。
