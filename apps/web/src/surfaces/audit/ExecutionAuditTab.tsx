import { useEffect, useState } from "react";
import { Empty, Pagination, message, Select } from "antd";
import { Search, ClipboardList, Calendar, User, Info, Eye, Activity, ChevronDown } from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditRunSummary } from "../../types/audit";
import { RunEvidenceDrawer } from "./RunEvidenceDrawer";

interface ExecutionAuditTabProps {
  setLoading: (loading: boolean) => void;
}

export function ExecutionAuditTab({ setLoading }: ExecutionAuditTabProps) {
  const token = useAuthStore((s) => s.token) || "";
  const activeRole = useAuthStore((s) => s.activeRole);
  const user = useAuthStore((s) => s.user);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const themeMode = useAuthStore((s) => s.themeMode);

  const [runs, setRuns] = useState<AuditRunSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [keyword, setKeyword] = useState("");
  const [state, setState] = useState("");

  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const selectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
  const selectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

  const fetchRuns = async (pageVal = page, sizeVal = size) => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const res = await auditApi.listRuns(tenantId, token, pageVal, sizeVal, "startedAt,desc", keyword, state);
      if (res) {
        setRuns(res.items);
        setTotal(res.total);
      }
    } catch (e: any) {
      console.error(e);
      message.error(e.message || "加载运行审计数据失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRuns(1);
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, state, tenantId]);

  const handlePageChange = (p: number, s: number) => {
    setPage(p);
    setSize(s);
    fetchRuns(p, s);
  };

  const formatState = (s: string) => {
    switch (s) {
      case "running": return { label: "执行中", cls: "sys-status--active" };
      case "paused": return { label: "已暂停", cls: "sys-status--paused" };
      case "completed": return { label: "已完成", cls: "sys-status--success" };
      case "failed": return { label: "已失败", cls: "sys-status--inactive" };
      case "canceled": return { label: "已取消", cls: "sys-status--inactive" };
      default: return { label: s, cls: "sys-status--inactive" };
    }
  };

  const formatDate = (isoStr: string | null) => {
    if (!isoStr) return "—";
    return new Date(isoStr).toLocaleString("zh-CN", { hour12: false });
  };

  return (
    <div className="space-y-4">
      {/* 简洁平级工具栏，无灰色背景与边框 */}
      <div className="flex flex-col md:flex-row gap-3 items-center justify-between py-2 border-b border-zinc-100 dark:border-zinc-800">
        <div className="workflow-library-toolbar-actions w-full justify-start">
          {/* 关键字搜索 */}
          <label className="workflow-definition-search w-80 shrink-0">
            <Search className="h-[18px] w-[18px]" aria-hidden="true" />
            <input
              type="text"
              placeholder="搜索工作流名称/实例标题..."
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </label>
          {/* 状态筛选 */}
          <Select
            value={state}
            onChange={(value) => setState(value)}
            className="agent-admin-select w-36"
            classNames={selectClassNames}
            suffixIcon={selectSuffixIcon}
            prefix={<Activity className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
            options={[
              { value: "", label: "全部状态" },
              { value: "running", label: "执行中" },
              { value: "paused", label: "已暂停" },
              { value: "completed", label: "已完成" },
              { value: "failed", label: "已失败" },
              { value: "canceled", label: "已取消" }
            ]}
          />
        </div>
      </div>

      {runs.length === 0 ? (
        <div className="py-16 flex items-center justify-center">
          <Empty description="暂无运行审计记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="workbench-task-center-list">
            {runs.map((run, index) => {
              const stateInfo = formatState(run.state);
              return (
                <div
                  key={run.id}
                  onClick={() => setSelectedRunId(run.id)}
                  className="sys-preview-item sys-card-enter cursor-pointer group"
                  style={{ animationDelay: `${index * 40}ms` }}
                >
                  <div className="sys-preview-item-left">
                    <span className="sys-preview-item-icon sys-card-avatar--cap">
                      <ClipboardList size={16} aria-hidden="true" />
                    </span>
                    <div className="min-w-0">
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className="font-semibold text-zinc-800 dark:text-zinc-100 group-hover:text-primary-600 transition-colors text-sm">
                          {run.title}
                        </span>
                        <span className={`sys-status ${stateInfo.cls}`}>
                          <span className="sys-status-dot" />
                          {stateInfo.label}
                        </span>
                      </div>
                      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-zinc-400 mt-1">
                        <span className="flex items-center gap-1">
                          流程: {run.workflowName} (v{run.versionNumber})
                        </span>
                        <span className="flex items-center gap-1">
                          启动人: {run.operatorName}
                        </span>
                        <span className="flex items-center gap-1">
                          启动时间: {formatDate(run.startedAt)}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex shrink-0 items-center gap-4">
                    <div className="text-xs text-zinc-400 hidden md:block text-right">
                      {run.completedAt ? (
                        <div>结束: {formatDate(run.completedAt)}</div>
                      ) : (
                        <div className="text-zinc-500 italic">正在持续运行...</div>
                      )}
                    </div>
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center bg-zinc-50 dark:bg-zinc-800 text-zinc-400 group-hover:bg-primary-50 group-hover:text-primary-600 dark:group-hover:bg-primary-950/40 transition-colors">
                      <Eye size={16} />
                    </div>
                  </div>
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

      {/* 全证据链抽屉 */}
      <RunEvidenceDrawer
        runId={selectedRunId}
        onClose={() => setSelectedRunId(null)}
      />
    </div>
  );
}
