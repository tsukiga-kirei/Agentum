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

function readRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function readItems(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
    : [];
}

function hasValue(value: string | undefined): boolean {
  return value !== undefined && value.trim() !== "";
}

function triggerMatched(item: Record<string, unknown>, variables: Record<string, string>, policy: string): boolean {
  if (policy !== "conditional") {
    return true;
  }
  const rule = readRecord(item.triggerRule);
  const type = stringifyValue(rule.type) || "always";
  const variableName = stringifyValue(rule.variableName);
  if (type === "cluster_agent_matched") {
    return hasValue(variables[variableName]);
  }
  if (type === "input_field_equals") {
    return stringifyValue(variables[variableName]).trim() === stringifyValue(rule.expectedValue).trim();
  }
  if (type === "agent_output_exists") {
    return hasValue(variables[variableName]);
  }
  return true;
}

export function DeliveryPreviewPanel({ activeStep, runDetail }: DeliveryPreviewPanelProps) {
  const config = activeStep.configSnapshot ?? {};
  const isDirectDelivery =
    stringifyValue(config.deliveryMode) === "direct"
    || stringifyValue(config.deliveryType) === "direct";
  const isMultipleDelivery = stringifyValue(config.deliveryConfigMode) === "multiple";
  const isWordDelivery =
    !isMultipleDelivery && !isDirectDelivery && (
      stringifyValue(config.deliveryType) === "word_document"
      || stringifyValue(config.documentKind) === "word"
    );

  const variables = useMemo(() => collectRunVariables(runDetail), [runDetail]);
  const multiplePreviews = useMemo(() => {
    if (!isMultipleDelivery) {
      return [];
    }
    const policy = stringifyValue(config.deliveryExecutionPolicy) || "all";
    return readItems(config.deliveryItems)
      .filter((item) => item.enabled !== false)
      .filter((item) => triggerMatched(item, variables, policy))
      .map((item, index) => {
        const itemConfig = readRecord(item.config);
        const template = stringifyValue(itemConfig.deliveryContent)
          || stringifyValue(itemConfig.markdownContent)
          || stringifyValue(itemConfig.deliveryTarget)
          || "请配置交付内容模板。";
        return {
          name: stringifyValue(item.name) || `交付项 ${index + 1}`,
          content: renderTemplate(template, variables),
          word: stringifyValue(itemConfig.deliveryType) === "word_document" || stringifyValue(itemConfig.documentKind) === "word",
        };
      });
  }, [config.deliveryExecutionPolicy, config.deliveryItems, isMultipleDelivery, variables]);

  const previewContent = useMemo(() => {
    const template = stringifyValue(config.deliveryContent)
      || stringifyValue(config.markdownContent)
      || stringifyValue(config.deliveryTarget)
      || "请配置交付内容模板。";
    return renderTemplate(template, variables);
  }, [config.deliveryContent, config.deliveryTarget, config.markdownContent, variables]);

  return (
    <div className="space-y-4 max-w-3xl mx-auto">
      <section className="bg-slate-50 dark:bg-slate-900/60 rounded-xl border border-slate-100 dark:border-slate-800 p-5">
        <div className="flex items-start gap-3">
          <Package className="text-emerald-500 shrink-0 mt-0.5" size={20} />
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">交付预览</h3>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
              {isMultipleDelivery
                ? "以下只展示当前变量下会触发的交付项。未触发的交付项不会生成交付物，也不会出现在最终产品交付页。"
                : isWordDelivery
                ? "以下为按 Word 交付正文模板和当前变量渲染后的 Markdown 预览，确认无误后再执行生成 docx。"
                : isDirectDelivery
                  ? "以下为按直接交付内容模板和当前变量渲染后的预览，确认无误后再执行交付。"
                  : "以下为交付能力通道的配置摘要，确认后再执行归档。"}
            </p>
          </div>
        </div>
      </section>

      {isMultipleDelivery ? (
        <div className="space-y-3">
          {multiplePreviews.length === 0 ? (
            <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 text-center text-sm text-slate-400">
              当前变量没有命中任何交付项。
            </section>
          ) : multiplePreviews.map((item) => (
            <section key={item.name} className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
              <header className="px-5 py-3 border-b border-slate-100 dark:border-slate-800 flex items-center gap-2 bg-slate-50/50 dark:bg-slate-900/20">
                <FileText size={16} className="text-blue-500" />
                <span className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                  {item.name}（{item.word ? "Word 正文模板" : "交付内容模板"}）
                </span>
              </header>
              <div className="p-5 max-h-[360px] overflow-y-auto">
                <MarkdownRenderer content={item.content} />
              </div>
            </section>
          ))}
        </div>
      ) : (
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
      )}
    </div>
  );
}
