export type DocumentDeliveryStyleValues = {
  chineseFont: string;
  latinFont: string;
  bodyFontSize: string | number;
  heading1FontSize: string | number;
  heading2FontSize: string | number;
  heading3FontSize: string | number;
  heading1ChineseFont: string;
  heading1LatinFont: string;
  heading2ChineseFont: string;
  heading2LatinFont: string;
  heading3ChineseFont: string;
  heading3LatinFont: string;
  tableChineseFont: string;
  tableLatinFont: string;
  tableFontSize: string | number;
  tableCellAlignment: string;
  lineSpacingMode: LineSpacingMode;
  lineSpacing: number;
  lineSpacingPt: number;
  firstLineIndentMode: FirstLineIndentMode;
  firstLineIndentChars: number;
  firstLineIndentCm: number;
  paragraphSpacingBefore: number;
  paragraphSpacingAfter: number;
  marginTopCm: number;
  marginBottomCm: number;
  marginLeftCm: number;
  marginRightCm: number;
  titleCentered: boolean;
  headingFirstLineIndent: boolean;
};

export type SelectOption = { value: string; label: string };

export type LineSpacingMode = "multiple" | "exact";
export type FirstLineIndentMode = "chars" | "cm";


export const LINE_SPACING_MODE_OPTIONS: SelectOption[] = [
  { value: "multiple", label: "倍数" },
  { value: "exact", label: "固定磅值" },
];

export const INHERIT_FONT_OPTION: SelectOption = { value: "", label: "继承正文" };

export const CHINESE_FONT_OPTIONS: SelectOption[] = [
  { value: "宋体", label: "宋体" },
  { value: "黑体", label: "黑体" },
  { value: "仿宋", label: "仿宋" },
  { value: "仿宋_GB2312", label: "仿宋_GB2312" },
  { value: "楷体", label: "楷体" },
  { value: "微软雅黑", label: "微软雅黑" },
];

export const INHERITABLE_CHINESE_FONT_OPTIONS: SelectOption[] = [
  INHERIT_FONT_OPTION,
  ...CHINESE_FONT_OPTIONS,
];

export const LATIN_FONT_OPTIONS: SelectOption[] = [
  { value: "Times New Roman", label: "Times New Roman" },
  { value: "Arial", label: "Arial" },
  { value: "Calibri", label: "Calibri" },
  { value: "Georgia", label: "Georgia" },
];

export const INHERITABLE_LATIN_FONT_OPTIONS: SelectOption[] = [
  INHERIT_FONT_OPTION,
  ...LATIN_FONT_OPTIONS,
];

const CHINESE_FONT_SIZE_OPTIONS: SelectOption[] = [
  { value: "初号", label: "初号（42pt）" },
  { value: "小初", label: "小初（36pt）" },
  { value: "一号", label: "一号（26pt）" },
  { value: "小一", label: "小一（24pt）" },
  { value: "二号", label: "二号（22pt）" },
  { value: "小二", label: "小二（18pt）" },
  { value: "三号", label: "三号（16pt）" },
  { value: "小三", label: "小三（15pt）" },
  { value: "四号", label: "四号（14pt）" },
  { value: "小四", label: "小四（12pt）" },
  { value: "五号", label: "五号（11pt）" },
  { value: "小五", label: "小五（9pt）" },
  { value: "六号", label: "六号（8pt）" },
];

const NUMERIC_FONT_SIZE_OPTIONS: SelectOption[] = Array.from({ length: 41 }, (_, index) => {
  const pt = index + 8;
  return { value: String(pt), label: `${pt} pt` };
});

export const FONT_SIZE_OPTIONS: SelectOption[] = [
  ...CHINESE_FONT_SIZE_OPTIONS,
  ...NUMERIC_FONT_SIZE_OPTIONS,
];

export const TABLE_FONT_SIZE_OPTIONS: SelectOption[] = [
  { value: "0", label: "继承正文" },
  ...FONT_SIZE_OPTIONS,
];

export const TABLE_CELL_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "left", label: "左对齐" },
  { value: "center", label: "居中" },
  { value: "right", label: "右对齐" },
  { value: "both", label: "两端对齐" },
];

export const LINE_SPACING_OPTIONS: SelectOption[] = [
  { value: "1", label: "单倍（1.0）" },
  { value: "1.25", label: "1.25 倍" },
  { value: "1.5", label: "1.5 倍（默认）" },
  { value: "1.75", label: "1.75 倍" },
  { value: "2", label: "2 倍" },
  { value: "2.5", label: "2.5 倍" },
  { value: "3", label: "3 倍" },
];

export const LINE_SPACING_PT_OPTIONS: SelectOption[] = Array.from({ length: 67 }, (_, index) => {
  const pt = index + 6;
  return { value: String(pt), label: `${pt} pt` };
});

export function isExactLineSpacingMode(mode: string | undefined): mode is "exact" {
  return mode === "exact";
}

export function readLineSpacingMode(value: unknown): LineSpacingMode {
  return isExactLineSpacingMode(typeof value === "string" ? value : String(value ?? "")) ? "exact" : "multiple";
}

export function stringifyLineSpacingPtValue(value: string | number): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return "18";
  }
  const clamped = Math.min(72, Math.max(6, Math.round(parsed)));
  return String(clamped);
}

export const FIRST_LINE_INDENT_MODE_OPTIONS: SelectOption[] = [
  { value: "chars", label: "字符" },
  { value: "cm", label: "厘米" },
];

export const FIRST_LINE_INDENT_CM_OPTIONS: SelectOption[] = [
  { value: "0", label: "无缩进" },
  { value: "0.25", label: "0.25 cm" },
  { value: "0.5", label: "0.5 cm" },
  { value: "0.75", label: "0.75 cm（默认）" },
  { value: "0.8", label: "0.8 cm" },
  { value: "1", label: "1 cm" },
  { value: "1.25", label: "1.25 cm" },
  { value: "1.5", label: "1.5 cm" },
  { value: "1.75", label: "1.75 cm" },
  { value: "2", label: "2 cm" },
  { value: "2.5", label: "2.5 cm" },
  { value: "3", label: "3 cm" },
];

export function isCmFirstLineIndentMode(mode: string | undefined): mode is "cm" {
  return mode === "cm";
}

export function readFirstLineIndentMode(value: unknown): FirstLineIndentMode {
  return isCmFirstLineIndentMode(typeof value === "string" ? value : String(value ?? "")) ? "cm" : "chars";
}

export function stringifyFirstLineIndentCmValue(value: string | number): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return "0.75";
  }
  return String(parsed);
}

export const FIRST_LINE_INDENT_OPTIONS: SelectOption[] = [
  { value: "0", label: "无缩进" },
  { value: "1", label: "1 字符" },
  { value: "1.5", label: "1.5 字符" },
  { value: "2", label: "2 字符（默认）" },
  { value: "2.5", label: "2.5 字符" },
  { value: "3", label: "3 字符" },
  { value: "4", label: "4 字符" },
  { value: "5", label: "5 字符" },
  { value: "6", label: "6 字符" },
];

export const PARAGRAPH_SPACING_BEFORE_OPTIONS: SelectOption[] = [
  { value: "0", label: "0 pt（默认）" },
  { value: "3", label: "3 pt" },
  { value: "6", label: "6 pt" },
  { value: "9", label: "9 pt" },
  { value: "12", label: "12 pt" },
  { value: "18", label: "18 pt" },
  { value: "24", label: "24 pt" },
];

export const PARAGRAPH_SPACING_AFTER_OPTIONS: SelectOption[] = [
  { value: "0", label: "0 pt" },
  { value: "3", label: "3 pt" },
  { value: "6", label: "6 pt（默认）" },
  { value: "9", label: "9 pt" },
  { value: "12", label: "12 pt" },
  { value: "18", label: "18 pt" },
  { value: "24", label: "24 pt" },
];

/** @deprecated 使用 PARAGRAPH_SPACING_AFTER_OPTIONS */
export const PARAGRAPH_SPACING_OPTIONS = PARAGRAPH_SPACING_AFTER_OPTIONS;

export const TITLE_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "false", label: "默认左对齐" },
  { value: "true", label: "居中" },
];

export const HEADING_INDENT_OPTIONS: SelectOption[] = [
  { value: "false", label: "不应用" },
  { value: "true", label: "应用" },
];

export const MARGIN_CM_OPTIONS: SelectOption[] = [
  { value: "1.27", label: "1.27 cm（窄）" },
  { value: "1.5", label: "1.5 cm" },
  { value: "2", label: "2 cm" },
  { value: "2.54", label: "2.54 cm（标准）" },
  { value: "3", label: "3 cm" },
  { value: "3.18", label: "3.18 cm（公文）" },
  { value: "3.5", label: "3.5 cm" },
  { value: "4", label: "4 cm" },
];

export type MarginPresetKey = "standard" | "moderate" | "narrow" | "custom";

export const MARGIN_PRESET_OPTIONS: Array<SelectOption & { key: MarginPresetKey }> = [
  { key: "standard", value: "standard", label: "标准（上下 2.54 / 左右 3.18 cm）" },
  { key: "moderate", value: "moderate", label: "适中（上下左右 2.54 cm）" },
  { key: "narrow", value: "narrow", label: "窄（上下左右 1.27 cm）" },
  { key: "custom", value: "custom", label: "自定义" },
];

export const MARGIN_PRESETS: Record<Exclude<MarginPresetKey, "custom">, Pick<DocumentDeliveryStyleValues, "marginTopCm" | "marginBottomCm" | "marginLeftCm" | "marginRightCm">> = {
  standard: { marginTopCm: 2.54, marginBottomCm: 2.54, marginLeftCm: 3.18, marginRightCm: 3.18 },
  moderate: { marginTopCm: 2.54, marginBottomCm: 2.54, marginLeftCm: 2.54, marginRightCm: 2.54 },
  narrow: { marginTopCm: 1.27, marginBottomCm: 1.27, marginLeftCm: 1.27, marginRightCm: 1.27 },
};

export function stringifySelectValue(value: string | number | boolean): string {
  return String(value);
}

export function stringifyFontSizeValue(value: string | number): string {
  const text = String(value).trim();
  if (!text) {
    return "12";
  }
  if (FONT_SIZE_OPTIONS.some((option) => option.value === text)) {
    return text;
  }
  if (/^\d+$/.test(text)) {
    return text;
  }
  return text;
}

export function stringifyTableFontSizeValue(value: string | number): string {
  const text = String(value).trim();
  if (!text || text === "0") {
    return "0";
  }
  return stringifyFontSizeValue(value);
}

export function detectMarginPreset(style: Pick<DocumentDeliveryStyleValues, "marginTopCm" | "marginBottomCm" | "marginLeftCm" | "marginRightCm">): MarginPresetKey {
  for (const preset of MARGIN_PRESET_OPTIONS) {
    if (preset.key === "custom") {
      continue;
    }
    const margins = MARGIN_PRESETS[preset.key];
    if (
      style.marginTopCm === margins.marginTopCm
      && style.marginBottomCm === margins.marginBottomCm
      && style.marginLeftCm === margins.marginLeftCm
      && style.marginRightCm === margins.marginRightCm
    ) {
      return preset.key;
    }
  }
  return "custom";
}
