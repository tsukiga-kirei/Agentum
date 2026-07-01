import type { InputFieldConfig } from "../types/runtime-types";

export type WorkflowInputFieldType = NonNullable<InputFieldConfig["fieldType"]>;

export const WORKFLOW_INPUT_FIELD_TYPE_OPTIONS: Array<{ value: WorkflowInputFieldType; label: string }> = [
  { value: "text", label: "文本输入" },
  { value: "select", label: "下拉选择" },
];

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
  const fieldType: WorkflowInputFieldType = field.fieldType === "select" ? "select" : "text";
  const placeholder = field.placeholder ?? (fieldType === "select" ? "请选择" : "请输入内容");

  return {
    ...field,
    placeholder,
    defaultValue: field.defaultValue ?? "",
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
  return fieldType === "select" ? "下拉框" : "文本框";
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

export function shouldSyncInputFieldOptionValue(option: { label: string; value: string }, index: number): boolean {
  const defaultLabel = `选项 ${index + 1}`;
  return option.value === option.label
    || option.value === defaultLabel
    || option.value === `选项${index + 1}`
    || /^option_\d+$/.test(option.value);
}

function isPlaceholderLikeOption(label: string, value: string, placeholder: string): boolean {
  if (placeholder && (label === placeholder || value === placeholder)) {
    return true;
  }
  return label === "请选择" || value === "请选择" || value === "__placeholder__" || value === "placeholder";
}
