import React, { useMemo, useState } from "react";
import type { LucideIcon } from "lucide-react";
import { AlignCenter, FileText, Hash, LayoutTemplate, Type } from "lucide-react";
import {
  CHINESE_FONT_OPTIONS,
  detectMarginPreset,
  FIRST_LINE_INDENT_OPTIONS,
  FONT_SIZE_OPTIONS,
  HEADING_INDENT_OPTIONS,
  LATIN_FONT_OPTIONS,
  LINE_SPACING_OPTIONS,
  MARGIN_CM_OPTIONS,
  MARGIN_PRESETS,
  MARGIN_PRESET_OPTIONS,
  type MarginPresetKey,
  PARAGRAPH_SPACING_OPTIONS,
  stringifyFontSizeValue,
  TITLE_ALIGNMENT_OPTIONS,
} from "../../constants/documentDeliveryStyleOptions";

type AdminSelectFieldProps = {
  label: string;
  icon?: LucideIcon;
  defaultValue?: string;
  placeholder?: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
};

type DocumentDeliveryStyleAdminSectionsProps = {
  values: Record<string, string>;
  onChange: (key: string, value: string) => void;
  SelectField: React.ComponentType<AdminSelectFieldProps>;
};

function AdminStyleSection({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
      <div className="mb-3">
        <h5 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h5>
        {description ? <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{description}</p> : null}
      </div>
      <div className="space-y-3">{children}</div>
    </section>
  );
}

function AdminStyleRow({ children }: { children: React.ReactNode }) {
  return <div className="sys-field-row">{children}</div>;
}

export function DocumentDeliveryStyleAdminSections({
  values,
  onChange,
  SelectField,
}: DocumentDeliveryStyleAdminSectionsProps) {
  const initialPreset = useMemo(
    () => detectMarginPreset({
      marginTopCm: Number(values.documentMarginTopCm || "2.54"),
      marginBottomCm: Number(values.documentMarginBottomCm || "2.54"),
      marginLeftCm: Number(values.documentMarginLeftCm || "3.18"),
      marginRightCm: Number(values.documentMarginRightCm || "3.18"),
    }),
    // 仅在打开编辑抽屉时根据当前能力快照初始化一次。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const [marginPreset, setMarginPreset] = useState<MarginPresetKey>(initialPreset);

  function handleMarginPresetChange(value: string) {
    const preset = value as MarginPresetKey;
    setMarginPreset(preset);
    onChange("documentMarginPreset", preset);
    if (preset === "custom") {
      return;
    }
    const margins = MARGIN_PRESETS[preset];
    onChange("documentMarginTopCm", String(margins.marginTopCm));
    onChange("documentMarginBottomCm", String(margins.marginBottomCm));
    onChange("documentMarginLeftCm", String(margins.marginLeftCm));
    onChange("documentMarginRightCm", String(margins.marginRightCm));
  }

  function handleCustomMarginChange(key: string, value: string) {
    setMarginPreset("custom");
    onChange("documentMarginPreset", "custom");
    onChange(key, value);
  }

  return (
    <div className="space-y-4">
      <AdminStyleSection title="字体" description="设置文档中西文默认字体。">
        <AdminStyleRow>
          <SelectField
            label="中文字体"
            icon={Type}
            defaultValue={values.documentChineseFont || "宋体"}
            placeholder="请选择中文字体"
            options={CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange("documentChineseFont", value)}
          />
          <SelectField
            label="西文字体"
            icon={Type}
            defaultValue={values.documentLatinFont || "Times New Roman"}
            placeholder="请选择西文字体"
            options={LATIN_FONT_OPTIONS}
            onChange={(value) => onChange("documentLatinFont", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="字号" description="正文与各级标题字号，支持中文字号名。">
        <AdminStyleRow>
          <SelectField
            label="正文字号"
            icon={Hash}
            defaultValue={stringifyFontSizeValue(values.documentBodyFontSize || "小四")}
            placeholder="请选择正文字号"
            options={FONT_SIZE_OPTIONS}
            onChange={(value) => onChange("documentBodyFontSize", value)}
          />
          <SelectField
            label="一级标题字号"
            icon={Hash}
            defaultValue={stringifyFontSizeValue(values.documentHeading1FontSize || "三号")}
            placeholder="请选择一级标题字号"
            options={FONT_SIZE_OPTIONS}
            onChange={(value) => onChange("documentHeading1FontSize", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="二级标题字号"
            icon={Hash}
            defaultValue={stringifyFontSizeValue(values.documentHeading2FontSize || "四号")}
            placeholder="请选择二级标题字号"
            options={FONT_SIZE_OPTIONS}
            onChange={(value) => onChange("documentHeading2FontSize", value)}
          />
          <SelectField
            label="三级标题字号"
            icon={Hash}
            defaultValue={stringifyFontSizeValue(values.documentHeading3FontSize || "小四")}
            placeholder="请选择三级标题字号"
            options={FONT_SIZE_OPTIONS}
            onChange={(value) => onChange("documentHeading3FontSize", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="段落" description="行距、缩进与首行标题排版规则。">
        <AdminStyleRow>
          <SelectField
            label="行距"
            icon={AlignCenter}
            defaultValue={values.documentLineSpacing || "1.5"}
            placeholder="请选择行距"
            options={LINE_SPACING_OPTIONS}
            onChange={(value) => onChange("documentLineSpacing", value)}
          />
          <SelectField
            label="首行缩进"
            icon={FileText}
            defaultValue={values.documentFirstLineIndentChars || "2"}
            placeholder="请选择首行缩进"
            options={FIRST_LINE_INDENT_OPTIONS}
            onChange={(value) => onChange("documentFirstLineIndentChars", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="段后间距"
            icon={FileText}
            defaultValue={values.documentParagraphSpacingAfter || "6"}
            placeholder="请选择段后间距"
            options={PARAGRAPH_SPACING_OPTIONS}
            onChange={(value) => onChange("documentParagraphSpacingAfter", value)}
          />
          <SelectField
            label="首行标题对齐"
            icon={AlignCenter}
            defaultValue={values.documentTitleCentered || "false"}
            placeholder="请选择首行标题对齐"
            options={TITLE_ALIGNMENT_OPTIONS}
            onChange={(value) => onChange("documentTitleCentered", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="标题首行缩进"
            icon={FileText}
            defaultValue={values.documentHeadingFirstLineIndent || "false"}
            placeholder="请选择标题首行缩进"
            options={HEADING_INDENT_OPTIONS}
            onChange={(value) => onChange("documentHeadingFirstLineIndent", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="页边距" description="可先选常用预设，再按需微调四边边距。">
        <SelectField
          label="页边距预设"
          icon={LayoutTemplate}
          defaultValue={marginPreset}
          placeholder="请选择页边距预设"
          options={MARGIN_PRESET_OPTIONS}
          onChange={handleMarginPresetChange}
        />
        <AdminStyleRow>
          <SelectField
            label="上边距"
            icon={LayoutTemplate}
            defaultValue={values.documentMarginTopCm || "2.54"}
            placeholder="请选择上边距"
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("documentMarginTopCm", value)}
          />
          <SelectField
            label="下边距"
            icon={LayoutTemplate}
            defaultValue={values.documentMarginBottomCm || "2.54"}
            placeholder="请选择下边距"
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("documentMarginBottomCm", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="左边距"
            icon={LayoutTemplate}
            defaultValue={values.documentMarginLeftCm || "3.18"}
            placeholder="请选择左边距"
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("documentMarginLeftCm", value)}
          />
          <SelectField
            label="右边距"
            icon={LayoutTemplate}
            defaultValue={values.documentMarginRightCm || "3.18"}
            placeholder="请选择右边距"
            options={MARGIN_CM_OPTIONS}
            onChange={(value) => handleCustomMarginChange("documentMarginRightCm", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>
    </div>
  );
}
