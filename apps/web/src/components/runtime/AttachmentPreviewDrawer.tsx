import { useCallback, useEffect, useMemo, useState } from "react";
import { Drawer, Spin } from "antd";
import { Download, FileText, Image as ImageIcon, RefreshCw } from "lucide-react";
import type { InputAttachmentRow } from "../../types/workbench";
import { workbenchApi } from "../../services/apiClient";
import { ExcelWorkbookPreviewDrawer } from "./ExcelWorkbookPreviewDrawer";
import { WordDocumentPreviewDrawer } from "./WordDocumentPreviewDrawer";
import { MarkdownRenderer } from "./MarkdownRenderer";
import "./DocumentPreviewDrawer.css";

interface AttachmentPreviewDrawerProps {
  open: boolean;
  tenantId: string;
  token: string;
  runId: string;
  nodeRunId: string;
  attachment: InputAttachmentRow;
  downloading: boolean;
  onClose: () => void;
  onDownload: () => void;
}

export function AttachmentPreviewDrawer(props: AttachmentPreviewDrawerProps) {
  const loadOriginal = useCallback(
    () => workbenchApi.downloadInputAttachment(props.tenantId, props.token, props.runId, props.nodeRunId, props.attachment.id),
    [props.attachment.id, props.nodeRunId, props.runId, props.tenantId, props.token],
  );
  const extension = props.attachment.extension.toLowerCase();

  if (["xlsx", "xls"].includes(extension)) {
    return <ExcelWorkbookPreviewDrawer {...props} fileName={props.attachment.fileName} loadDocument={loadOriginal} />;
  }
  if (extension === "docx") {
    return <WordDocumentPreviewDrawer {...props} fileName={props.attachment.fileName} loadDocument={loadOriginal} />;
  }
  return <GenericAttachmentPreviewDrawer {...props} loadOriginal={loadOriginal} />;
}

function GenericAttachmentPreviewDrawer({
  open,
  tenantId,
  token,
  runId,
  nodeRunId,
  attachment,
  downloading,
  onClose,
  onDownload,
  loadOriginal,
}: AttachmentPreviewDrawerProps & { loadOriginal: () => Promise<{ blob: Blob }> }) {
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);
  const [objectUrl, setObjectUrl] = useState("");
  const [textContent, setTextContent] = useState("");
  const extension = attachment.extension.toLowerCase();
  const mode = useMemo(() => {
    if (extension === "pdf") return "pdf";
    if (["png", "jpg", "jpeg", "gif", "bmp", "webp"].includes(extension)) return "image";
    if (["txt", "md", "csv"].includes(extension)) return "text";
    if (attachment.status === "ready" && attachment.parseResultId) return "parsed";
    return "unsupported";
  }, [attachment.parseResultId, attachment.status, extension]);

  useEffect(() => {
    if (!open || mode === "unsupported") return;
    let disposed = false;
    let nextObjectUrl = "";
    setLoading(true);
    setErrorMessage("");
    setObjectUrl("");
    setTextContent("");

    async function load() {
      try {
        if (mode === "parsed") {
          const { blob } = await workbenchApi.previewInputAttachmentContent(tenantId, token, runId, nodeRunId, attachment.id);
          const text = await blob.text();
          if (!disposed) setTextContent(text);
          return;
        }
        const { blob } = await loadOriginal();
        if (mode === "text") {
          // 文本预览限制浏览器端读取量，完整原件仍可下载，避免超大纯文本阻塞页面。
          const text = await blob.slice(0, 2 * 1024 * 1024).text();
          if (!disposed) setTextContent(text);
          return;
        }
        nextObjectUrl = URL.createObjectURL(blob);
        if (!disposed) setObjectUrl(nextObjectUrl);
      } catch (error) {
        if (!disposed) {
          console.warn("[runtime] 附件预览失败", { attachmentId: attachment.id, message: error instanceof Error ? error.message : "unknown" });
          setErrorMessage(error instanceof Error ? error.message : "附件预览失败，请稍后重试");
        }
      } finally {
        if (!disposed) setLoading(false);
      }
    }
    void load();
    return () => {
      disposed = true;
      if (nextObjectUrl) URL.revokeObjectURL(nextObjectUrl);
    };
  }, [attachment.id, loadOriginal, mode, nodeRunId, open, reloadVersion, runId, tenantId, token]);

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size="min(960px, 92vw)"
      destroyOnHidden
      rootClassName="agent-document-preview-drawer"
      title={<div className="agent-document-preview-drawer__title"><span className="agent-document-preview-drawer__title-icon">{mode === "image" ? <ImageIcon size={18} /> : <FileText size={18} />}</span><span className="min-w-0"><strong>预览附件</strong><small title={attachment.fileName}>{attachment.fileName}</small></span></div>}
      extra={<button type="button" className="agent-button h-8 px-3 text-xs" onClick={onDownload} disabled={downloading}><Download size={14} />{downloading ? "下载中" : "下载原件"}</button>}
    >
      <div className="agent-document-preview-drawer__body">
        {mode === "pdf" && objectUrl ? <iframe className="h-full min-h-[70vh] w-full border-0" src={objectUrl} title={attachment.fileName} /> : null}
        {mode === "image" && objectUrl ? <div className="flex min-h-[60vh] items-center justify-center p-6"><img src={objectUrl} alt={attachment.fileName} className="max-h-[75vh] max-w-full object-contain" /></div> : null}
        {mode === "text" && textContent ? <pre className="m-0 min-h-[60vh] whitespace-pre-wrap break-words p-6 text-sm leading-7 text-[var(--color-text-primary)]">{textContent}</pre> : null}
        {mode === "parsed" && textContent ? <div className="p-6"><MarkdownRenderer content={textContent} /></div> : null}
        {loading ? <div className="agent-document-preview-drawer__state"><Spin size="large" /><span>正在加载附件预览…</span></div> : null}
        {!loading && (errorMessage || mode === "unsupported") ? <div className="agent-document-preview-drawer__state agent-document-preview-drawer__state--error" role="alert"><FileText size={36} /><strong>{errorMessage ? "附件预览失败" : "暂不支持在线预览"}</strong><span>{errorMessage || "当前文件格式暂无可用的前端预览适配器，请下载后在本地打开。"}</span>{errorMessage ? <button type="button" className="agent-button h-9 px-4 text-sm" onClick={() => setReloadVersion((value) => value + 1)}><RefreshCw size={15} />重新加载</button> : null}</div> : null}
      </div>
    </Drawer>
  );
}
