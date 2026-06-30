import { useEffect, useState } from "react";
import { Drawer, Empty, Pagination, message, Tag, Select, Segmented } from "antd";
import { Search, Cpu, Sparkles, ChevronDown, AlertCircle, Clock, Activity, Sigma, Eye, FileInput, FileOutput } from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditToolCall } from "../../types/audit";
import { getThemedDrawerRootClassName } from "../../utils/theme";

interface ToolAuditTabProps {
  setLoading: (loading: boolean) => void;
}

export function ToolAuditTab({ setLoading }: ToolAuditTabProps) {
  const token = useAuthStore((s) => s.token) || "";
  const activeRole = useAuthStore((s) => s.activeRole);
  const user = useAuthStore((s) => s.user);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const themeMode = useAuthStore((s) => s.themeMode);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode);

  const [logs, setLogs] = useState<AuditToolCall[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [toolType, setToolType] = useState<"mcp" | "model">("mcp");
  const [status, setStatus] = useState("");
  const [keyword, setKeyword] = useState("");

  const [selectedLog, setSelectedLog] = useState<AuditToolCall | null>(null);

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
    setSelectedLog(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [toolType, status, keyword, tenantId]);

  const handlePageChange = (p: number, s: number) => {
    setPage(p);
    setSize(s);
    fetchLogs(p, s);
  };

  const handleToolTypeChange = (value: "mcp" | "model") => {
    // MCP 与模型调用使用独立查询语义；切换类型时回到无状态过滤，避免沿用另一类调用的筛选值。
    setStatus("");
    setToolType(value);
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
            onChange={handleToolTypeChange}
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
              { value: "success", label: "成功" },
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
              const isSuccess = log.status === "success" || log.status === "completed";
              return (
                <button
                  type="button"
                  key={log.id}
                  onClick={() => setSelectedLog(log)}
                  className="sys-preview-item sys-card-enter w-full cursor-pointer text-left group"
                  style={{ animationDelay: `${index * 40}ms` }}
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
                        {log.tokenUsage ? <span>Token: {log.tokenUsage.totalTokens.toLocaleString("zh-CN")}</span> : null}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-4 text-right">
                    <div className="text-xs text-zinc-400 hidden sm:block">
                      <div className="flex items-center gap-1">
                        <Clock size={12} /> {log.latencyMs != null ? `${log.latencyMs} ms` : "—"}
                      </div>
                    </div>
                    <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-zinc-50 text-zinc-400 transition-colors group-hover:bg-primary-50 group-hover:text-primary-600 dark:bg-zinc-800 dark:group-hover:bg-primary-950/40">
                      <Eye size={16} aria-hidden="true" />
                    </span>
                  </div>
                </button>
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
              className="agent-admin-pagination"
            />
          </div>
        </div>
      )}

      <Drawer
        open={selectedLog != null}
        onClose={() => setSelectedLog(null)}
        width={760}
        title={(
          <div className="flex items-center gap-2">
            {toolType === "mcp" ? <Cpu className="text-blue-500" size={20} /> : <Sparkles className="text-amber-500" size={20} />}
            <span className="font-semibold text-zinc-800 dark:text-zinc-100">
              {toolType === "mcp" ? "MCP 工具调用详情" : "模型推理调用详情"}
            </span>
          </div>
        )}
        rootClassName={drawerRootClassName}
      >
        {selectedLog ? (
          <div className="sys-drawer-section sys-drawer-section-enter">
            <div className="rounded-2xl border border-violet-100 bg-gradient-to-br from-white to-violet-50/60 p-5 shadow-sm dark:border-violet-900/40 dark:from-zinc-900 dark:to-violet-950/20">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="mb-2 flex items-center gap-2">
                    <span className="break-all text-base font-semibold text-zinc-900 dark:text-zinc-100">{selectedLog.toolName}</span>
                    {selectedLog.status === "success" || selectedLog.status === "completed"
                      ? <Tag color="success">成功</Tag>
                      : <Tag color="error">失败</Tag>}
                  </div>
                  <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-zinc-500 dark:text-zinc-400">
                    <span>所属运行：{selectedLog.callerName}</span>
                    <span>触发时间：{formatDate(selectedLog.createdAt)}</span>
                    <span>耗时：{selectedLog.latencyMs != null ? `${selectedLog.latencyMs} ms` : "—"}</span>
                  </div>
                </div>
              </div>
              {selectedLog.tokenUsage ? (
                <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-violet-100 pt-4 text-xs text-zinc-500 dark:border-violet-900/40 dark:text-zinc-400">
                  <Sigma size={14} className="text-violet-500" />
                  <span className="font-semibold text-zinc-700 dark:text-zinc-200">总计 {selectedLog.tokenUsage.totalTokens.toLocaleString("zh-CN")} tokens</span>
                  <span className="rounded-md bg-white/80 px-2 py-1 dark:bg-zinc-900/70">输入 {selectedLog.tokenUsage.inputTokens.toLocaleString("zh-CN")}</span>
                  <span className="rounded-md bg-white/80 px-2 py-1 dark:bg-zinc-900/70">输出 {selectedLog.tokenUsage.outputTokens.toLocaleString("zh-CN")}</span>
                </div>
              ) : null}
            </div>

            <div className="mt-5 space-y-4">
              <section className="overflow-hidden rounded-xl border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
                <div className="flex items-center gap-2 border-b border-zinc-100 px-4 py-3 text-sm font-semibold text-zinc-700 dark:border-zinc-800 dark:text-zinc-200">
                  <FileInput size={15} className="text-blue-500" />
                  请求参数
                </div>
                <pre className="m-0 max-h-[360px] overflow-auto whitespace-pre-wrap break-all bg-zinc-50/60 p-4 font-mono text-xs text-zinc-700 dark:bg-zinc-950/40 dark:text-zinc-300">{formatJson(selectedLog.requestPayload)}</pre>
              </section>

              <section className="overflow-hidden rounded-xl border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
                <div className="flex items-center gap-2 border-b border-zinc-100 px-4 py-3 text-sm font-semibold text-zinc-700 dark:border-zinc-800 dark:text-zinc-200">
                  <FileOutput size={15} className="text-emerald-500" />
                  响应内容
                </div>
                <pre className="m-0 max-h-[360px] overflow-auto whitespace-pre-wrap break-all bg-zinc-50/60 p-4 font-mono text-xs text-zinc-700 dark:bg-zinc-950/40 dark:text-zinc-300">{formatJson(selectedLog.responsePayload)}</pre>
              </section>

              {selectedLog.errorMessage ? (
                <div className="flex items-start gap-2 rounded-xl border border-red-100 bg-red-50 p-4 text-xs text-red-600 dark:border-red-950/60 dark:bg-red-950/20">
                  <AlertCircle size={14} className="mt-0.5 shrink-0" />
                  <div>
                    <div className="mb-1 font-semibold">失败原因</div>
                    <div>{selectedLog.errorMessage}</div>
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}
