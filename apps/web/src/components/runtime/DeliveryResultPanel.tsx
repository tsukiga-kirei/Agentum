import React, { useMemo } from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { CheckCircle2, Package, Send } from "lucide-react";
import { resolveDeliveryItems } from "../../utils/deliveryContent";
import { DeliveryItemsList } from "./DeliveryItemsList";

interface DeliveryResultPanelProps {
  activeStep: RuntimePreviewStep;
}

export function DeliveryResultPanel({ activeStep }: DeliveryResultPanelProps) {
  const deliveryItems = useMemo(() => resolveDeliveryItems(activeStep), [activeStep]);
  const summary = activeStep.outputs?.find((field) => field.label === "summary")?.value;

  return (
    <div className="space-y-4 max-w-3xl mx-auto">
      <section className="bg-emerald-50/70 dark:bg-emerald-950/20 rounded-xl border border-emerald-100 dark:border-emerald-900/40 p-5">
        <div className="flex items-start gap-3">
          <CheckCircle2 className="text-emerald-500 shrink-0 mt-0.5" size={20} />
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">交付结果预览</h3>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
              交付步骤已执行完成。请核对下方各交付项内容，确认无误后点击底部「确认完成」结束本任务。
            </p>
            {summary ? (
              <p className="text-xs text-emerald-700 dark:text-emerald-400 mt-2 font-medium">{summary}</p>
            ) : null}
          </div>
        </div>
      </section>

      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 space-y-3">
        <h4 className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
          <Send size={16} className="text-emerald-500" /> 交付项内容
        </h4>
        {deliveryItems.length === 0 ? (
          <div className="text-center py-10 text-slate-400 text-sm">
            <Package size={24} className="mx-auto mb-2 opacity-60" />
            暂无可展示的交付项，请检查交付节点输出。
          </div>
        ) : (
          <DeliveryItemsList
            items={deliveryItems}
            isFlowCompleted={false}
            emptyDescription="暂无可展示的交付项，请检查交付节点输出。"
          />
        )}
      </section>
    </div>
  );
}
