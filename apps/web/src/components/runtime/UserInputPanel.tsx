import React, { useState, useEffect } from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { Send, AlertCircle, FileText } from "lucide-react";

interface UserInputPanelProps {
  activeStep: RuntimePreviewStep;
  readOnly: boolean;
  onSubmit: (payload: Record<string, unknown>) => void;
}

export function UserInputPanel({
  activeStep,
  readOnly,
  onSubmit,
}: UserInputPanelProps) {
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const fields = activeStep.inputs || [];

  useEffect(() => {
    const initial: Record<string, string> = {};
    fields.forEach((field) => {
      initial[field.label] = field.value || "";
    });
    setFormValues(initial);
    setErrorMsg(null);
  }, [activeStep.inputs]);

  function handleInputChange(label: string, val: string) {
    setFormValues((prev) => ({ ...prev, [label]: val }));
    setErrorMsg(null);
  }

  function handleFormSubmit(e: React.FormEvent) {
    e.preventDefault();
    
    // Check if any field is empty (basic required validation)
    const emptyField = fields.find((f) => !formValues[f.label]?.trim());
    if (emptyField) {
      setErrorMsg(`请填写「${emptyField.label}」`);
      return;
    }

    // Convert payload keys
    const payload: Record<string, unknown> = {};
    Object.entries(formValues).forEach(([k, v]) => {
      payload[k] = v.trim();
    });

    onSubmit(payload);
  }

  return (
    <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-5 space-y-4 max-w-2xl mx-auto shadow-sm">
      <header className="flex items-center gap-2 border-b border-slate-100 dark:border-slate-800 pb-3">
        <FileText className="text-amber-500" size={18} />
        <div>
          <h3 className="text-xs font-semibold text-slate-800 dark:text-slate-200">信息填写</h3>
          <p className="text-[10px] text-slate-400 mt-0.5">请填写当前步骤所需的业务资料，提交后流程将继续推进。</p>
        </div>
      </header>

      <form onSubmit={handleFormSubmit} className="space-y-4">
        {fields.length === 0 ? (
          <div className="text-center py-6 text-slate-400 text-xs">
            本步骤不需要填写额外资料，直接提交即可。
          </div>
        ) : (
          <div className="space-y-3">
            {fields.map((field, index) => {
              const val = formValues[field.label] || "";
              const isLargeText = field.label.includes("描述") || field.label.includes("材料") || field.label.includes("内容");
              
              return (
                <div key={`${field.label}-${index}`} className="flex flex-col gap-1.5">
                  <label className="text-xs font-medium text-slate-700 dark:text-slate-300 flex items-center gap-1">
                    {field.label}
                    <span className="text-rose-500">*</span>
                  </label>
                  
                  {isLargeText ? (
                    <textarea
                      rows={4}
                      value={val}
                      disabled={readOnly || activeStep.state !== "waiting"}
                      placeholder={`请输入${field.label}`}
                      onChange={(e) => handleInputChange(field.label, e.target.value)}
                      className="sys-input w-full p-2.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-xs focus:ring-1 focus:ring-blue-500 focus:border-blue-500 resize-y"
                    />
                  ) : (
                    <input
                      type="text"
                      value={val}
                      disabled={readOnly || activeStep.state !== "waiting"}
                      placeholder={`请输入${field.label}`}
                      onChange={(e) => handleInputChange(field.label, e.target.value)}
                      className="sys-input w-full p-2.5 rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/20 text-xs focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
                    />
                  )}
                </div>
              );
            })}
          </div>
        )}

        {errorMsg && (
          <div className="p-2.5 rounded-lg bg-rose-50 dark:bg-rose-950/20 border border-rose-100 dark:border-rose-900/40 text-rose-600 dark:text-rose-450 text-[10px] flex items-center gap-1.5">
            <AlertCircle size={14} />
            <span>{errorMsg}</span>
          </div>
        )}

        {!readOnly && activeStep.state === "waiting" && (
          <div className="flex justify-end pt-2">
            <button 
              type="submit" 
              className="sys-btn sys-btn--primary flex items-center justify-center gap-2 px-5 py-2 text-xs w-full sm:w-auto font-medium"
            >
              <Send size={14} />
              提交资料并继续
            </button>
          </div>
        )}
      </form>
    </div>
  );
}
