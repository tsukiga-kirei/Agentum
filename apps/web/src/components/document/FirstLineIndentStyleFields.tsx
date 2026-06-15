import React, { useMemo } from "react";
import type { LucideIcon } from "lucide-react";
import { FileText } from "lucide-react";
import {
  isCmFirstLineIndentMode,
  FIRST_LINE_INDENT_MODE_OPTIONS,
  FIRST_LINE_INDENT_OPTIONS,
  FIRST_LINE_INDENT_CM_OPTIONS,
  type FirstLineIndentMode,
  stringifyFirstLineIndentCmValue,
  stringifySelectValue,
} from "../../constants/documentDeliveryStyleOptions";

type FirstLineIndentSelectProps = {
  label: string;
  icon?: LucideIcon;
  value?: string;
  defaultValue?: string;
  placeholder?: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
};

type FirstLineIndentStyleFieldsProps = {
  mode: FirstLineIndentMode;
  chars: number;
  cm: number;
  onModeChange: (mode: FirstLineIndentMode) => void;
  onCharsChange: (value: number) => void;
  onCmChange: (value: number) => void;
  SelectField: React.ComponentType<FirstLineIndentSelectProps>;
};

function withCustomOption(
  value: string,
  options: Array<{ value: string; label: string }>,
): Array<{ value: string; label: string }> {
  if (value && !options.some((option) => option.value === value)) {
    return [{ value, label: value }, ...options];
  }
  return options;
}

export function FirstLineIndentStyleFields({
  mode,
  chars,
  cm,
  onModeChange,
  onCharsChange,
  onCmChange,
  SelectField,
}: FirstLineIndentStyleFieldsProps) {
  const charsValue = stringifySelectValue(chars);
  const cmValue = stringifyFirstLineIndentCmValue(cm);
  const charsOptions = useMemo(
    () => withCustomOption(charsValue, FIRST_LINE_INDENT_OPTIONS),
    [charsValue],
  );
  const cmOptions = useMemo(
    () => withCustomOption(cmValue, FIRST_LINE_INDENT_CM_OPTIONS),
    [cmValue],
  );

  return (
    <>
      <SelectField
        label="首行缩进类型"
        icon={FileText}
        value={mode}
        defaultValue={mode}
        placeholder="请选择首行缩进类型"
        options={FIRST_LINE_INDENT_MODE_OPTIONS}
        onChange={(value) => onModeChange(isCmFirstLineIndentMode(value) ? "cm" : "chars")}
      />
      {isCmFirstLineIndentMode(mode) ? (
        <SelectField
          label="首行缩进（厘米）"
          icon={FileText}
          value={cmValue}
          defaultValue={cmValue}
          placeholder="请选择首行缩进"
          options={cmOptions}
          onChange={(value) => onCmChange(Number(value))}
        />
      ) : (
        <SelectField
          label="首行缩进（字符）"
          icon={FileText}
          value={charsValue}
          defaultValue={charsValue}
          placeholder="请选择首行缩进"
          options={charsOptions}
          onChange={(value) => onCharsChange(Number(value))}
        />
      )}
    </>
  );
}
