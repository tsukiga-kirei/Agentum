import React, { useMemo, useState } from "react";
import type { LucideIcon } from "lucide-react";
import { AlignCenter, FileText, Hash, LayoutTemplate, Table2, Type } from "lucide-react";
import {
  CHINESE_FONT_OPTIONS,
  detectMarginPreset,
  FIRST_LINE_INDENT_OPTIONS,
  FONT_SIZE_OPTIONS,
  HEADING_INDENT_OPTIONS,
  INHERITABLE_CHINESE_FONT_OPTIONS,
  INHERITABLE_LATIN_FONT_OPTIONS,
  INHERITABLE_NUMBER_FONT_OPTIONS,
  LATIN_FONT_OPTIONS,
  NUMBER_FONT_OPTIONS,
  MARGIN_CM_OPTIONS,
  MARGIN_PRESETS,
  MARGIN_PRESET_OPTIONS,
  type MarginPresetKey,
  PARAGRAPH_SPACING_AFTER_OPTIONS,
  PARAGRAPH_SPACING_BEFORE_OPTIONS,
  readLineSpacingMode,
  stringifyFontSizeValue,
  stringifyTableFontSizeValue,
  TABLE_BORDER_OPTIONS,
  TABLE_BORDER_WIDTH_OPTIONS,
  TABLE_CELL_ALIGNMENT_OPTIONS,
  TABLE_FONT_SIZE_OPTIONS,
  TABLE_HEADER_BOLD_OPTIONS,
  TITLE_ALIGNMENT_OPTIONS,
} from "../../constants/documentDeliveryStyleOptions";
import { LineSpacingStyleFields } from "./LineSpacingStyleFields";
import { FirstLineIndentStyleFields } from "./FirstLineIndentStyleFields";

type AdminSelectFieldProps = {
  label: string;
  icon?: LucideIcon;
  defaultValue?: string;
  value?: string;
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
      <AdminStyleSection title="字体" description="设置正文的中文、西文与数字默认字体。">
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
        <AdminStyleRow>
          <SelectField
            label="数字字体"
            icon={Hash}
            defaultValue={values.documentNumberFont || "Times New Roman"}
            placeholder="请选择数字字体"
            options={NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange("documentNumberFont", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="字号" description="正文与各级标题字号，支持中文字号名与 pt 数字。">
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

      <AdminStyleSection title="标题字体" description="可为各级标题单独设置中文、西文与数字字体，留空则继承正文。">
        <AdminStyleRow>
          <SelectField
            label="一级标题中文字体"
            icon={Type}
            defaultValue={values.documentHeading1ChineseFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading1ChineseFont", value)}
          />
          <SelectField
            label="一级标题西文字体"
            icon={Type}
            defaultValue={values.documentHeading1LatinFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_LATIN_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading1LatinFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="一级标题数字字体"
            icon={Hash}
            defaultValue={values.documentHeading1NumberFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading1NumberFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="二级标题中文字体"
            icon={Type}
            defaultValue={values.documentHeading2ChineseFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading2ChineseFont", value)}
          />
          <SelectField
            label="二级标题西文字体"
            icon={Type}
            defaultValue={values.documentHeading2LatinFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_LATIN_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading2LatinFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="二级标题数字字体"
            icon={Hash}
            defaultValue={values.documentHeading2NumberFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading2NumberFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="三级标题中文字体"
            icon={Type}
            defaultValue={values.documentHeading3ChineseFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading3ChineseFont", value)}
          />
          <SelectField
            label="三级标题西文字体"
            icon={Type}
            defaultValue={values.documentHeading3LatinFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_LATIN_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading3LatinFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="三级标题数字字体"
            icon={Hash}
            defaultValue={values.documentHeading3NumberFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange("documentHeading3NumberFont", value)}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="表格" description="设置表格文字、首行、框线与独立行距；不再附加表头底色。">
        <AdminStyleRow>
          <SelectField
            label="表格中文字体"
            icon={Table2}
            defaultValue={values.documentTableChineseFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_CHINESE_FONT_OPTIONS}
            onChange={(value) => onChange("documentTableChineseFont", value)}
          />
          <SelectField
            label="表格西文字体"
            icon={Table2}
            defaultValue={values.documentTableLatinFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_LATIN_FONT_OPTIONS}
            onChange={(value) => onChange("documentTableLatinFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="表格数字字体"
            icon={Hash}
            defaultValue={values.documentTableNumberFont || ""}
            placeholder="继承正文"
            options={INHERITABLE_NUMBER_FONT_OPTIONS}
            onChange={(value) => onChange("documentTableNumberFont", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="表格字号"
            icon={Hash}
            defaultValue={stringifyTableFontSizeValue(values.documentTableFontSize || "0")}
            placeholder="继承正文"
            options={TABLE_FONT_SIZE_OPTIONS}
            onChange={(value) => onChange("documentTableFontSize", value)}
          />
          <SelectField
            label="单元格对齐"
            icon={AlignCenter}
            defaultValue={values.documentTableCellAlignment || "left"}
            placeholder="请选择对齐方式"
            options={TABLE_CELL_ALIGNMENT_OPTIONS}
            onChange={(value) => onChange("documentTableCellAlignment", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="首行加粗"
            icon={Table2}
            defaultValue={values.documentTableHeaderBold || "false"}
            placeholder="请选择首行格式"
            options={TABLE_HEADER_BOLD_OPTIONS}
            onChange={(value) => onChange("documentTableHeaderBold", value)}
          />
          <SelectField
            label="表格框线"
            icon={Table2}
            defaultValue={values.documentTableBorders || "true"}
            placeholder="请选择框线"
            options={TABLE_BORDER_OPTIONS}
            onChange={(value) => onChange("documentTableBorders", value)}
          />
        </AdminStyleRow>
        {values.documentTableBorders !== "false" ? (
          <AdminStyleRow>
            <SelectField
              label="框线磅数"
              icon={Table2}
              defaultValue={values.documentTableBorderWidthPt || "0.5"}
              placeholder="请选择框线磅数"
              options={TABLE_BORDER_WIDTH_OPTIONS}
              onChange={(value) => onChange("documentTableBorderWidthPt", value)}
            />
          </AdminStyleRow>
        ) : null}
        <AdminStyleRow>
          <LineSpacingStyleFields
            mode={readLineSpacingMode(values.documentTableLineSpacingMode)}
            multiple={Number(values.documentTableLineSpacing || "1")}
            pt={Number(values.documentTableLineSpacingPt || "12")}
            labelPrefix="表格"
            onModeChange={(value) => onChange("documentTableLineSpacingMode", value)}
            onMultipleChange={(value) => onChange("documentTableLineSpacing", String(value))}
            onPtChange={(value) => onChange("documentTableLineSpacingPt", String(value))}
            SelectField={SelectField}
          />
        </AdminStyleRow>
      </AdminStyleSection>

      <AdminStyleSection title="段落" description="行距、缩进与首行标题排版规则。">
        <AdminStyleRow>
          <LineSpacingStyleFields
            mode={readLineSpacingMode(values.documentLineSpacingMode)}
            multiple={Number(values.documentLineSpacing || "1.5")}
            pt={Number(values.documentLineSpacingPt || "18")}
            onModeChange={(value) => onChange("documentLineSpacingMode", value)}
            onMultipleChange={(value) => onChange("documentLineSpacing", String(value))}
            onPtChange={(value) => onChange("documentLineSpacingPt", String(value))}
            SelectField={SelectField}
          />
          <FirstLineIndentStyleFields
            mode={(values.documentFirstLineIndentMode as "chars" | "cm") || "chars"}
            chars={Number(values.documentFirstLineIndentChars || "2")}
            cm={Number(values.documentFirstLineIndentCm || "0.75")}
            onModeChange={(value) => onChange("documentFirstLineIndentMode", value)}
            onCharsChange={(value) => onChange("documentFirstLineIndentChars", String(value))}
            onCmChange={(value) => onChange("documentFirstLineIndentCm", String(value))}
            SelectField={SelectField}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="段前间距"
            icon={FileText}
            defaultValue={values.documentParagraphSpacingBefore || "0"}
            placeholder="请选择段前间距"
            options={PARAGRAPH_SPACING_BEFORE_OPTIONS}
            onChange={(value) => onChange("documentParagraphSpacingBefore", value)}
          />
          <SelectField
            label="段后间距"
            icon={FileText}
            defaultValue={values.documentParagraphSpacingAfter || "6"}
            placeholder="请选择段后间距"
            options={PARAGRAPH_SPACING_AFTER_OPTIONS}
            onChange={(value) => onChange("documentParagraphSpacingAfter", value)}
          />
        </AdminStyleRow>
        <AdminStyleRow>
          <SelectField
            label="首行标题对齐"
            icon={AlignCenter}
            defaultValue={values.documentTitleCentered || "false"}
            placeholder="请选择首行标题对齐"
            options={TITLE_ALIGNMENT_OPTIONS}
            onChange={(value) => onChange("documentTitleCentered", value)}
          />
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
