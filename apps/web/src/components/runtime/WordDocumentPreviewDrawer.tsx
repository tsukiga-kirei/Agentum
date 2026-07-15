import { useEffect, useRef, useState } from "react";
import { Drawer, Spin } from "antd";
import { Download, FileText, RefreshCw } from "lucide-react";
import { workbenchApi } from "../../services/apiClient";
import "./DocumentPreviewDrawer.css";

interface WordDocumentPreviewDrawerProps {
  open: boolean;
  tenantId?: string;
  token?: string;
  recordId?: string;
  fileName: string;
  loadDocument?: () => Promise<{ blob: Blob }>;
  downloading: boolean;
  onClose: () => void;
  onDownload: () => void;
}

export function WordDocumentPreviewDrawer({
  open,
  tenantId,
  token,
  recordId,
  fileName,
  loadDocument,
  downloading,
  onClose,
  onDownload,
}: WordDocumentPreviewDrawerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);

  useEffect(() => {
    if (!open || (!recordId && !loadDocument)) {
      return;
    }

    let disposed = false;
    const container = containerRef.current;
    if (container) {
      container.innerHTML = "";
    }
    setLoading(true);
    setErrorMessage("");

    async function renderDocument() {
      try {
        // 预览与下载复用同一受保护接口，确保租户边界、文件过期状态和工作台权限始终由后端复核。
        const [{ blob }, { renderAsync }] = await Promise.all([
          loadDocument ? loadDocument() : workbenchApi.downloadDeliveryRecord(tenantId!, token!, recordId!),
          import("docx-preview"),
        ]);
        if (disposed || !containerRef.current) {
          return;
        }
        await renderAsync(blob, containerRef.current, undefined, {
          className: "agent-word-preview",
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          ignoreFonts: false,
          breakPages: true,
          ignoreLastRenderedPageBreak: true,
          experimental: false,
          trimXmlDeclaration: true,
          useBase64URL: true,
        });
      } catch (error) {
        if (disposed) {
          return;
        }
        console.warn("[runtime] Word 交付文档预览失败", {
          recordId,
          message: error instanceof Error ? error.message : "unknown",
        });
        setErrorMessage(error instanceof Error ? error.message : "Word 文档预览失败，请稍后重试");
      } finally {
        if (!disposed) {
          setLoading(false);
        }
      }
    }

    void renderDocument();
    return () => {
      disposed = true;
      if (container) {
        container.innerHTML = "";
      }
    };
  }, [open, recordId, reloadVersion, tenantId, token, loadDocument]);

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size="min(960px, 92vw)"
      destroyOnHidden
      rootClassName="agent-document-preview-drawer"
      title={(
        <div className="agent-document-preview-drawer__title">
          <span className="agent-document-preview-drawer__title-icon"><FileText size={18} /></span>
          <span className="min-w-0">
            <strong>预览文档</strong>
            <small title={fileName}>{fileName}</small>
          </span>
        </div>
      )}
      extra={(
        <button
          type="button"
          className="agent-button h-8 px-3 text-xs"
          onClick={onDownload}
          disabled={downloading}
        >
          <Download size={14} />
          {downloading ? "下载中" : "下载文档"}
        </button>
      )}
    >
      <div className="agent-document-preview-drawer__body">
        <div ref={containerRef} className="agent-document-preview-drawer__document" />
        {loading ? (
          <div className="agent-document-preview-drawer__state" aria-live="polite">
            <Spin size="large" />
            <span>正在加载 Word 文档…</span>
          </div>
        ) : null}
        {!loading && errorMessage ? (
          <div className="agent-document-preview-drawer__state agent-document-preview-drawer__state--error" role="alert">
            <FileText size={36} />
            <strong>文档预览失败</strong>
            <span>{errorMessage}</span>
            <button type="button" className="agent-button h-9 px-4 text-sm" onClick={() => setReloadVersion((value) => value + 1)}>
              <RefreshCw size={15} />
              重新加载
            </button>
          </div>
        ) : null}
      </div>
    </Drawer>
  );
}
