import React, { useState, useEffect, useMemo } from "react";
import { DatePicker, Select } from "antd";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import type { InputFieldConfig, RuntimePreviewStep } from "../../types/runtime-types";
import { AlertCircle, CalendarDays, ChevronDown, FileText } from "lucide-react";
import {
  getSystemDefaultValueLabel,
  isInputFieldConfig,
  normalizeInputField,
  normalizeInputFieldOptions,
  resolveInputFieldDefaultValue,
} from "../../utils/workflowInputField";

interface UserInputPanelProps {
  activeStep: RuntimePreviewStep;
  templateVariables?: Record<string, unknown>;
  readOnly: boolean;
  onSubmit: (payload: Record<string, unknown>) => void;
}

const runtimeSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown workbench-user-input-select-dropdown" } };
const runtimeSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

dayjs.locale("zh-cn");

export function UserInputPanel({
  activeStep,
  templateVariables = {},
  readOnly,
  onSubmit,
}: UserInputPanelProps) {
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const fieldConfigs = useMemo((): InputFieldConfig[] => {
    const configs = activeStep.configSnapshot?.inputFields;
    if (Array.isArray(configs)) {
      return configs
        .filter(isInputFieldConfig)
        .map((field, index) => {
          const normalized = normalizeInputField({
            ...field,
            id: field.id || `field-${index}`,
            variable: field.variable || field.label,
            defaultValue: field.defaultValueSource === "system"
              ? resolveInputFieldDefaultValue(field, templateVariables)
              : renderRuntimeTemplate(field.defaultValue ?? "", templateVariables),
            placeholder: renderRuntimeTemplate(field.placeholder || (field.fieldType === "select" ? "请选择" : `请输入${field.label}`), templateVariables),
          });
          return normalized;
        });
    }
    return (activeStep.inputs || []).map((field, index) => normalizeInputField({
      id: `field-${index}`,
      label: field.label,
      variable: field.label,
      placeholder: `请输入${field.label}`,
      defaultValue: field.value,
      required: true,
      fieldType: "text",
    }));
  }, [activeStep.configSnapshot, activeStep.inputs, templateVariables]);

  useEffect(() => {
    const initial: Record<string, string> = {};
    fieldConfigs.forEach((field) => {
      const matched = activeStep.inputs?.find((item) => item.label === field.label);
      // 运行预览里的 inputs 可能仍带设计态模板，初始化表单时必须再按本次运行变量解析，避免 {{day}} 原样展示。
      const candidateValue = renderRuntimeTemplate(matched?.value || field.defaultValue || "", templateVariables);
      if (field.fieldType === "select") {
        const options = normalizeInputFieldOptions(field.options, field.placeholder);
        initial[field.id] = candidateValue && options.some((option) => option.value === candidateValue) ? candidateValue : "";
        return;
      }
      initial[field.id] = candidateValue;
    });
    setFormValues(initial);
    setErrorMsg(null);
  }, [activeStep.nodeRunId, fieldConfigs, activeStep.inputs]);

  function handleInputChange(fieldId: string, val: string) {
    setFormValues((prev) => ({ ...prev, [fieldId]: val }));
    setErrorMsg(null);
  }

  function handleFormSubmit(e: React.FormEvent) {
    e.preventDefault();

    const emptyField = fieldConfigs.find((field) => field.required !== false && !formValues[field.id]?.trim());
    if (emptyField) {
      setErrorMsg(emptyField.fieldType === "select" ? `请选择「${emptyField.label}」` : `请填写「${emptyField.label}」`);
      return;
    }

    const payload: Record<string, unknown> = {};
    fieldConfigs.forEach((field) => {
      payload[field.variable] = formValues[field.id]?.trim() ?? "";
    });

    onSubmit(payload);
  }

  return (
    <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-6 space-y-5 max-w-3xl mx-auto shadow-sm">
      <header className="flex items-center gap-3 border-b border-slate-100 dark:border-slate-800 pb-4">
        <FileText className="text-amber-500 shrink-0" size={22} />
        <div>
          <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">信息填写</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">请填写当前步骤所需的业务资料，提交后流程将继续推进。</p>
        </div>
      </header>

      <form id="workbench-user-input-form" onSubmit={handleFormSubmit} className="space-y-5">
        {fieldConfigs.length === 0 ? (
          <div className="text-center py-8 text-slate-400 text-sm">
            本步骤不需要填写额外资料，直接提交即可。
          </div>
        ) : (
          <div className="space-y-5">
            {fieldConfigs.map((field) => {
              const val = formValues[field.id] || "";
              const isLargeText =
                field.fieldType === "text"
                && (
                  field.label.includes("描述")
                  || field.label.includes("材料")
                  || field.label.includes("内容")
                  || (field.placeholder?.length ?? 0) > 20
                );
              const options = normalizeInputFieldOptions(field.options, field.placeholder);
              const systemValueLocked = field.defaultValueSource === "system" && field.allowManualOverride === false;
              const inputDisabled = readOnly || activeStep.state !== "waiting" || systemValueLocked;
              const datePickerGranularity = field.defaultValueSource === "system"
                ? field.systemDefaultValue === "current_year"
                  ? "year"
                  : ["current_month", "previous_month"].includes(field.systemDefaultValue ?? "")
                    ? "month"
                    : "day"
                : field.dateGranularity ?? "day";
              const datePickerMode = datePickerGranularity === "year" ? "year" : datePickerGranularity === "month" ? "month" : "date";
              const datePickerFormat = datePickerGranularity === "year" ? "YYYY年" : datePickerGranularity === "month" ? "YYYY年MM月" : "YYYY年MM月DD日";
              const dateStorageFormat = datePickerGranularity === "year" ? "YYYY" : datePickerGranularity === "month" ? "YYYY-MM" : "YYYY-MM-DD";

              return (
                <div key={field.id} className="flex flex-col gap-2">
                  <label className="text-sm font-medium text-slate-700 dark:text-slate-300 flex items-center gap-1">
                    {field.label}
                    {field.required !== false
                      ? <span className="text-rose-500">*</span>
                      : <span className="text-xs font-normal text-slate-400">（选填）</span>}
                  </label>

                  {field.fieldType === "select" ? (
                    <Select
                      value={val || undefined}
                      disabled={inputDisabled}
                      onChange={(value) => handleInputChange(field.id, value)}
                      className="agent-admin-select workbench-user-input-select w-full"
                      classNames={runtimeSelectClassNames}
                      suffixIcon={runtimeSelectSuffixIcon}
                      placeholder={field.placeholder || "请选择"}
                      options={options.map((option) => ({ value: option.value, label: option.label }))}
                    />
                  ) : field.fieldType === "date" ? (
                    <DatePicker
                      picker={datePickerMode}
                      format={datePickerFormat}
                      value={parsePickerValue(val)}
                      disabled={inputDisabled}
                      allowClear={field.required === false || field.allowManualOverride !== false}
                      inputReadOnly
                      placeholder={datePickerGranularity === "year" ? "请选择年份" : datePickerGranularity === "month" ? "请选择年月" : "请选择日期"}
                      suffixIcon={<CalendarDays className="h-[18px] w-[18px]" aria-hidden="true" />}
                      className="workbench-user-input-date-picker w-full"
                      classNames={{ popup: { root: "workbench-user-input-date-picker-popup" } }}
                      onChange={(value) => handleInputChange(field.id, value ? value.format(dateStorageFormat) : "")}
                    />
                  ) : isLargeText ? (
                    <textarea
                      rows={6}
                      value={val}
                      disabled={inputDisabled}
                      placeholder={field.placeholder}
                      onChange={(event) => handleInputChange(field.id, event.target.value)}
                      className="sys-input w-full p-3.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-sm leading-relaxed focus:ring-1 focus:ring-blue-500 focus:border-blue-500 resize-y min-h-[140px]"
                    />
                  ) : (
                    <input
                      type="text"
                      value={val}
                      disabled={inputDisabled}
                      placeholder={field.placeholder}
                      onChange={(event) => handleInputChange(field.id, event.target.value)}
                      className="sys-input w-full p-3.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-sm focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
                    />
                  )}
                  {field.defaultValueSource === "system" ? (
                    <span className="text-xs text-slate-400">
                      系统按本次运行时间自动填写“{getSystemDefaultValueLabel(field.systemDefaultValue)}”
                      {field.allowManualOverride === false ? "，不可修改" : "，可按需修改"}
                    </span>
                  ) : null}
                </div>
              );
            })}
          </div>
        )}

        {errorMsg && (
          <div className="p-3 rounded-lg bg-rose-50 dark:bg-rose-950/20 border border-rose-100 dark:border-rose-900/40 text-rose-600 dark:text-rose-450 text-sm flex items-center gap-2">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}
      </form>
    </div>
  );
}

function renderRuntimeTemplate(template: string, variables: Record<string, unknown>): string {
  return template.replace(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g, (_placeholder, variableName: string) =>
    Object.prototype.hasOwnProperty.call(variables, variableName)
      ? stringifyValue(variables[variableName])
      : "",
  );
}

function parsePickerValue(value: string) {
  if (!value) {
    return null;
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
