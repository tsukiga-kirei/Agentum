import React, { useEffect, useMemo, useState } from "react";
import { Select } from "antd";
import type { LucideIcon } from "lucide-react";
import { AlignCenter, ChevronDown, FileText, Hash, LayoutList, LayoutTemplate, ListOrdered, Plus, Table2, Trash2, Type } from "lucide-react";
import {
  BODY_ALIGNMENT_OPTIONS,
  BLANK_LINE_HEIGHT_MODE_OPTIONS,
  BLANK_LINE_HEIGHT_PT_OPTIONS,
  BLANK_LINE_OPTIONS,
  CHINESE_FONT_OPTIONS,
  createDefaultParagraphRule,
  detectMarginPreset,
  FONT_SIZE_OPTIONS,
  HEADING_BOLD_OPTIONS,
  HEADING_INDENT_OPTIONS,
  INHERITABLE_CHINESE_FONT_OPTIONS,
  INHERITABLE_LATIN_FONT_OPTIONS,
  INHERITABLE_NUMBER_FONT_OPTIONS,
  LATIN_FONT_OPTIONS,
  LIST_INDENT_CHARS_OPTIONS,
  LIST_INDENT_MODE_OPTIONS,
  NUMBER_FONT_OPTIONS,
  MARGIN_CM_OPTIONS,
  MARGIN_PRESETS,
  MARGIN_PRESET_OPTIONS,
  type DocumentDeliveryStyleValues,
  type MarginPresetKey,
  type ParagraphRule,
  type SpacingUnit,
  PARAGRAPH_RULE_TARGET_OPTIONS,
  RULE_ALIGNMENT_OPTIONS,
  RULE_FONT_SIZE_OPTIONS,
  RULE_INDENT_MODE_OPTIONS,
  RULE_SPACING_UNIT_OPTIONS,
  readSpacingUnit,
  spacingValueOptions,
  SPACING_UNIT_OPTIONS,
  stringifyFontSizeValue,
  stringifyRuleFontSizeValue,
  stringifySelectValue,
  stringifyTableFontSizeValue,
  TABLE_BORDER_OPTIONS,
  TABLE_BORDER_WIDTH_OPTIONS,
  TABLE_CELL_ALIGNMENT_OPTIONS,
  TABLE_CELL_PADDING_OPTIONS,
  TABLE_CELL_VERTICAL_ALIGNMENT_OPTIONS,
  TABLE_FONT_SIZE_OPTIONS,
  TABLE_HEADER_BOLD_OPTIONS,
  TITLE_ALIGNMENT_OPTIONS,
} from "../../constants/documentDeliveryStyleOptions";
import { LineSpacingStyleFields } from "./LineSpacingStyleFields";
import { FirstLineIndentStyleFields } from "./FirstLineIndentStyleFields";

const workflowSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const workflowSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

type FieldChangeHandler = <K extends keyof DocumentDeliveryStyleValues>(
  key: K,
  value: DocumentDeliveryStyleValues[K],
) => void;

type DocumentDeliveryStyleSectionsProps = {
  style: DocumentDeliveryStyleValues;
  onFieldChange: FieldChangeHandler;
  onFieldsChange: (updates: Partial<DocumentDeliveryStyleValues>) => void;
};

type ColumnCount = 1 | 2 | 3 | 4 | 5;

function StyleSection({
  title,
  description,
  defaultOpen = false,
  children,
}: {
  title: string;
  description?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-hover)]">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="flex w-full items-center justify-between gap-3 p-4 text-left"
      >
        <div>
          <h5 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h5>
          {description ? <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{description}</p> : null}
        </div>
        <ChevronDown
          className={`h-4 w-4 shrink-0 text-[var(--color-text-tertiary)] transition-transform ${open ? "rotate-180" : ""}`}
          aria-hidden="true"
        />
      </button>
      {open ? <div className="space-y-3 px-4 pb-4">{children}</div> : null}
    </section>
  );
}

function StyleRow({ children, columns = 2 }: { children: React.ReactNode; columns?: ColumnCount }) {
  const className = columns === 5
    ? "grid gap-4 sm:grid-cols-2 lg:grid-cols-5"
    : columns === 4
      ? "grid gap-4 sm:grid-cols-2 lg:grid-cols-4"
    : columns === 3
      ? "grid gap-4 sm:grid-cols-3"
      : columns === 1
        ? "grid gap-4"
        : "grid gap-4 lg:grid-cols-2";
  return <div className={className}>{children}</div>;
}

function StyleSelectField({
  label,
  icon: Icon,
  value,
  options,
  onChange,
}: {
  label: string;
  icon?: LucideIcon;
  value: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  const normalizedOptions = useMemo(() => {
    if (value && !options.some((option) => option.value === value)) {
      return [{ value, label: value }, ...options];
    }
    return options;
  }, [options, value]);

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={Icon ? <Icon className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden /> : undefined}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={value}
        options={normalizedOptions}
        onChange={onChange}
      />
    </label>
  );
}

function StyleNumberField({
  label,
  value,
  min = 1,
  onChange,
}: {
  label: string;
  value: number;
  min?: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <div className="sys-field-input-wrap">
        <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
        <input
          type="number"
          min={min}
          value={Number.isFinite(value) ? value : min}
          onChange={(event) => {
            const parsed = Number(event.target.value);
            onChange(Number.isFinite(parsed) ? Math.max(min, Math.round(parsed)) : min);
          }}
          className="sys-field-input"
        />
      </div>
    </label>
  );
}

const HEADING_LEVELS: Array<{ level: 1 | 2 | 3 | 4 | 5; label: string; inherit: boolean }> = [
  { level: 1, label: "一级标题", inherit: false },
  { level: 2, label: "二级标题", inherit: false },
  { level: 3, label: "三级标题", inherit: false },
  { level: 4, label: "四级标题（默认继承三级）", inherit: true },
  { level: 5, label: "五级标题（默认继承三级）", inherit: true },
];

function ParagraphRuleCard({
  rule,
  index,
  onChange,
  onRemove,
}: {
  rule: ParagraphRule;
  index: number;
  onChange: (patch: Partial<ParagraphRule>) => void;
  onRemove: () => void;
}) {
  const spacingUnit: SpacingUnit = readSpacingUnit(rule.spacingUnit || "pt");
  return (
    <div className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-surface)] p-3">
      <div className="mb-3 flex items-center justify-between">
        <span className="text-xs font-semibold text-[var(--color-text-primary)]">个性化规则 {index + 1}</span>
        <button
          type="button"
          onClick={onRemove}
          className="inline-flex items-center gap-1 text-xs text-[var(--color-text-tertiary)] hover:text-[var(--color-danger)]"
        >
          <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
          删除
        </button>
      </div>
      <div className="space-y-3">
        <StyleRow columns={rule.targetType === "index" ? 2 : 1}>
          <StyleSelectField
            label="作用段落"
            icon={FileText}
            value={rule.targetType}
            options={PARAGRAPH_RULE_TARGET_OPTIONS}
            onChange={(value) => onChange({ targetType: value as ParagraphRule["targetType"] })}
          />
          {rule.targetType === "index" ? (
            <StyleNumberField
              label="第几段"
              value={rule.targetIndex}
              onChange={(value) => onChange({ targetIndex: value })}
            />
          ) : null}
        </StyleRow>
        <StyleRow columns={2}>
          <StyleSelectField
            label="对齐方式"
            icon={AlignCenter}
            value={rule.alignment}
            options={RULE_ALIGNMENT_OPTIONS}
            onChange={(value) => onChange({ alignment: value })}
          />
          <StyleSelectField
            label="字号"
            icon={Hash}
            value={stringifyRuleFontSizeValue(rule.fontSize)}
            options={RULE_FONT_SIZE_OPTIONS}
            onChange={(value) => onChange({ fontSize: value === "0" ? 0 : value })}
          />
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField
            label="中文字体"
            icon={Type}
            value={rule.chineseFont}
            options={INHERITABLE_CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange({ chineseFont: value })}
          />
          <StyleSelectField
            label="西文字体"
            icon={Type}
            value={rule.latinFont}
            options={INHERITABLE_LATIN_FONT_OPTIONS}
            onChange={(value) => onChange({ latinFont: value })}
          />
          <StyleSelectField
            label="数字字体"
            icon={Hash}
            value={rule.numberFont}
            options={INHERITABLE_NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange({ numberFont: value })}
          />
        </StyleRow>
        <StyleRow columns={rule.firstLineIndentMode === "chars" || rule.firstLineIndentMode === "cm" ? 2 : 1}>
          <StyleSelectField
            label="首行缩进"
            icon={FileText}
            value={rule.firstLineIndentMode}
            options={RULE_INDENT_MODE_OPTIONS}
            onChange={(value) => onChange({ firstLineIndentMode: value as ParagraphRule["firstLineIndentMode"] })}
          />
          {rule.firstLineIndentMode === "chars" ? (
            <StyleSelectField
              label="缩进字符"
              icon={FileText}
              value={stringifySelectValue(rule.firstLineIndentChars)}
              options={[1, 1.5, 2, 2.5, 3, 4].map((value) => ({ value: String(value), label: `${value} 字符` }))}
              onChange={(value) => onChange({ firstLineIndentChars: Number(value) })}
            />
          ) : null}
          {rule.firstLineIndentMode === "cm" ? (
            <StyleSelectField
              label="缩进厘米"
              icon={FileText}
              value={stringifySelectValue(rule.firstLineIndentCm)}
              options={[0.25, 0.5, 0.75, 1, 1.5, 2].map((value) => ({ value: String(value), label: `${value} cm` }))}
              onChange={(value) => onChange({ firstLineIndentCm: Number(value) })}
            />
          ) : null}
        </StyleRow>
        <StyleRow columns={rule.spacingUnit ? 3 : 1}>
          <StyleSelectField
            label="段距单位"
            icon={FileText}
            value={rule.spacingUnit}
            options={RULE_SPACING_UNIT_OPTIONS}
            onChange={(value) => onChange({ spacingUnit: value as ParagraphRule["spacingUnit"] })}
          />
          {rule.spacingUnit ? (
            <>
              <StyleSelectField
                label="段前"
                icon={FileText}
                value={stringifySelectValue(rule.spacingBefore)}
                options={spacingValueOptions(spacingUnit)}
                onChange={(value) => onChange({ spacingBefore: Number(value) })}
              />
              <StyleSelectField
                label="段后"
                icon={FileText}
                value={stringifySelectValue(rule.spacingAfter)}
                options={spacingValueOptions(spacingUnit)}
                onChange={(value) => onChange({ spacingAfter: Number(value) })}
              />
            </>
          ) : null}
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField
            label="段前空行"
            icon={FileText}
            value={stringifySelectValue(rule.blankLinesBefore)}
            options={BLANK_LINE_OPTIONS}
            onChange={(value) => onChange({ blankLinesBefore: Number(value) })}
          />
          <StyleSelectField
            label="段后空行"
            icon={FileText}
            value={stringifySelectValue(rule.blankLinesAfter)}
            options={BLANK_LINE_OPTIONS}
            onChange={(value) => onChange({ blankLinesAfter: Number(value) })}
          />
          <StyleSelectField
            label="空行高度"
            icon={FileText}
            value={rule.blankLineHeightMode}
            options={BLANK_LINE_HEIGHT_MODE_OPTIONS}
            onChange={(value) => onChange({ blankLineHeightMode: value as ParagraphRule["blankLineHeightMode"] })}
          />
        </StyleRow>
        {rule.blankLineHeightMode === "exact" ? (
          <StyleRow columns={1}>
            <StyleSelectField
              label="空行固定高度"
              icon={FileText}
              value={stringifySelectValue(rule.blankLineHeightPt)}
              options={BLANK_LINE_HEIGHT_PT_OPTIONS}
              onChange={(value) => onChange({ blankLineHeightPt: Number(value) })}
            />
          </StyleRow>
        ) : null}
      </div>
    </div>
  );
}

export function DocumentDeliveryStyleSections({ style, onFieldChange, onFieldsChange }: DocumentDeliveryStyleSectionsProps) {
  const [marginPreset, setMarginPreset] = useState<MarginPresetKey>(() => detectMarginPreset(style));

  useEffect(() => {
    setMarginPreset(detectMarginPreset(style));
  }, [style.marginTopCm, style.marginBottomCm, style.marginLeftCm, style.marginRightCm]);

  const effectiveMarginPreset = marginPreset === "custom" ? "custom" : detectMarginPreset(style);
  const paragraphSpacingUnit: SpacingUnit = readSpacingUnit(style.paragraphSpacingUnit);
  const spacingOptions = useMemo(() => spacingValueOptions(paragraphSpacingUnit), [paragraphSpacingUnit]);
  const rules = style.paragraphRules ?? [];

  function handleMarginPresetChange(value: string) {
    const preset = value as MarginPresetKey;
    setMarginPreset(preset);
    if (preset === "custom") {
      return;
    }
    onFieldsChange(MARGIN_PRESETS[preset]);
  }

  function handleCustomMarginChange(
    key: "marginTopCm" | "marginBottomCm" | "marginLeftCm" | "marginRightCm",
    value: string,
  ) {
    setMarginPreset("custom");
    onFieldChange(key, Number(value));
  }

  function updateRule(index: number, patch: Partial<ParagraphRule>) {
    onFieldChange("paragraphRules", rules.map((rule, i) => (i === index ? { ...rule, ...patch } : rule)));
  }

  function addRule() {
    onFieldChange("paragraphRules", [...rules, createDefaultParagraphRule()]);
  }

  function removeRule(index: number) {
    onFieldChange("paragraphRules", rules.filter((_, i) => i !== index));
  }

  const headingFontKeys = {
    1: { chinese: "heading1ChineseFont", latin: "heading1LatinFont", number: "heading1NumberFont", bold: "heading1Bold", alignment: "heading1Alignment" },
    2: { chinese: "heading2ChineseFont", latin: "heading2LatinFont", number: "heading2NumberFont", bold: "heading2Bold", alignment: "heading2Alignment" },
    3: { chinese: "heading3ChineseFont", latin: "heading3LatinFont", number: "heading3NumberFont", bold: "heading3Bold", alignment: "heading3Alignment" },
    4: { chinese: "heading4ChineseFont", latin: "heading4LatinFont", number: "heading4NumberFont", bold: "heading4Bold", alignment: "heading4Alignment" },
    5: { chinese: "heading5ChineseFont", latin: "heading5LatinFont", number: "heading5NumberFont", bold: "heading5Bold", alignment: "heading5Alignment" },
  } as const;

  return (
    <div className="space-y-3">
      <StyleSection title="字体" description="正文的中文、西文与数字默认字体。" defaultOpen>
        <StyleRow columns={3}>
          <StyleSelectField label="中文字体" icon={Type} value={style.chineseFont} options={CHINESE_FONT_OPTIONS} onChange={(value) => onFieldChange("chineseFont", value)} />
          <StyleSelectField label="西文字体" icon={Type} value={style.latinFont} options={LATIN_FONT_OPTIONS} onChange={(value) => onFieldChange("latinFont", value)} />
          <StyleSelectField label="数字字体" icon={Hash} value={style.numberFont} options={NUMBER_FONT_OPTIONS} onChange={(value) => onFieldChange("numberFont", value)} />
        </StyleRow>
      </StyleSection>

      <StyleSection title="字号" description="正文与一至五级标题字号，四五级默认继承三级。">
        <StyleRow columns={2}>
          <StyleSelectField label="正文字号" icon={Hash} value={stringifyFontSizeValue(style.bodyFontSize)} options={FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("bodyFontSize", value)} />
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField label="一级标题字号" icon={Hash} value={stringifyFontSizeValue(style.heading1FontSize)} options={FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("heading1FontSize", value)} />
          <StyleSelectField label="二级标题字号" icon={Hash} value={stringifyFontSizeValue(style.heading2FontSize)} options={FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("heading2FontSize", value)} />
          <StyleSelectField label="三级标题字号" icon={Hash} value={stringifyFontSizeValue(style.heading3FontSize)} options={FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("heading3FontSize", value)} />
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField label="四级标题字号" icon={Hash} value={stringifyRuleFontSizeValue(style.heading4FontSize)} options={RULE_FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("heading4FontSize", value === "0" ? 0 : value)} />
          <StyleSelectField label="五级标题字号" icon={Hash} value={stringifyRuleFontSizeValue(style.heading5FontSize)} options={RULE_FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("heading5FontSize", value === "0" ? 0 : value)} />
        </StyleRow>
      </StyleSection>

      <StyleSection title="标题样式" description="标题对齐、首行缩进，以及各级标题字体与是否加粗；四五级字体留空继承三级。">
        <StyleRow columns={2}>
          <StyleSelectField label="首行标题对齐" icon={AlignCenter} value={String(Boolean(style.titleCentered))} options={TITLE_ALIGNMENT_OPTIONS} onChange={(value) => onFieldChange("titleCentered", value === "true")} />
          <StyleSelectField label="标题首行缩进" icon={FileText} value={String(Boolean(style.headingFirstLineIndent))} options={HEADING_INDENT_OPTIONS} onChange={(value) => onFieldChange("headingFirstLineIndent", value === "true")} />
        </StyleRow>
        {HEADING_LEVELS.map(({ level, label }) => {
          const keys = headingFontKeys[level];
          return (
            <div key={level} className="space-y-2">
              <span className="text-xs font-medium text-[var(--color-text-secondary)]">{label}</span>
              <StyleRow columns={5}>
                <StyleSelectField label="中文字体" icon={Type} value={style[keys.chinese]} options={INHERITABLE_CHINESE_FONT_OPTIONS} onChange={(value) => onFieldChange(keys.chinese, value)} />
                <StyleSelectField label="西文字体" icon={Type} value={style[keys.latin]} options={INHERITABLE_LATIN_FONT_OPTIONS} onChange={(value) => onFieldChange(keys.latin, value)} />
                <StyleSelectField label="数字字体" icon={Hash} value={style[keys.number]} options={INHERITABLE_NUMBER_FONT_OPTIONS} onChange={(value) => onFieldChange(keys.number, value)} />
                <StyleSelectField label="是否加粗" icon={Type} value={String(Boolean(style[keys.bold]))} options={HEADING_BOLD_OPTIONS} onChange={(value) => onFieldChange(keys.bold, value === "true")} />
                <StyleSelectField label="对齐方式" icon={AlignCenter} value={style[keys.alignment]} options={BODY_ALIGNMENT_OPTIONS} onChange={(value) => onFieldChange(keys.alignment, value)} />
              </StyleRow>
            </div>
          );
        })}
      </StyleSection>

      <StyleSection title="段落" description="正文对齐、行距、首行缩进与段前段后间距。">
        <StyleRow columns={2}>
          <StyleSelectField label="正文对齐" icon={AlignCenter} value={style.bodyAlignment} options={BODY_ALIGNMENT_OPTIONS} onChange={(value) => onFieldChange("bodyAlignment", value)} />
        </StyleRow>
        <StyleRow columns={2}>
          <LineSpacingStyleFields
            mode={style.lineSpacingMode}
            multiple={style.lineSpacing}
            pt={style.lineSpacingPt}
            onModeChange={(value) => onFieldChange("lineSpacingMode", value)}
            onMultipleChange={(value) => onFieldChange("lineSpacing", value)}
            onPtChange={(value) => onFieldChange("lineSpacingPt", value)}
            SelectField={(props) => (
              <StyleSelectField label={props.label} icon={props.icon} value={props.value ?? props.defaultValue ?? ""} options={props.options} onChange={props.onChange} />
            )}
          />
        </StyleRow>
        <StyleRow columns={2}>
          <FirstLineIndentStyleFields
            mode={style.firstLineIndentMode}
            chars={style.firstLineIndentChars}
            cm={style.firstLineIndentCm}
            onModeChange={(value) => onFieldChange("firstLineIndentMode", value)}
            onCharsChange={(value) => onFieldChange("firstLineIndentChars", value)}
            onCmChange={(value) => onFieldChange("firstLineIndentCm", value)}
            SelectField={(props) => (
              <StyleSelectField label={props.label} icon={props.icon} value={props.value ?? props.defaultValue ?? ""} options={props.options} onChange={props.onChange} />
            )}
          />
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField label="段距单位" icon={FileText} value={paragraphSpacingUnit} options={SPACING_UNIT_OPTIONS} onChange={(value) => onFieldChange("paragraphSpacingUnit", value as SpacingUnit)} />
          <StyleSelectField label="段前间距" icon={FileText} value={stringifySelectValue(style.paragraphSpacingBefore)} options={spacingOptions} onChange={(value) => onFieldChange("paragraphSpacingBefore", Number(value))} />
          <StyleSelectField label="段后间距" icon={FileText} value={stringifySelectValue(style.paragraphSpacingAfter)} options={spacingOptions} onChange={(value) => onFieldChange("paragraphSpacingAfter", Number(value))} />
        </StyleRow>
      </StyleSection>

      <StyleSection title="列表" description="分别设置有序与无序列表的缩进；选择悬挂缩进后可配置续行位置。">
        <div className="space-y-2">
          <span className="text-xs font-medium text-[var(--color-text-secondary)]">有序列表</span>
          <StyleRow columns={style.orderedListIndentMode === "hanging" ? 3 : 1}>
            <StyleSelectField label="缩进方式" icon={ListOrdered} value={style.orderedListIndentMode} options={LIST_INDENT_MODE_OPTIONS} onChange={(value) => onFieldChange("orderedListIndentMode", value as DocumentDeliveryStyleValues["orderedListIndentMode"])} />
            {style.orderedListIndentMode === "hanging" ? (
              <>
                <StyleSelectField label="左缩进" icon={ListOrdered} value={stringifySelectValue(style.orderedListLeftIndentChars)} options={LIST_INDENT_CHARS_OPTIONS} onChange={(value) => onFieldChange("orderedListLeftIndentChars", Number(value))} />
                <StyleSelectField label="悬挂缩进" icon={ListOrdered} value={stringifySelectValue(style.orderedListHangingIndentChars)} options={LIST_INDENT_CHARS_OPTIONS} onChange={(value) => onFieldChange("orderedListHangingIndentChars", Number(value))} />
              </>
            ) : null}
          </StyleRow>
        </div>
        <div className="space-y-2">
          <span className="text-xs font-medium text-[var(--color-text-secondary)]">无序列表</span>
          <StyleRow columns={style.unorderedListIndentMode === "hanging" ? 3 : 1}>
            <StyleSelectField label="缩进方式" icon={LayoutList} value={style.unorderedListIndentMode} options={LIST_INDENT_MODE_OPTIONS} onChange={(value) => onFieldChange("unorderedListIndentMode", value as DocumentDeliveryStyleValues["unorderedListIndentMode"])} />
            {style.unorderedListIndentMode === "hanging" ? (
              <>
                <StyleSelectField label="左缩进" icon={LayoutList} value={stringifySelectValue(style.unorderedListLeftIndentChars)} options={LIST_INDENT_CHARS_OPTIONS} onChange={(value) => onFieldChange("unorderedListLeftIndentChars", Number(value))} />
                <StyleSelectField label="悬挂缩进" icon={LayoutList} value={stringifySelectValue(style.unorderedListHangingIndentChars)} options={LIST_INDENT_CHARS_OPTIONS} onChange={(value) => onFieldChange("unorderedListHangingIndentChars", Number(value))} />
              </>
            ) : null}
          </StyleRow>
        </div>
      </StyleSection>

      <StyleSection title="表格" description="表格文字、对齐、单元格内边距、框线与行距。">
        <StyleRow columns={3}>
          <StyleSelectField label="表格中文字体" icon={Table2} value={style.tableChineseFont} options={INHERITABLE_CHINESE_FONT_OPTIONS} onChange={(value) => onFieldChange("tableChineseFont", value)} />
          <StyleSelectField label="表格西文字体" icon={Table2} value={style.tableLatinFont} options={INHERITABLE_LATIN_FONT_OPTIONS} onChange={(value) => onFieldChange("tableLatinFont", value)} />
          <StyleSelectField label="表格数字字体" icon={Hash} value={style.tableNumberFont} options={INHERITABLE_NUMBER_FONT_OPTIONS} onChange={(value) => onFieldChange("tableNumberFont", value)} />
        </StyleRow>
        <StyleRow columns={3}>
          <StyleSelectField label="表格字号" icon={Hash} value={stringifyTableFontSizeValue(style.tableFontSize)} options={TABLE_FONT_SIZE_OPTIONS} onChange={(value) => onFieldChange("tableFontSize", value === "0" ? 0 : value)} />
          <StyleSelectField label="水平对齐" icon={AlignCenter} value={style.tableCellAlignment} options={TABLE_CELL_ALIGNMENT_OPTIONS} onChange={(value) => onFieldChange("tableCellAlignment", value)} />
          <StyleSelectField label="垂直对齐" icon={AlignCenter} value={style.tableCellVerticalAlignment} options={TABLE_CELL_VERTICAL_ALIGNMENT_OPTIONS} onChange={(value) => onFieldChange("tableCellVerticalAlignment", value)} />
        </StyleRow>
        <StyleRow columns={2}>
          <StyleSelectField label="上下内边距" icon={Table2} value={stringifySelectValue(style.tableCellPaddingVerticalPt ?? 1.5)} options={TABLE_CELL_PADDING_OPTIONS} onChange={(value) => onFieldChange("tableCellPaddingVerticalPt", Number(value))} />
          <StyleSelectField label="首行加粗" icon={Table2} value={String(style.tableHeaderBold)} options={TABLE_HEADER_BOLD_OPTIONS} onChange={(value) => onFieldChange("tableHeaderBold", value === "true")} />
        </StyleRow>
        <StyleRow columns={2}>
          <StyleSelectField label="表格框线" icon={Table2} value={String(style.tableBorders)} options={TABLE_BORDER_OPTIONS} onChange={(value) => onFieldChange("tableBorders", value === "true")} />
          {style.tableBorders ? (
            <StyleSelectField label="框线磅数" icon={Table2} value={String(style.tableBorderWidthPt)} options={TABLE_BORDER_WIDTH_OPTIONS} onChange={(value) => onFieldChange("tableBorderWidthPt", Number(value))} />
          ) : null}
        </StyleRow>
        <StyleRow columns={2}>
          <LineSpacingStyleFields
            mode={style.tableLineSpacingMode}
            multiple={style.tableLineSpacing}
            pt={style.tableLineSpacingPt}
            labelPrefix="表格"
            onModeChange={(value) => onFieldChange("tableLineSpacingMode", value)}
            onMultipleChange={(value) => onFieldChange("tableLineSpacing", value)}
            onPtChange={(value) => onFieldChange("tableLineSpacingPt", value)}
            SelectField={(props) => (
              <StyleSelectField label={props.label} icon={props.icon} value={props.value ?? props.defaultValue ?? ""} options={props.options} onChange={props.onChange} />
            )}
          />
        </StyleRow>
      </StyleSection>

      <StyleSection title="页边距" description="先选常用预设，再按需微调四边边距。">
        <StyleRow columns={1}>
          <StyleSelectField label="页边距预设" icon={LayoutTemplate} value={effectiveMarginPreset} options={MARGIN_PRESET_OPTIONS} onChange={handleMarginPresetChange} />
        </StyleRow>
        <StyleRow columns={2}>
          <StyleSelectField label="上边距" icon={LayoutTemplate} value={stringifySelectValue(style.marginTopCm)} options={MARGIN_CM_OPTIONS} onChange={(value) => handleCustomMarginChange("marginTopCm", value)} />
          <StyleSelectField label="下边距" icon={LayoutTemplate} value={stringifySelectValue(style.marginBottomCm)} options={MARGIN_CM_OPTIONS} onChange={(value) => handleCustomMarginChange("marginBottomCm", value)} />
          <StyleSelectField label="左边距" icon={LayoutTemplate} value={stringifySelectValue(style.marginLeftCm)} options={MARGIN_CM_OPTIONS} onChange={(value) => handleCustomMarginChange("marginLeftCm", value)} />
          <StyleSelectField label="右边距" icon={LayoutTemplate} value={stringifySelectValue(style.marginRightCm)} options={MARGIN_CM_OPTIONS} onChange={(value) => handleCustomMarginChange("marginRightCm", value)} />
        </StyleRow>
      </StyleSection>

      <StyleSection title="个性化段落" description="针对指定段落（非表格）单独设置对齐、缩进、字体、字号、间距与空行；优先级高于上方全局样式。">
        {rules.length === 0 ? (
          <p className="text-xs text-[var(--color-text-tertiary)]">暂无个性化规则。可针对第一段、最后一段或指定段号单独排版。</p>
        ) : (
          <div className="space-y-3">
            {rules.map((rule, index) => (
              <ParagraphRuleCard
                key={rule.id}
                rule={rule}
                index={index}
                onChange={(patch) => updateRule(index, patch)}
                onRemove={() => removeRule(index)}
              />
            ))}
          </div>
        )}
        <button
          type="button"
          onClick={addRule}
          className="inline-flex items-center gap-1 rounded-md border border-dashed border-[var(--color-border-light)] px-3 py-2 text-xs text-[var(--color-text-secondary)] hover:border-[var(--color-primary)] hover:text-[var(--color-primary)]"
        >
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          新增个性化规则
        </button>
      </StyleSection>
    </div>
  );
}
