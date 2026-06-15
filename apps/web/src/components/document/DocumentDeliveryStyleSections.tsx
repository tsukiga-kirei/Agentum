import React, { useEffect, useMemo, useState } from "react";
import { Select } from "antd";
import type { LucideIcon } from "lucide-react";
import { AlignCenter, ChevronDown, FileText, Hash, LayoutTemplate, Table2, Type } from "lucide-react";
import {
  CHINESE_FONT_OPTIONS,
  detectMarginPreset,
  FIRST_LINE_INDENT_OPTIONS,
  FONT_SIZE_OPTIONS,
  HEADING_INDENT_OPTIONS,
  INHERITABLE_CHINESE_FONT_OPTIONS,
  INHERITABLE_LATIN_FONT_OPTIONS,
  LATIN_FONT_OPTIONS,
  LINE_SPACING_OPTIONS,
  MARGIN_CM_OPTIONS,
  MARGIN_PRESETS,
  MARGIN_PRESET_OPTIONS,
  type DocumentDeliveryStyleValues,
  type MarginPresetKey,
  PARAGRAPH_SPACING_AFTER_OPTIONS,
  PARAGRAPH_SPACING_BEFORE_OPTIONS,
  stringifyFontSizeValue,
  stringifySelectValue,
  stringifyTableFontSizeValue,
  TABLE_CELL_ALIGNMENT_OPTIONS,
  TABLE_FONT_SIZE_OPTIONS,
  TITLE_ALIGNMENT_OPTIONS,
} from "../../constants/documentDeliveryStyleOptions";

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

function StyleSection({
  title,
  description,
  children,
  layout = "grid",
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
  layout?: "grid" | "stack";
}) {
  return (
    <section className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
      <div className="mb-3">
        <h5 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h5>
        {description ? <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{description}</p> : null}
      </div>
      <div className={layout === "stack" ? "space-y-4" : "grid gap-4 lg:grid-cols-2"}>{children}</div>
    </section>
  );
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

export function DocumentDeliveryStyleSections({ style, onFieldChange, onFieldsChange }: DocumentDeliveryStyleSectionsProps) {
  const [marginPreset, setMarginPreset] = useState<MarginPresetKey>(() => detectMarginPreset(style));

  useEffect(() => {
    setMarginPreset(detectMarginPreset(style));
  }, [style.marginTopCm, style.marginBottomCm, style.marginLeftCm, style.marginRightCm]);

  const effectiveMarginPreset = marginPreset === "custom" ? "custom" : detectMarginPreset(style);

  function handleMarginPresetChange(value: string) {
    const preset = value as MarginPresetKey;
    setMarginPreset(preset);
    if (preset === "custom") {
      return;
    }
    const margins = MARGIN_PRESETS[preset];
    onFieldsChange(margins);
  }

  function handleCustomMarginChange(
    key: "marginTopCm" | "marginBottomCm" | "marginLeftCm" | "marginRightCm",
    value: string,
  ) {
    setMarginPreset("custom");
    onFieldChange(key, Number(value));
  }

  return (
    <div className="space-y-4">
      <StyleSection title="字体" description="设置文档中西文默认字体。">
        <StyleSelectField
          label="中文字体"
          icon={Type}
          value={style.chineseFont}
          options={CHINESE_FONT_OPTIONS}
          onChange={(value) => onFieldChange("chineseFont", value)}
        />
        <StyleSelectField
          label="西文字体"
          icon={Type}
          value={style.latinFont}
          options={LATIN_FONT_OPTIONS}
          onChange={(value) => onFieldChange("latinFont", value)}
        />
      </StyleSection>

      <StyleSection title="字号" description="正文与各级标题字号，支持中文字号名与 pt 数字。">
        <StyleSelectField
          label="正文字号"
          icon={Hash}
          value={stringifyFontSizeValue(style.bodyFontSize)}
          options={FONT_SIZE_OPTIONS}
          onChange={(value) => onFieldChange("bodyFontSize", value)}
        />
        <StyleSelectField
          label="一级标题字号"
          icon={Hash}
          value={stringifyFontSizeValue(style.heading1FontSize)}
          options={FONT_SIZE_OPTIONS}
          onChange={(value) => onFieldChange("heading1FontSize", value)}
        />
        <StyleSelectField
          label="二级标题字号"
          icon={Hash}
          value={stringifyFontSizeValue(style.heading2FontSize)}
          options={FONT_SIZE_OPTIONS}
          onChange={(value) => onFieldChange("heading2FontSize", value)}
        />
        <StyleSelectField
          label="三级标题字号"
          icon={Hash}
          value={stringifyFontSizeValue(style.heading3FontSize)}
          options={FONT_SIZE_OPTIONS}
          onChange={(value) => onFieldChange("heading3FontSize", value)}
        />
      </StyleSection>

      <StyleSection title="标题字体" description="可为各级标题单独设置中西文字体，留空则继承正文。">
        <StyleSelectField
          label="一级标题中文字体"
          icon={Type}
          value={style.heading1ChineseFont}
          options={INHERITABLE_CHINESE_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading1ChineseFont", value)}
        />
        <StyleSelectField
          label="一级标题西文字体"
          icon={Type}
          value={style.heading1LatinFont}
          options={INHERITABLE_LATIN_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading1LatinFont", value)}
        />
        <StyleSelectField
          label="二级标题中文字体"
          icon={Type}
          value={style.heading2ChineseFont}
          options={INHERITABLE_CHINESE_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading2ChineseFont", value)}
        />
        <StyleSelectField
          label="二级标题西文字体"
          icon={Type}
          value={style.heading2LatinFont}
          options={INHERITABLE_LATIN_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading2LatinFont", value)}
        />
        <StyleSelectField
          label="三级标题中文字体"
          icon={Type}
          value={style.heading3ChineseFont}
          options={INHERITABLE_CHINESE_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading3ChineseFont", value)}
        />
        <StyleSelectField
          label="三级标题西文字体"
          icon={Type}
          value={style.heading3LatinFont}
          options={INHERITABLE_LATIN_FONT_OPTIONS}
          onChange={(value) => onFieldChange("heading3LatinFont", value)}
        />
      </StyleSection>

      <StyleSection title="表格" description="设置表格内文字字体、字号与对齐方式。">
        <StyleSelectField
          label="表格中文字体"
          icon={Table2}
          value={style.tableChineseFont}
          options={INHERITABLE_CHINESE_FONT_OPTIONS}
          onChange={(value) => onFieldChange("tableChineseFont", value)}
        />
        <StyleSelectField
          label="表格西文字体"
          icon={Table2}
          value={style.tableLatinFont}
          options={INHERITABLE_LATIN_FONT_OPTIONS}
          onChange={(value) => onFieldChange("tableLatinFont", value)}
        />
        <StyleSelectField
          label="表格字号"
          icon={Hash}
          value={stringifyTableFontSizeValue(style.tableFontSize)}
          options={TABLE_FONT_SIZE_OPTIONS}
          onChange={(value) => onFieldChange("tableFontSize", value === "0" ? 0 : value)}
        />
        <StyleSelectField
          label="单元格对齐"
          icon={AlignCenter}
          value={style.tableCellAlignment}
          options={TABLE_CELL_ALIGNMENT_OPTIONS}
          onChange={(value) => onFieldChange("tableCellAlignment", value)}
        />
      </StyleSection>

      <StyleSection title="段落" description="行距、缩进与首行标题排版规则。">
        <StyleSelectField
          label="行距"
          icon={AlignCenter}
          value={stringifySelectValue(style.lineSpacing)}
          options={LINE_SPACING_OPTIONS}
          onChange={(value) => onFieldChange("lineSpacing", Number(value))}
        />
        <StyleSelectField
          label="首行缩进"
          icon={FileText}
          value={stringifySelectValue(style.firstLineIndentChars)}
          options={FIRST_LINE_INDENT_OPTIONS}
          onChange={(value) => onFieldChange("firstLineIndentChars", Number(value))}
        />
        <StyleSelectField
          label="段前间距"
          icon={FileText}
          value={stringifySelectValue(style.paragraphSpacingBefore)}
          options={PARAGRAPH_SPACING_BEFORE_OPTIONS}
          onChange={(value) => onFieldChange("paragraphSpacingBefore", Number(value))}
        />
        <StyleSelectField
          label="段后间距"
          icon={FileText}
          value={stringifySelectValue(style.paragraphSpacingAfter)}
          options={PARAGRAPH_SPACING_AFTER_OPTIONS}
          onChange={(value) => onFieldChange("paragraphSpacingAfter", Number(value))}
        />
        <StyleSelectField
          label="首行标题对齐"
          icon={AlignCenter}
          value={String(Boolean(style.titleCentered))}
          options={TITLE_ALIGNMENT_OPTIONS}
          onChange={(value) => onFieldChange("titleCentered", value === "true")}
        />
        <StyleSelectField
          label="标题首行缩进"
          icon={FileText}
          value={String(Boolean(style.headingFirstLineIndent))}
          options={HEADING_INDENT_OPTIONS}
          onChange={(value) => onFieldChange("headingFirstLineIndent", value === "true")}
        />
      </StyleSection>

      <StyleSection title="页边距" description="可先选常用预设，再按需微调四边边距。" layout="stack">
        <StyleSelectField
          label="页边距预设"
          icon={LayoutTemplate}
          value={effectiveMarginPreset}
          options={MARGIN_PRESET_OPTIONS}
          onChange={handleMarginPresetChange}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <StyleSelectField
            label="上边距"
            icon={LayoutTemplate}
            value={stringifySelectValue(style.marginTopCm)}
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("marginTopCm", value)}
          />
          <StyleSelectField
            label="下边距"
            icon={LayoutTemplate}
            value={stringifySelectValue(style.marginBottomCm)}
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("marginBottomCm", value)}
          />
          <StyleSelectField
            label="左边距"
            icon={LayoutTemplate}
            value={stringifySelectValue(style.marginLeftCm)}
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("marginLeftCm", value)}
          />
          <StyleSelectField
            label="右边距"
            icon={LayoutTemplate}
            value={stringifySelectValue(style.marginRightCm)}
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("marginRightCm", value)}
          />
        </div>
      </StyleSection>
    </div>
  );
}
