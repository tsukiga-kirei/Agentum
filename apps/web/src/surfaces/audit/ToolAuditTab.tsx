import { useEffect, useState } from "react";
import { Empty, Pagination, message, Tag } from "antd";
import { Search, Info, Cpu, Sparkles, ChevronDown, ChevronUp, AlertCircle, Clock } from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditToolCall } from "../../types/audit";

interface ToolAuditTabProps {
  setLoading: (loading: boolean) => void;
}

export function ToolAuditTab({ setLoading }: ToolAuditTabProps) {
  const token = useAuthStore((s) => s.token) || "";
  const activeRole = useAuthStore((s) => s.activeRole);
  const user = useAuthStore((s) => s.user);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const themeMode = useAuthStore((s) => s.themeMode);

  const [logs, setLogs] = useState<AuditToolCall[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [toolType, setToolType] = useState<"mcp" | "model">("mcp");
  const [status, setStatus] = useState("");
  const [keyword, setKeyword] = useState("");

  const [expandedLogId, setExpandedLogId] = useState<string | null>(null);

  const fetchLogs = async (pageVal = page, sizeVal = size) => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const res = await auditApi.listToolCalls(
        tenantId, token, pageVal, sizeVal, "createdAt,desc", toolType, status, keyword
      );
      if (res) {
        setLogs(res.items);
        setTotal(res.total);
      }
    } catch (e: any) {
      console.error(e);
      message.error(e.message || "加载工具审计日志失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs(1);
    setPage(1);
    setExpandedLogId(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [toolType, status, keyword, tenantId]);

  const handlePageChange = (p: number, s: number) => {
    setPage(p);
    setSize(s);
    fetchLogs(p, s);
  };

  const toggleExpand = (id: string) => {
    setExpandedLogId(expandedLogId === id ? null : id);
  };

  const formatDate = (isoStr: string) => {
    return new Date(isoStr).toLocaleString("zh-CN", { hour12: false });
  };

  const formatJson = (val: any) => {
    if (typeof val === "string") {
      try {
        const parsed = JSON.parse(val);
        return JSON.stringify(parsed, null, 2);
      } catch {
        return val;
      }
    }
    return JSON.stringify(val, null, 2);
  };

  return (
    <div className="space-y-4">
      {/* 简洁平级工具栏，无灰色背景与边框 */}
      <div className="flex flex-col md:flex-row gap-3 items-center justify-between py-2 border-b border-zinc-100 dark:border-zinc-800">
        {/* 类型切换：模型调用或工具调用 */}
        <div className="flex bg-zinc-100 dark:bg-zinc-800 p-0.5 rounded-lg text-xs font-semibold">
          <button
            onClick={() => setToolType("mcp")}
            className={`px-3 py-1.5 rounded-md transition-colors ${
              toolType === "mcp"
                ? "bg-white dark:bg-zinc-900 text-zinc-800 dark:text-zinc-100 shadow-sm"
                : "text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-300"
            }`}
          >
            MCP 工具调用
          </button>
          <button
            onClick={() => setToolType("model")}
            className={`px-3 py-1.5 rounded-md transition-colors ${
              toolType === "model"
                ? "bg-white dark:bg-zinc-900 text-zinc-800 dark:text-zinc-100 shadow-sm"
                : "text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-300"
            }`}
          >
            模型推理调用
          </button>
        </div>

        <div className="flex items-center gap-3 w-full md:w-auto justify-end">
          {/* 关键字搜索 */}
          <div className="relative w-64">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400" size={16} />
            <input
              type="text"
              placeholder={toolType === "mcp" ? "搜索工具名称/能力编码..." : "搜索模型名称..."}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              className="sys-input pl-9 w-full"
            />
          </div>
          {/* 状态筛选 */}
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="sys-input w-32"
          >
            <option value="">全部状态</option>
            <option value={toolType === "model" ? "completed" : "success"}>成功</option>
            <option value="failed">失败</option>
          </select>
        </div>
      </div>

      {/* 列表渲染 */}
      {logs.length === 0 ? (
        <div className="py-12 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-xl flex items-center justify-center">
          <Empty description={`暂无${toolType === "mcp" ? "MCP" : "模型"}审计记录`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="overflow-hidden bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-xl divide-y divide-zinc-100 dark:divide-zinc-800">
            {logs.map((log) => {
              const isExpanded = expandedLogId === log.id;
              const isSuccess = log.status === "success" || log.status === "completed";
              return (
                <div key={log.id} className="transition-colors hover:bg-zinc-50/20 dark:hover:bg-zinc-800/10">
                  <div
                    onClick={() => toggleExpand(log.id)}
                    className="flex items-center justify-between p-4 cursor-pointer"
                  >
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded-lg ${
                        toolType === "mcp" 
                          ? "bg-blue-50 dark:bg-blue-950/30 text-blue-600 dark:text-blue-400" 
                          : "bg-yellow-50 dark:bg-yellow-950/30 text-yellow-600 dark:text-yellow-400"
                      }`}>
                        {toolType === "mcp" ? <Cpu size={16} /> : <Sparkles size={16} />}
                      </div>
                      <div>
                        <div className="font-semibold text-zinc-800 dark:text-zinc-100 text-sm flex items-center gap-2">
                          {log.toolName}
                          {isSuccess ? (
                            <Tag color="success" className="text-2xs font-normal">成功</Tag>
                          ) : (
                            <Tag color="error" className="text-2xs font-normal">失败</Tag>
                          )}
                        </div>
                        <div className="text-xs text-zinc-400 mt-1">
                          所属运行: {log.callerName} · 时间: {formatDate(log.createdAt)}
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-4 text-right">
                      <div className="text-xs text-zinc-400 hidden sm:block">
                        <div className="flex items-center gap-1">
                          <Clock size={12} /> {log.latencyMs ? `${log.latencyMs} ms` : "—"}
                        </div>
                      </div>
                      <div className="text-zinc-400">
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </div>
                  </div>

                  {/* 展开的详情日志 */}
                  {isExpanded && (
                    <div className="px-4 pb-4 border-t border-dashed border-zinc-100 dark:border-zinc-800 pt-3 bg-zinc-50/30 dark:bg-zinc-950/10 space-y-4">
                      {/* 参数与返回值 */}
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div className="space-y-1.5">
                          <div className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">请求参数 (Request Payload)</div>
                          <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto max-h-60 text-zinc-700 dark:text-zinc-300">
                            {formatJson(log.requestPayload)}
                          </pre>
                        </div>
                        <div className="space-y-1.5">
                          <div className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">响应内容 (Response Outcome)</div>
                          <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto max-h-60 text-zinc-700 dark:text-zinc-300">
                            {formatJson(log.responsePayload)}
                          </pre>
                        </div>
                      </div>

                      {/* 错误提示 */}
                      {log.errorMessage && (
                        <div className="p-3 bg-red-50 dark:bg-red-950/20 border border-red-100 dark:border-red-950/60 rounded-lg flex items-start gap-2 text-xs text-red-600">
                          <AlertCircle size={14} className="mt-0.5 shrink-0" />
                          <div>
                            <div className="font-semibold">失败原因</div>
                            <div>{log.errorMessage}</div>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* 分页组件 */}
          <div className="flex justify-end pt-2">
            <Pagination
              current={page}
              pageSize={size}
              total={total}
              onChange={handlePageChange}
              showSizeChanger
              pageSizeOptions={["10", "20", "50"]}
              className={themeMode === "dark" ? "agent-pagination--dark" : ""}
            />
          </div>
        </div>
      )}
    </div>
  );
}
