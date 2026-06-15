import React, { useMemo } from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import type { WorkbenchRunDetail } from "../../types/workbench";
import { Package, FileText } from "lucide-react";
import { MarkdownRenderer } from "./MarkdownRenderer";

interface DeliveryPreviewPanelProps {
  activeStep: RuntimePreviewStep;
  runDetail: WorkbenchRunDetail;
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "object") {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return "[Object]";
    }
  }
  return String(value);
}

function collectRunVariables(run: WorkbenchRunDetail): Record<string, string> {
  const variables: Record<string, string> = {};
  run.nodes.forEach((node) => {
    Object.entries(node.outputs ?? {}).forEach(([key, value]) => {
      variables[key] = stringifyValue(value);
    });
    Object.entries(node.inputs ?? {}).forEach(([key, value]) => {
      if (!variables[key]) {
        variables[key] = stringifyValue(value);
      }
    });
  });
  return variables;
}

function renderTemplate(template: string, variables: Record<string, string>): string {
  let result = template;
  Object.entries(variables).forEach(([key, value]) => {
    result = result.split(`{{${key}}}`).join(value);
  });
  return result;
}

export function DeliveryPreviewPanel({ activeStep, runDetail }: DeliveryPreviewPanelProps) {
  const config = activeStep.configSnapshot ?? {};
  const isDirectDelivery =
    stringifyValue(config.deliveryMode) === "direct"
    || stringifyValue(config.deliveryType) === "direct";
  const isWordDelivery =
    !isDirectDelivery && (
      stringifyValue(config.deliveryType) === "word_document"
      || stringifyValue(config.documentKind) === "word"
    );

  const previewContent = useMemo(() => {
    const template = stringifyValue(config.deliveryContent)
      || stringifyValue(config.markdownContent)
      || stringifyValue(config.deliveryTarget)
      || "请配置交付内容模板。";
    return renderTemplate(template, collectRunVariables(runDetail));
  }, [config.deliveryContent, config.deliveryTarget, config.markdownContent, runDetail]);

  return (
    <div className="space-y-4 max-w-3xl mx-auto">
      <section className="bg-slate-50 dark:bg-slate-900/60 rounded-xl border border-slate-100 dark:border-slate-800 p-5">
        <div className="flex items-start gap-3">
          <Package className="text-emerald-500 shrink-0 mt-0.5" size={20} />
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">交付预览</h3>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
              {isWordDelivery
                ? "以下为按 Word 交付正文模板和当前变量渲染后的 Markdown 预览，确认无误后再执行生成 docx。"
                : isDirectDelivery
                  ? "以下为按直接交付内容模板和当前变量渲染后的预览，确认无误后再执行交付。"
                  : "以下为交付能力通道的配置摘要，确认后再执行归档。"}
            </p>
          </div>
        </div>
      </section>

      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
        <header className="px-5 py-3 border-b border-slate-100 dark:border-slate-800 flex items-center gap-2 bg-slate-50/50 dark:bg-slate-900/20">
          <FileText size={16} className="text-blue-500" />
          <span className="text-sm font-semibold text-slate-800 dark:text-slate-200">
            {isWordDelivery ? "Word 文档交付（正文模板）" : isDirectDelivery ? "直接交付（内容模板）" : "交付能力输出预览"}
          </span>
        </header>
        <div className="p-5 max-h-[420px] overflow-y-auto">
          <MarkdownRenderer content={previewContent} />
        </div>
      </section>
    </div>
  );
}
