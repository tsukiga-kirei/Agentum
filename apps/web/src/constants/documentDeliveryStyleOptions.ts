export type DocumentDeliveryStyleValues = {
  chineseFont: string;
  latinFont: string;
  numberFont: string;
  bodyFontSize: string | number;
  bodyAlignment: string;
  heading1Alignment: string;
  heading2Alignment: string;
  heading3Alignment: string;
  heading4Alignment: string;
  heading5Alignment: string;
  heading1FontSize: string | number;
  heading2FontSize: string | number;
  heading3FontSize: string | number;
  heading4FontSize: string | number;
  heading5FontSize: string | number;
  heading1ChineseFont: string;
  heading1LatinFont: string;
  heading1NumberFont: string;
  heading2ChineseFont: string;
  heading2LatinFont: string;
  heading2NumberFont: string;
  heading3ChineseFont: string;
  heading3LatinFont: string;
  heading3NumberFont: string;
  heading4ChineseFont: string;
  heading4LatinFont: string;
  heading4NumberFont: string;
  heading5ChineseFont: string;
  heading5LatinFont: string;
  heading5NumberFont: string;
  heading1Bold: boolean;
  heading2Bold: boolean;
  heading3Bold: boolean;
  heading4Bold: boolean;
  heading5Bold: boolean;
  tableChineseFont: string;
  tableLatinFont: string;
  tableNumberFont: string;
  tableFontSize: string | number;
  tableCellAlignment: string;
  tableCellVerticalAlignment: string;
  tableCellPaddingVerticalPt: number;
  tableHeaderBold: boolean;
  tableBorders: boolean;
  tableBorderWidthPt: number;
  tableLineSpacingMode: LineSpacingMode;
  tableLineSpacing: number;
  tableLineSpacingPt: number;
  lineSpacingMode: LineSpacingMode;
  lineSpacing: number;
  lineSpacingPt: number;
  firstLineIndentMode: FirstLineIndentMode;
  firstLineIndentChars: number;
  firstLineIndentCm: number;
  orderedListIndentMode: ListIndentMode;
  orderedListLeftIndentChars: number;
  orderedListHangingIndentChars: number;
  unorderedListIndentMode: ListIndentMode;
  unorderedListLeftIndentChars: number;
  unorderedListHangingIndentChars: number;
  paragraphSpacingUnit: SpacingUnit;
  paragraphSpacingBefore: number;
  paragraphSpacingAfter: number;
  marginTopCm: number;
  marginBottomCm: number;
  marginLeftCm: number;
  marginRightCm: number;
  titleCentered: boolean;
  headingFirstLineIndent: boolean;
  paragraphRules: ParagraphRule[];
};

export type SelectOption = { value: string; label: string };

export type LineSpacingMode = "multiple" | "exact";
export type FirstLineIndentMode = "chars" | "cm";
export type ListIndentMode = "body" | "none" | "hanging";
export type BlankLineHeightMode = "target" | "body" | "exact";
export type SpacingUnit = "line" | "pt" | "cm" | "mm";
export type ParagraphRuleTargetType = "index" | "first" | "second" | "third" | "last" | "secondLast";

/**
 * 逐段个性化规则。空字符串字段表示继承全局样式，仅显式设置的字段参与覆盖。
 * fontSize 为 "0"/0 表示继承；spacingUnit 为 "" 表示不覆盖段前段后间距。
 */
export type ParagraphRule = {
  id: string;
  targetType: ParagraphRuleTargetType;
  targetIndex: number;
  alignment: string;
  firstLineIndentMode: "" | "none" | "chars" | "cm";
  firstLineIndentChars: number;
  firstLineIndentCm: number;
  chineseFont: string;
  latinFont: string;
  numberFont: string;
  fontSize: string | number;
  spacingUnit: "" | SpacingUnit;
  spacingBefore: number;
  spacingAfter: number;
  blankLinesBefore: number;
  blankLinesAfter: number;
  blankLineHeightMode: BlankLineHeightMode;
  blankLineHeightPt: number;
};


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

// 数字在 Word 中既可以使用 Times New Roman 等西文字体，也常按公文规范使用宋体、黑体等中文字体。
export const NUMBER_FONT_OPTIONS: SelectOption[] = [
  ...CHINESE_FONT_OPTIONS,
  ...LATIN_FONT_OPTIONS,
];

export const INHERITABLE_NUMBER_FONT_OPTIONS: SelectOption[] = [
  INHERIT_FONT_OPTION,
  ...NUMBER_FONT_OPTIONS,
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

export const TABLE_HEADER_BOLD_OPTIONS: SelectOption[] = [
  { value: "false", label: "不加粗（默认）" },
  { value: "true", label: "加粗" },
];

export const TABLE_BORDER_OPTIONS: SelectOption[] = [
  { value: "true", label: "有框线（全边框）" },
  { value: "false", label: "无框线" },
];

export const TABLE_BORDER_WIDTH_OPTIONS: SelectOption[] = [0.25, 0.5, 0.75, 1, 1.5, 2, 2.25, 3, 4, 5, 6]
  .map((pt) => ({ value: String(pt), label: `${pt} 磅${pt === 0.5 ? "（默认）" : ""}` }));

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

export const LIST_INDENT_MODE_OPTIONS: SelectOption[] = [
  { value: "body", label: "继承正文首行缩进（默认）" },
  { value: "none", label: "无特殊缩进" },
  { value: "hanging", label: "悬挂缩进" },
];

export const LIST_INDENT_CHARS_OPTIONS: SelectOption[] = [0, 0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10, 12]
  .map((value) => ({ value: String(value), label: `${value} 字符` }));

export const BLANK_LINE_HEIGHT_MODE_OPTIONS: SelectOption[] = [
  { value: "target", label: "继承目标段落（默认）" },
  { value: "body", label: "继承正文" },
  { value: "exact", label: "固定磅值" },
];

export const BLANK_LINE_HEIGHT_PT_OPTIONS: SelectOption[] = [6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 48, 72]
  .map((value) => ({ value: String(value), label: `${value} pt` }));

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

export const HEADING_INDENT_OPTIONS: SelectOption[] = [
  { value: "false", label: "不应用" },
  { value: "true", label: "应用" },
];

export const TITLE_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "false", label: "不单独设置（默认）" },
  { value: "true", label: "首行居中" },
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

export const BODY_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "left", label: "左对齐（默认）" },
  { value: "center", label: "居中对齐" },
  { value: "right", label: "右对齐" },
  { value: "both", label: "两端对齐" },
];

export const TABLE_CELL_VERTICAL_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "top", label: "顶端对齐" },
  { value: "center", label: "居中（默认）" },
  { value: "bottom", label: "底端对齐" },
];

export const TABLE_CELL_PADDING_OPTIONS: SelectOption[] = [0, 0.5, 1, 1.5, 2, 3, 4]
  .map((pt) => ({ value: String(pt), label: pt === 0 ? "无（顶住框线）" : `${pt} 磅${pt === 1.5 ? "（默认）" : ""}` }));

export const HEADING_BOLD_OPTIONS: SelectOption[] = [
  { value: "true", label: "加粗（默认）" },
  { value: "false", label: "不加粗" },
];

export const SPACING_UNIT_OPTIONS: SelectOption[] = [
  { value: "line", label: "行" },
  { value: "pt", label: "磅" },
  { value: "cm", label: "厘米" },
  { value: "mm", label: "毫米" },
];

export const RULE_SPACING_UNIT_OPTIONS: SelectOption[] = [
  { value: "", label: "继承" },
  ...SPACING_UNIT_OPTIONS,
];

/** 段前段后取值选项随单位变化，避免出现单位与数值不匹配的组合。 */
export function spacingValueOptions(unit: SpacingUnit): SelectOption[] {
  switch (unit) {
    case "line":
      return [0, 0.5, 1, 1.5, 2, 2.5, 3].map((value) => ({ value: String(value), label: `${value} 行` }));
    case "cm":
      return [0, 0.25, 0.5, 0.75, 1, 1.5, 2].map((value) => ({ value: String(value), label: `${value} cm` }));
    case "mm":
      return [0, 2.5, 5, 7.5, 10, 15, 20].map((value) => ({ value: String(value), label: `${value} mm` }));
    default:
      return [0, 3, 6, 9, 12, 18, 24].map((value) => ({ value: String(value), label: `${value} pt` }));
  }
}

export function readSpacingUnit(value: unknown): SpacingUnit {
  const text = typeof value === "string" ? value.toLowerCase() : "";
  return text === "line" || text === "cm" || text === "mm" ? text : "pt";
}

export const PARAGRAPH_RULE_TARGET_OPTIONS: SelectOption[] = [
  { value: "index", label: "指定段号（第 N 段）" },
  { value: "first", label: "第一段" },
  { value: "second", label: "第二段" },
  { value: "third", label: "第三段" },
  { value: "secondLast", label: "倒数第二段" },
  { value: "last", label: "最后一段" },
];

export const RULE_ALIGNMENT_OPTIONS: SelectOption[] = [
  { value: "", label: "继承" },
  ...BODY_ALIGNMENT_OPTIONS.map((option) => ({
    value: option.value,
    label: option.label.replace("（默认）", ""),
  })),
];

export const RULE_INDENT_MODE_OPTIONS: SelectOption[] = [
  { value: "", label: "继承" },
  { value: "none", label: "无缩进" },
  { value: "chars", label: "按字符" },
  { value: "cm", label: "按厘米" },
];

export const RULE_FONT_SIZE_OPTIONS: SelectOption[] = [
  { value: "0", label: "继承" },
  ...FONT_SIZE_OPTIONS,
];

export const BLANK_LINE_OPTIONS: SelectOption[] = [0, 1, 2, 3, 4, 5, 6]
  .map((value) => ({ value: String(value), label: value === 0 ? "无" : `${value} 个空行` }));

export function stringifyRuleFontSizeValue(value: string | number): string {
  const text = String(value ?? "").trim();
  if (!text || text === "0") {
    return "0";
  }
  return stringifyFontSizeValue(value);
}

let paragraphRuleSeed = 0;

export function createDefaultParagraphRule(): ParagraphRule {
  paragraphRuleSeed += 1;
  const id = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `rule-${Date.now()}-${paragraphRuleSeed}`;
  return {
    id,
    targetType: "index",
    targetIndex: 1,
    alignment: "",
    firstLineIndentMode: "",
    firstLineIndentChars: 2,
    firstLineIndentCm: 0.75,
    chineseFont: "",
    latinFont: "",
    numberFont: "",
    fontSize: "0",
    spacingUnit: "",
    spacingBefore: 0,
    spacingAfter: 0,
    blankLinesBefore: 0,
    blankLinesAfter: 0,
    blankLineHeightMode: "target",
    blankLineHeightPt: 18,
  };
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
