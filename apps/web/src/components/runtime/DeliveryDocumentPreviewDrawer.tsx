import { Drawer } from "antd";
import { Download, FileText } from "lucide-react";
import { resolveDeliveryDocumentKind } from "../../utils/deliveryDocumentPreview";
import { ExcelWorkbookPreviewDrawer } from "./ExcelWorkbookPreviewDrawer";
import { WordDocumentPreviewDrawer } from "./WordDocumentPreviewDrawer";
import "./DocumentPreviewDrawer.css";

interface DeliveryDocumentPreviewDrawerProps {
  open: boolean;
  tenantId: string;
  token: string;
  recordId: string;
  fileName: string;
  deliveryType?: string;
  downloading: boolean;
  onClose: () => void;
  onDownload: () => void;
}

export function DeliveryDocumentPreviewDrawer({
  open,
  tenantId,
  token,
  recordId,
  fileName,
  deliveryType,
  downloading,
  onClose,
  onDownload,
}: DeliveryDocumentPreviewDrawerProps) {
  const documentKind = resolveDeliveryDocumentKind({ fileName, deliveryType });

  if (documentKind === "excel") {
    return (
      <ExcelWorkbookPreviewDrawer
        open={open}
        tenantId={tenantId}
        token={token}
        recordId={recordId}
        fileName={fileName}
        downloading={downloading}
        onClose={onClose}
        onDownload={onDownload}
      />
    );
  }

  if (documentKind === "word") {
    return (
      <WordDocumentPreviewDrawer
        open={open}
        tenantId={tenantId}
        token={token}
        recordId={recordId}
        fileName={fileName}
        downloading={downloading}
        onClose={onClose}
        onDownload={onDownload}
      />
    );
  }

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
        <div className="agent-document-preview-drawer__state agent-document-preview-drawer__state--error" role="alert">
          <FileText size={36} />
          <strong>暂不支持在线预览</strong>
          <span>当前交付文件格式暂不支持浏览器内预览，请下载后在本地打开。</span>
        </div>
      </div>
    </Drawer>
  );
}
