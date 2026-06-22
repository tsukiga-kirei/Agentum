import React, { useMemo } from "react";
import type { LucideIcon } from "lucide-react";
import { AlignCenter } from "lucide-react";
import {
  isExactLineSpacingMode,
  LINE_SPACING_MODE_OPTIONS,
  LINE_SPACING_OPTIONS,
  LINE_SPACING_PT_OPTIONS,
  type LineSpacingMode,
  stringifyLineSpacingPtValue,
  stringifySelectValue,
} from "../../constants/documentDeliveryStyleOptions";

type LineSpacingSelectProps = {
  label: string;
  icon?: LucideIcon;
  value?: string;
  defaultValue?: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
};

type LineSpacingStyleFieldsProps = {
  mode: LineSpacingMode;
  multiple: number;
  pt: number;
  labelPrefix?: string;
  onModeChange: (mode: LineSpacingMode) => void;
  onMultipleChange: (value: number) => void;
  onPtChange: (value: number) => void;
  SelectField: React.ComponentType<LineSpacingSelectProps>;
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

export function LineSpacingStyleFields({
  mode,
  multiple,
  pt,
  labelPrefix = "",
  onModeChange,
  onMultipleChange,
  onPtChange,
  SelectField,
}: LineSpacingStyleFieldsProps) {
  const multipleValue = stringifySelectValue(multiple);
  const ptValue = stringifyLineSpacingPtValue(pt);
  const multipleOptions = useMemo(
    () => withCustomOption(multipleValue, LINE_SPACING_OPTIONS),
    [multipleValue],
  );
  const ptOptions = useMemo(
    () => withCustomOption(ptValue, LINE_SPACING_PT_OPTIONS),
    [ptValue],
  );

  return (
    <>
      <SelectField
        label={`${labelPrefix}行距类型`}
        icon={AlignCenter}
        value={mode}
        defaultValue={mode}
        options={LINE_SPACING_MODE_OPTIONS}
        onChange={(value) => onModeChange(isExactLineSpacingMode(value) ? "exact" : "multiple")}
      />
      {isExactLineSpacingMode(mode) ? (
        <SelectField
          label={`${labelPrefix}行距（磅）`}
          icon={AlignCenter}
          value={ptValue}
          defaultValue={ptValue}
          options={ptOptions}
          onChange={(value) => onPtChange(Number(stringifyLineSpacingPtValue(value)))}
        />
      ) : (
        <SelectField
          label={`${labelPrefix}行距（倍数）`}
          icon={AlignCenter}
          value={multipleValue}
          defaultValue={multipleValue}
          options={multipleOptions}
          onChange={(value) => onMultipleChange(Number(value))}
        />
      )}
    </>
  );
}
