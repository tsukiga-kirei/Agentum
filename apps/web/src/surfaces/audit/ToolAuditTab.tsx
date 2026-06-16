import { useEffect, useState } from "react";
import { Empty, Pagination, message, Tag, Select, Segmented } from "antd";
import { Search, Info, Cpu, Sparkles, ChevronDown, ChevronUp, AlertCircle, Clock, Activity } from "lucide-react";
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

  const selectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
  const selectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

  const toolTypeOptions: { value: "mcp" | "model"; label: React.ReactNode }[] = [
    {
      value: "mcp",
      label: (
        <span className="login-portal-option">
          <Cpu className="login-portal-option-icon" aria-hidden="true" />
          <span>MCP 工具调用</span>
        </span>
      )
    },
    {
      value: "model",
      label: (
        <span className="login-portal-option">
          <Sparkles className="login-portal-option-icon" aria-hidden="true" />
          <span>模型推理调用</span>
        </span>
      )
    }
  ];

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
        <div className="system-mgmt-segmented-scroll">
          <Segmented<"mcp" | "model">
            value={toolType}
            onChange={(value) => setToolType(value)}
            options={toolTypeOptions}
            className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
          />
        </div>

        <div className="workflow-library-toolbar-actions w-full md:w-auto">
          {/* 关键字搜索 */}
          <label className="workflow-definition-search w-80 shrink-0">
            <Search className="h-[18px] w-[18px]" aria-hidden="true" />
            <input
              type="text"
              placeholder={toolType === "mcp" ? "搜索工具名称/能力编码..." : "搜索模型名称..."}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </label>
          {/* 状态筛选 */}
          <Select
            value={status}
            onChange={(value) => setStatus(value)}
            className="agent-admin-select w-36"
            classNames={selectClassNames}
            suffixIcon={selectSuffixIcon}
            prefix={<Activity className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
            options={[
              { value: "", label: "全部状态" },
              { value: toolType === "model" ? "completed" : "success", label: "成功" },
              { value: "failed", label: "失败" }
            ]}
          />
        </div>
      </div>

      {logs.length === 0 ? (
        <div className="py-16 flex items-center justify-center">
          <Empty description={`暂无${toolType === "mcp" ? "MCP" : "模型"}审计记录`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="workbench-task-center-list">
            {logs.map((log, index) => {
              const isExpanded = expandedLogId === log.id;
              const isSuccess = log.status === "success" || log.status === "completed";
              return (
                <div
                  key={log.id}
                  className="sys-preview-item sys-card-enter !flex !flex-col !items-stretch !p-0 overflow-hidden w-full"
                  style={{ animationDelay: `${index * 40}ms` }}
                >
                  <div
                    onClick={() => toggleExpand(log.id)}
                    className="w-full flex items-center justify-between p-4 cursor-pointer hover:bg-zinc-50/50 dark:hover:bg-zinc-800/30 transition-colors"
                  >
                    <div className="sys-preview-item-left">
                      <span className={`sys-preview-item-icon ${
                        toolType === "mcp" 
                          ? "bg-blue-50 dark:bg-blue-950/30 text-blue-600 dark:text-blue-400" 
                          : "bg-yellow-50 dark:bg-yellow-950/30 text-yellow-600 dark:text-yellow-400"
                      }`}>
                        {toolType === "mcp" ? <Cpu size={16} /> : <Sparkles size={16} />}
                      </span>
                      <div className="min-w-0">
                        <div className="font-semibold text-zinc-800 dark:text-zinc-100 text-sm flex items-center gap-2">
                          {log.toolName}
                          {isSuccess ? (
                            <Tag color="success" className="text-2xs font-normal">成功</Tag>
                          ) : (
                            <Tag color="error" className="text-2xs font-normal">失败</Tag>
                          )}
                        </div>
                        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-zinc-400 mt-1">
                          <span>所属运行: {log.callerName}</span>
                          <span>触发时间: {formatDate(log.createdAt)}</span>
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
                    <div className="w-full px-4 pb-4 border-t border-dashed border-zinc-100 dark:border-zinc-800 pt-3 bg-zinc-50/30 dark:bg-zinc-950/10 space-y-4">
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
