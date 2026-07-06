import { useEffect, useMemo, useState } from "react";
import { Drawer, Spin, Tabs } from "antd";
import { Download, FileSpreadsheet, RefreshCw } from "lucide-react";
import type { WorkBook } from "xlsx";
import { workbenchApi } from "../../services/apiClient";
import "./DocumentPreviewDrawer.css";

interface ExcelWorkbookPreviewDrawerProps {
  open: boolean;
  tenantId: string;
  token: string;
  recordId: string;
  fileName: string;
  downloading: boolean;
  onClose: () => void;
  onDownload: () => void;
}

type ExcelPreviewSheet = {
  key: string;
  name: string;
  html: string;
  rowCount: number;
};

function renderWorkbookSheets(workbook: WorkBook, sheetToHtml: typeof import("xlsx").utils.sheet_to_html, decodeRange: typeof import("xlsx").utils.decode_range): ExcelPreviewSheet[] {
  return workbook.SheetNames.map((sheetName, index) => {
    const worksheet = workbook.Sheets[sheetName];
    if (!worksheet) {
      return {
        key: `sheet-${index}`,
        name: sheetName,
        html: "",
        rowCount: 0,
      };
    }

    const ref = worksheet["!ref"];
    const rowCount = ref ? decodeRange(ref).e.r + 1 : 0;
    const html = rowCount > 0
      ? sheetToHtml(worksheet, { id: `sheet-${index}`, editable: false })
      : "";

    return {
      key: `sheet-${index}`,
      name: sheetName,
      html,
      rowCount,
    };
  });
}

export function ExcelWorkbookPreviewDrawer({
  open,
  tenantId,
  token,
  recordId,
  fileName,
  downloading,
  onClose,
  onDownload,
}: ExcelWorkbookPreviewDrawerProps) {
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);
  const [sheets, setSheets] = useState<ExcelPreviewSheet[]>([]);
  const [activeSheetKey, setActiveSheetKey] = useState("");

  useEffect(() => {
    if (!open || !recordId) {
      return;
    }

    let disposed = false;
    setLoading(true);
    setErrorMessage("");
    setSheets([]);
    setActiveSheetKey("");

    async function renderWorkbook() {
      try {
        // 预览与下载复用同一受保护接口，确保租户边界、文件过期状态和工作台权限始终由后端复核。
        const [{ blob }, XLSX] = await Promise.all([
          workbenchApi.downloadDeliveryRecord(tenantId, token, recordId),
          import("xlsx"),
        ]);
        if (disposed) {
          return;
        }

        const buffer = await blob.arrayBuffer();
        const workbook = XLSX.read(buffer, {
          type: "array",
          cellDates: true,
          dense: false,
        });
        const nextSheets = renderWorkbookSheets(workbook, XLSX.utils.sheet_to_html, XLSX.utils.decode_range);
        if (disposed) {
          return;
        }
        if (nextSheets.length === 0) {
          throw new Error("Excel 工作簿中没有可预览的 Sheet");
        }

        setSheets(nextSheets);
        setActiveSheetKey(nextSheets[0]?.key ?? "");
      } catch (error) {
        if (disposed) {
          return;
        }
        console.warn("[runtime] Excel 交付文档预览失败", {
          recordId,
          message: error instanceof Error ? error.message : "unknown",
        });
        setErrorMessage(error instanceof Error ? error.message : "Excel 文档预览失败，请稍后重试");
      } finally {
        if (!disposed) {
          setLoading(false);
        }
      }
    }

    void renderWorkbook();
    return () => {
      disposed = true;
    };
  }, [open, recordId, reloadVersion, tenantId, token]);

  const tabItems = useMemo(
    () => sheets.map((sheet) => ({
      key: sheet.key,
      label: sheet.name,
      children: (
        <div className="agent-document-preview-drawer__sheet-panel">
          {sheet.rowCount > 0 ? (
            <div
              className="agent-document-preview-drawer__sheet-table-wrap"
              // xlsx 仅将当前工作簿解析结果转成表格 HTML，不拼接外部输入。
              dangerouslySetInnerHTML={{ __html: sheet.html }}
            />
          ) : (
            <div className="agent-document-preview-drawer__sheet-empty">该 Sheet 暂无数据</div>
          )}
        </div>
      ),
    })),
    [sheets],
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size="min(960px, 92vw)"
      destroyOnHidden
      rootClassName="agent-document-preview-drawer"
      title={(
        <div className="agent-document-preview-drawer__title">
          <span className="agent-document-preview-drawer__title-icon"><FileSpreadsheet size={18} /></span>
          <span className="min-w-0">
            <strong>预览表格</strong>
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
          {downloading ? "下载中" : "下载表格"}
        </button>
      )}
    >
      <div className="agent-document-preview-drawer__body">
        {!loading && !errorMessage && sheets.length > 0 ? (
          sheets.length === 1 ? (
            tabItems[0]?.children
          ) : (
            <Tabs
              className="agent-document-preview-drawer__sheet-tabs"
              activeKey={activeSheetKey}
              items={tabItems}
              onChange={setActiveSheetKey}
            />
          )
        ) : null}
        {loading ? (
          <div className="agent-document-preview-drawer__state" aria-live="polite">
            <Spin size="large" />
            <span>正在加载 Excel 工作簿…</span>
          </div>
        ) : null}
        {!loading && errorMessage ? (
          <div className="agent-document-preview-drawer__state agent-document-preview-drawer__state--error" role="alert">
            <FileSpreadsheet size={36} />
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
