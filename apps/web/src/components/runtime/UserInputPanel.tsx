import React, { useState, useEffect, useMemo } from "react";
import type { InputFieldConfig, RuntimePreviewStep } from "../../types/runtime-types";
import { AlertCircle, FileText } from "lucide-react";
import { isInputFieldConfig, normalizeInputField, normalizeInputFieldOptions } from "../../utils/workflowInputField";

interface UserInputPanelProps {
  activeStep: RuntimePreviewStep;
  templateVariables?: Record<string, unknown>;
  readOnly: boolean;
  onSubmit: (payload: Record<string, unknown>) => void;
}

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
            defaultValue: renderRuntimeTemplate(field.defaultValue ?? "", templateVariables),
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
      initial[field.id] = matched?.value || field.defaultValue || "";
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
                field.fieldType !== "select"
                && (
                  field.label.includes("描述")
                  || field.label.includes("材料")
                  || field.label.includes("内容")
                  || (field.placeholder?.length ?? 0) > 20
                );
              const options = normalizeInputFieldOptions(field.options);

              return (
                <div key={field.id} className="flex flex-col gap-2">
                  <label className="text-sm font-medium text-slate-700 dark:text-slate-300 flex items-center gap-1">
                    {field.label}
                    {field.required !== false
                      ? <span className="text-rose-500">*</span>
                      : <span className="text-xs font-normal text-slate-400">（选填）</span>}
                  </label>

                  {field.fieldType === "select" ? (
                    <select
                      value={val}
                      disabled={readOnly || activeStep.state !== "waiting"}
                      onChange={(event) => handleInputChange(field.id, event.target.value)}
                      className="sys-input w-full p-3.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-sm focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
                    >
                      <option value="">{field.placeholder || "请选择"}</option>
                      {options.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  ) : isLargeText ? (
                    <textarea
                      rows={6}
                      value={val}
                      disabled={readOnly || activeStep.state !== "waiting"}
                      placeholder={field.placeholder}
                      onChange={(event) => handleInputChange(field.id, event.target.value)}
                      className="sys-input w-full p-3.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-sm leading-relaxed focus:ring-1 focus:ring-blue-500 focus:border-blue-500 resize-y min-h-[140px]"
                    />
                  ) : (
                    <input
                      type="text"
                      value={val}
                      disabled={readOnly || activeStep.state !== "waiting"}
                      placeholder={field.placeholder}
                      onChange={(event) => handleInputChange(field.id, event.target.value)}
                      className="sys-input w-full p-3.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-sm focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
                    />
                  )}
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
