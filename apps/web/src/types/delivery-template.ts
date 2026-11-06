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
export type TemplateSpec = {
  excelRegions?: { sheet: string; field: string; startCell: string; direction?: "vertical" | "horizontal" }[];
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
  spec: TemplateSpec;
};

/** 只识别连续的样板行或样板列；遇到重复、分散标记时要求明确配置，避免自动推断写错业务区域。 */
export function inferTemplateExcelRegions(spec: TemplateSpec): NonNullable<TemplateSpec["excelRegions"]> {
  const result: NonNullable<TemplateSpec["excelRegions"]> = [];
  for (const sheet of new Set(spec.locations.map(location => location.sheet).filter(Boolean))) {
    for (const field of spec.fields.filter(field => field.type === "table")) {
      const locations = spec.locations.filter(location => location.sheet === sheet && location.field === field.key);
      if (!locations.length) continue;
      const positions = field.columns.map(column => {
        const matches = locations.filter(location => location.id.endsWith(`:${field.key}.${column.key}`));
        const address = matches.length === 1 ? matches[0].id.slice(0, matches[0].id.lastIndexOf(":")) : "";
        const match = /!([A-Z]+)([1-9][0-9]*)$/.exec(address);
        if (!match) throw new Error(`Sheet「${sheet}」的明细「${field.label || field.key}」存在重复或无法识别的标记位置，请手工配置区域`);
        const columnNumber = [...match[1]].reduce((value, character) => value * 26 + character.charCodeAt(0) - 64, 0);
        return { row: Number(match[2]), column: columnNumber, address: `${match[1]}${match[2]}` };
      });
      const first = positions[0];
      if (!first) throw new Error(`明细「${field.key}」没有字段`);
      const vertical = positions.every((position, index) => position.row === first.row && position.column === first.column + index);
      const horizontal = positions.every((position, index) => position.column === first.column && position.row === first.row + index);
      if (!vertical && !horizontal) throw new Error(`Sheet「${sheet}」的明细「${field.label || field.key}」标记不连续，请将字段放在同一样板行或样板列`);
      const previous = spec.excelRegions?.find(region => region.sheet === sheet && region.field === field.key);
      result.push({ sheet, field: field.key, startCell: first.address, direction: positions.length === 1 ? previous?.direction ?? "vertical" : vertical ? "vertical" : "horizontal" });
    }
  }
  return result;
}
export type TemplateBinding = {
  field: string;
  sourceNodeId: string;
  variable: string;
  path: string;
  constant: boolean;
  value?: unknown;
  additionalSources?: { sourceNodeId: string; variable: string; path: string }[];
  separator?: string;
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
  maxLength: 20000,
  maxItems: 200,
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
      column: number;
      rowSpan: number;
      colSpan: number;
      style: Record<string, string | number>;
    }[];
  }[];
  truncated: boolean;
};
