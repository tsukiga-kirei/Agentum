export type TemplateField = {
  key: string;
  label: string;
  type: "text" | "paragraphs" | "markdown" | "number" | "date" | "boolean" | "table";
  required: boolean;
  maxLength?: number;
  maxItems?: number;
  description: string;
  columns: TemplateField[];
  enumValues?: string[];
};
export type TemplateStyle = Record<string, string | number | boolean>;
export type TemplateLocation = {
  id: string;
  field: string;
  label: string;
  sheet: string;
  style: TemplateStyle;
};
export type TemplateFieldPreset = "text" | "number" | "amount" | "rate" | "date" | "boolean" | "custom";
export type TemplateFormatGroup = { id: string; name: string; preset: TemplateFieldPreset; fields: string[]; style: TemplateStyle };
export type TemplateSpec = {
  formatGroups?: TemplateFormatGroup[];
  excelRegions?: { sheet: string; field: string; startCell: string; column?: string; growth?: "shift" | "fixed"; rangeName?: string; direction?: "vertical" | "horizontal" }[];
  fields: TemplateField[];
  locations: TemplateLocation[];
  defaultStyle: TemplateStyle;
  sheetStyles: Record<string, TemplateStyle>;
};
export type DeliveryTemplate = {
  id: string;
  templateId: string;
  name: string;
  fileName: string;
  kind: "word" | "excel";
  version: number;
  status: "draft" | "published";
  readScope: "self" | "all";
  createdBy: string;
  updatedAt: string;
  deleted: boolean;
  spec: TemplateSpec;
};

/** 每个明细标记保留自己的填写位置；数组顺序与字段位置分开，不压缩空值。 */
export function inferTemplateExcelRegions(spec: TemplateSpec): NonNullable<TemplateSpec["excelRegions"]> {
  return spec.locations.filter(location => location.sheet && spec.fields.some(field => field.key === location.field && field.type === "table")).map(location => {
    const parse = (id: string) => {
      const match = /!([A-Z]+)([1-9][0-9]*):[^:]+\.([a-z][a-z0-9_]*)$/.exec(id);
      if (!match) throw new Error("无法识别明细填写位置，请重新上传原文件");
      return { address: `${match[1]}${match[2]}`, x: match[1], y: match[2], column: match[3] };
    };
    const position = parse(location.id);
    const horizontal = spec.locations.some(peer => {
      if (peer.id === location.id || peer.sheet !== location.sheet || peer.field !== location.field) return false;
      const other = parse(peer.id);
      return other.x === position.x && other.y !== position.y && other.column !== position.column;
    });
    const previous = spec.excelRegions?.find(region => region.sheet === location.sheet && region.field === location.field && region.startCell === position.address && region.column === position.column);
    return { sheet: location.sheet, field: location.field, column: position.column, startCell: position.address, growth: previous?.growth ?? "shift", ...(previous?.rangeName ? { rangeName: previous.rangeName } : {}), direction: previous?.direction ?? (horizontal ? "horizontal" : "vertical") };
  });
}
export type TemplateBinding = {
  field: string;
  sourceNodeId: string;
  variable: string;
  path: string;
  constant: boolean;
  value?: unknown;
  columnMappings?: Record<string, string>;
};
export type StructuredOutputConfig = {
  enabled: boolean;
  variable: string;
  fields: TemplateField[];
};
export type TemplateSource = {
  name: string;
  sourceNodeId: string;
  sourceNodeName: string;
  type: string;
  fields?: TemplateField[];
  datasetPath?: string;
  datasetColumns?: string[];
};
export const templateFieldTypes = [
  { value: "text", label: "文字" },
  { value: "paragraphs", label: "多段正文" },
  { value: "markdown", label: "Markdown 内容" },
  { value: "number", label: "数字" },
  { value: "date", label: "日期" },
  { value: "boolean", label: "是／否" },
  { value: "table", label: "表格明细" },
];
export const newTemplateField = (key = ""): TemplateField => ({
  key,
  label: "",
  type: "text",
  required: true,
  description: "",
  columns: [],
});
export function sampleTemplateValue(field: TemplateField): unknown {
  if (field.enumValues?.length) return field.enumValues[0];
  if (field.type === "table")
    return [
      Object.fromEntries(field.columns.map((column) => [column.key, sampleTemplateValue(column)])),
    ];
  if (field.type === "paragraphs") return ["第一段示例内容。", "第二段示例内容。"];
  if (field.type === "number") return 1200.5;
  if (field.type === "boolean") return true;
  if (field.type === "date") return new Date().toLocaleDateString("sv-SE");
  return `${field.label || field.key}示例内容`;
}

export type TemplateWorkbookSheet = {
  name: string;
  widths: number[];
  rows: {
    height: number;
    cells: {
      text: string;
      formula?: string | null;
      column: number;
      rowSpan: number;
      colSpan: number;
      style: Record<string, string | number>;
    }[];
  }[];
  truncated: boolean;
};

/** 批量设置包含明细列，保留类型、位置和其他约束。 */
export function setTemplateFieldsRequired(fields: TemplateField[], required: boolean): TemplateField[] {
  return fields.map(field => ({ ...field, required, columns: setTemplateFieldsRequired(field.columns, required) }));
}

/** 文件名仅接受标量；对象可选择具体字段，列表和完整 MCP 数据集不参与命名。 */
export function templateFileNameVariables(sources: TemplateSource[]): { value: string; label: string }[] {
  const items = new Map([
    ["runNumber", { value: "runNumber", label: "运行编号 · runNumber" }],
    ["date", { value: "date", label: "运行当天日期 · date" }],
    ["dateCompact", { value: "dateCompact", label: "运行当天日期（紧凑） · dateCompact" }],
  ]);
  for (const source of sources.filter(item => !item.datasetPath)) {
    if (["string", "text", "date", "number", "boolean"].includes(source.type))
      items.set(source.name, { value: source.name, label: `${source.sourceNodeName} → ${source.name}` });
    for (const field of source.fields ?? []) {
      if (!["text", "date", "number", "boolean"].includes(field.type)) continue;
      const value = `${source.name}.${field.key}`;
      items.set(value, { value, label: `${source.sourceNodeName} → ${source.name} → ${field.label || field.key}` });
    }
  }
  return [...items.values()];
}
