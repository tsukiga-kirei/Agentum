import React, { useMemo } from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { CheckCircle2, Copy, FileText, Package } from "lucide-react";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { resolveDeliveryDisplayContent } from "../../utils/deliveryContent";

interface DeliveryResultPanelProps {
  activeStep: RuntimePreviewStep;
}

export function DeliveryResultPanel({ activeStep }: DeliveryResultPanelProps) {
  const deliveryContent = useMemo(() => resolveDeliveryDisplayContent(activeStep), [activeStep]);
  const summary = activeStep.outputs?.find((field) => field.label === "summary")?.value;
  const [copied, setCopied] = React.useState(false);

  const handleCopy = () => {
    if (!deliveryContent) {
      return;
    }
    navigator.clipboard.writeText(deliveryContent);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-4 max-w-3xl mx-auto">
      <section className="bg-emerald-50/70 dark:bg-emerald-950/20 rounded-xl border border-emerald-100 dark:border-emerald-900/40 p-5">
        <div className="flex items-start gap-3">
          <CheckCircle2 className="text-emerald-500 shrink-0 mt-0.5" size={20} />
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">交付结果预览</h3>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
              交付步骤已执行完成。请核对下方内容，确认无误后点击底部「确认完成」结束本任务。
            </p>
            {summary ? (
              <p className="text-xs text-emerald-700 dark:text-emerald-400 mt-2 font-medium">{summary}</p>
            ) : null}
          </div>
        </div>
      </section>

      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
        <header className="px-5 py-3 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between bg-slate-50/50 dark:bg-slate-900/20">
          <div className="flex items-center gap-2 min-w-0">
            <FileText size={16} className="text-blue-500 shrink-0" />
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-200 truncate">
              交付执行结果
            </span>
          </div>
          {deliveryContent ? (
            <button
              type="button"
              onClick={handleCopy}
              className="text-xs font-semibold text-blue-500 hover:text-blue-650 dark:text-blue-400 flex items-center gap-1 shrink-0"
            >
              <Copy size={14} />
              {copied ? "已复制" : "复制内容"}
            </button>
          ) : null}
        </header>
        <div className="p-5 max-h-[480px] overflow-y-auto">
          {deliveryContent ? (
            <MarkdownRenderer content={deliveryContent} />
          ) : (
            <div className="text-center py-10 text-slate-400 text-sm">
              <Package size={24} className="mx-auto mb-2 opacity-60" />
              暂无可展示的交付摘要，请检查交付节点输出。
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
