import type { InputFieldConfig } from "../types/runtime-types";

export type WorkflowInputFieldType = NonNullable<InputFieldConfig["fieldType"]>;

export const WORKFLOW_INPUT_FIELD_TYPE_OPTIONS: Array<{ value: WorkflowInputFieldType; label: string }> = [
  { value: "text", label: "文本输入" },
  { value: "date", label: "日期" },
  { value: "select", label: "下拉选择" },
];

export const WORKFLOW_SYSTEM_DEFAULT_VALUE_OPTIONS = [
  { value: "current_date", label: "当前日期", description: "格式：YYYY-MM-DD" },
  { value: "current_year", label: "当前年", description: "格式：YYYY" },
  { value: "current_month", label: "当前年月", description: "格式：YYYY-MM" },
  { value: "previous_month", label: "上个年月", description: "格式：YYYY-MM" },
] as const;

export function normalizeInputFieldOptions(value: unknown, placeholder?: string): Array<{ label: string; value: string }> {
  if (!Array.isArray(value)) {
    return [];
  }

  const placeholderText = (placeholder ?? "").trim();
  return value
    .map((item) => {
      if (typeof item !== "object" || item === null) {
        return null;
      }
      const label = String((item as { label?: unknown }).label ?? "").trim();
      const optionValue = String((item as { value?: unknown }).value ?? "").trim();
      if (!label || !optionValue || isPlaceholderLikeOption(label, optionValue, placeholderText)) {
        return null;
      }
      return { label, value: optionValue };
    })
    .filter((item): item is { label: string; value: string } => item !== null);
}

export function normalizeInputField(field: InputFieldConfig): InputFieldConfig {
  const fieldType: WorkflowInputFieldType = ["select", "date"].includes(field.fieldType ?? "")
    ? field.fieldType as WorkflowInputFieldType
    : "text";
  const placeholder = field.placeholder ?? (fieldType === "select" ? "请选择" : "请输入内容");
  const defaultValue = field.defaultValue ?? "";
  const defaultValueSource = field.defaultValueSource === "system"
    ? "system"
    : field.defaultValueSource === "none"
      ? "none"
      : defaultValue
        ? "fixed"
        : "none";

  return {
    ...field,
    placeholder,
    defaultValue,
    defaultValueSource,
    systemDefaultValue: field.systemDefaultValue ?? "current_date",
    dateGranularity: field.dateGranularity ?? "day",
    allowManualOverride: field.allowManualOverride !== false,
    required: field.required !== false,
    fieldType,
    options: fieldType === "select" ? normalizeInputFieldOptions(field.options, placeholder) : undefined,
  };
}

export function isInputFieldConfig(value: unknown): value is InputFieldConfig {
  return typeof value === "object"
    && value !== null
    && typeof (value as InputFieldConfig).id === "string"
    && typeof (value as InputFieldConfig).label === "string"
    && typeof (value as InputFieldConfig).variable === "string";
}

export function readInputFields(value: unknown, outputVariables: string[]): InputFieldConfig[] {
  if (Array.isArray(value)) {
    const fields = value.filter(isInputFieldConfig);
    if (fields.length > 0) {
      return fields.map((field) => normalizeInputField({
        ...field,
        defaultValue: field.defaultValue ?? "",
        required: field.required !== false,
      }));
    }
  }

  return outputVariables.length > 0
    ? outputVariables.map((variable, index) => normalizeInputField(createInputField(index, "text", variable)))
    : [createInputField(0, "text")];
}

export function createInputField(
  index: number,
  fieldType: WorkflowInputFieldType = "text",
  variable?: string,
): InputFieldConfig {
  const isSelect = fieldType === "select";

  return normalizeInputField({
    id: `field_${Date.now().toString(36)}_${index}`,
    label: isSelect
      ? (index === 0 ? "下拉选项" : `下拉字段 ${index + 1}`)
      : (index === 0 ? "业务输入" : `输入字段 ${index + 1}`),
    variable: variable ?? `input_${index + 1}`,
    placeholder: isSelect ? "请选择" : "请输入内容",
    defaultValue: "",
    required: true,
    fieldType,
    options: isSelect ? [createInputFieldOption(0)] : undefined,
  });
}

export function getInputFieldTypeLabel(fieldType?: WorkflowInputFieldType): string {
  if (fieldType === "select") return "下拉框";
  if (fieldType === "date") return "日期框";
  return "文本框";
}

export function getSystemDefaultValueLabel(value?: InputFieldConfig["systemDefaultValue"]): string {
  return WORKFLOW_SYSTEM_DEFAULT_VALUE_OPTIONS.find((option) => option.value === value)?.label ?? "当前日期";
}

/** 人工填写页使用后端下发的运行时变量预填，避免浏览器本地时区与业务时区不一致。 */
export function resolveInputFieldDefaultValue(
  field: InputFieldConfig,
  variables: Record<string, unknown>,
): string {
  const normalized = normalizeInputField(field);
  if (normalized.defaultValueSource !== "system") {
    return normalized.defaultValue ?? "";
  }
  const variableName = normalized.systemDefaultValue === "previous_month"
    ? "previous_year_month"
    : normalized.systemDefaultValue === "current_month"
      ? "current_year_month"
      : normalized.systemDefaultValue === "current_year"
        ? "current_year"
        : "current_date";
  const value = variables[variableName];
  return value === null || value === undefined ? "" : String(value);
}

export function validateInputFieldDraft(field: InputFieldConfig): string | null {
  const normalized = normalizeInputField(field);
  if (normalized.fieldType === "select" && (normalized.options?.length ?? 0) === 0) {
    return "下拉框至少需要配置一个有效选项（显示文本与选项值均不能为空）";
  }
  return null;
}

export function createInputFieldOption(index: number): { label: string; value: string } {
  const label = `选项 ${index + 1}`;
  return { label, value: label };
}

/** 将选项展示文案同步为提交值；仅在保存/提交选项列表时调用，避免输入过程中联动导致失焦。 */
export function syncInputFieldOptionValuesFromLabels(
  options: Array<{ label: string; value: string }>,
): Array<{ label: string; value: string }> {
  return options.map((option) => {
    const label = option.label.trim();
    return { ...option, value: label || option.value };
  });
}

function isPlaceholderLikeOption(label: string, value: string, placeholder: string): boolean {
  if (placeholder && (label === placeholder || value === placeholder)) {
    return true;
  }
  return label === "请选择" || value === "请选择" || value === "__placeholder__" || value === "placeholder";
}
