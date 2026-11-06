import { useState } from "react";
import { App } from "antd";
import { Download, Table2 } from "lucide-react";
import { useAuthStore } from "../../stores/authStore";
import { workbenchApi } from "../../services/apiClient";
import { downloadTemplateFile } from "../../utils/deliveryTemplateFile";

type Result = { datasetId: string; runId: string; datasetKey: string; rowCount: number; fields?: string[]; preview?: Record<string, unknown>[] };
export function parseMcpResult(detail?: string): Result | null {
  if (!detail) return null;
  try {
    const value = JSON.parse(detail);
    return value && typeof value.datasetId === "string" && typeof value.runId === "string" && typeof value.rowCount === "number" ? value : null;
  } catch { return null; }
}
/** 会话只渲染受限预览；完整明细通过流程授权下载，与模型可见字段限制分开。 */
export function McpResultView({ result }: { result: Result }) {
  const { message } = App.useApp();
  const token = useAuthStore(state => state.token);
  const tenantId = useAuthStore(state => state.activeRole?.tenantId || state.user?.tenantId || "");
  const [busy, setBusy] = useState(false);
  const fields = (result.fields ?? []).slice(0, 10);
  const rows = (result.preview ?? []).slice(0, 100);
  return <div className="mcp-result-view"><div className="mcp-result-heading"><div><strong><Table2 size={15} />已保存 {result.rowCount.toLocaleString()} 条记录</strong><span>{result.datasetKey} · 完整结果随本次流程保存</span></div><button type="button" className="sys-btn sys-btn--default sys-btn--sm" disabled={busy || !token || !tenantId} onClick={async () => {
    if (!token || !tenantId) return;
    setBusy(true);
    try { downloadTemplateFile(await workbenchApi.downloadMcpResult(tenantId, token, result.runId, result.datasetId)); }
    catch (error) { void message.error(error instanceof Error ? error.message : "完整结果下载失败"); }
    finally { setBusy(false); }
  }}><Download size={14} />{busy ? "正在下载" : "下载完整数据"}</button></div>
    {rows.length > 0 && fields.length > 0 ? <div className="mcp-result-table"><table><thead><tr>{fields.map(field => <th key={field}>{field}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{fields.map(field => <td key={field}>{row[field] == null ? "" : String(row[field])}</td>)}</tr>)}</tbody></table></div> : <p>对话仅展示结果摘要，交付使用完整明细。</p>}
    {rows.length > 0 && <p>当前显示 {rows.length} 条预览，字段范围按工具规则设置。</p>}
  </div>;
}
