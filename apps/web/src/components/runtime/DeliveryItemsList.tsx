import React, { useState } from "react";
import type { RuntimeDeliveryItem } from "../../types/runtime-types";
import { Copy, Download, Eye } from "lucide-react";
import { message } from "antd";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { copyTextToClipboard } from "../../utils/clipboard";

interface DeliveryItemsListProps {
  items: RuntimeDeliveryItem[];
  isFlowCompleted?: boolean;
  downloadingRecordId?: string;
  onPreviewDocument?: (recordId: string, fileName: string, deliveryType?: string) => void;
  onDownloadDocument?: (recordId: string) => void;
  emptyTitle?: string;
  emptyDescription?: string;
  pendingDescription?: string;
}

export function DeliveryItemsList({
  items,
  isFlowCompleted = true,
  downloadingRecordId = "",
  onPreviewDocument,
  onDownloadDocument,
  emptyTitle = "暂无已触发交付物",
  emptyDescription = "本次运行没有命中任何交付项，因此不会展示未触发的模板或通道。",
  pendingDescription = "交付已生成，待您在「当前处理」点击「确认完成」后归档展示。",
}: DeliveryItemsListProps) {
  const [copiedKey, setCopiedKey] = useState("");

  const handleCopy = async (item: RuntimeDeliveryItem) => {
    if (!item.content) {
      return;
    }
    try {
      await copyTextToClipboard(item.content);
      setCopiedKey(item.key);
      window.setTimeout(() => setCopiedKey(""), 2000);
    } catch (error) {
      // 交付内容可能包含业务敏感信息，日志不得输出原始正文。
      console.warn("[runtime] 交付内容复制失败", error);
      message.error("复制失败，请手动选择内容复制");
    }
  };

  if (items.length === 0) {
    return (
      <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 text-sm">
        <strong className="text-slate-800 dark:text-slate-200 font-medium block">{emptyTitle}</strong>
        <span className="text-slate-400 block mt-0.5 whitespace-pre-wrap">{emptyDescription}</span>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {items.map((item) => {
        const showDocumentActions = !!item.recordId && !!item.fileName && onPreviewDocument && onDownloadDocument;
        const statusLabel = isFlowCompleted ? item.status : "待确认";
        const statusClass = isFlowCompleted
          ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
          : "bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400";

        return (
          <div
            key={item.key}
            className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 overflow-hidden"
          >
            <div className="p-3 flex flex-wrap justify-between items-start gap-3 text-sm">
              <div className="min-w-0 flex-1">
                <strong className="text-slate-800 dark:text-slate-200 font-medium block">{item.name}</strong>
                {item.meta ? (
                  <span className="text-slate-400 block mt-0.5 break-all">{item.meta}</span>
                ) : null}
                {!isFlowCompleted ? (
                  <span className="text-slate-400 block mt-1 text-xs">{pendingDescription}</span>
                ) : null}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {item.kind === "direct" && item.content ? (
                  <button
                    type="button"
                    onClick={() => void handleCopy(item)}
                    className="agent-button h-8 px-3 text-xs"
                  >
                    <Copy size={14} />
                    {copiedKey === item.key ? "已复制" : "复制内容"}
                  </button>
                ) : null}
                {showDocumentActions ? (
                  <>
                    <button
                      type="button"
                      onClick={() => onPreviewDocument(item.recordId!, item.fileName!, item.deliveryType)}
                      className="agent-button h-8 px-3 text-xs"
                    >
                      <Eye size={14} />
                      预览文档
                    </button>
                    <button
                      type="button"
                      onClick={() => onDownloadDocument(item.recordId!)}
                      disabled={downloadingRecordId === item.recordId}
                      className="agent-button h-8 px-3 text-xs"
                    >
                      <Download size={14} />
                      {downloadingRecordId === item.recordId ? "下载中" : "下载文档"}
                    </button>
                  </>
                ) : null}
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${statusClass}`}>
                  {statusLabel}
                </span>
              </div>
            </div>
            {item.kind === "direct" && item.content ? (
              <div className="px-3 pb-3">
                <div className="bg-white dark:bg-slate-950 rounded-lg border border-slate-100 dark:border-slate-800 p-4 max-h-[360px] overflow-y-auto">
                  <MarkdownRenderer content={item.content} />
                </div>
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
