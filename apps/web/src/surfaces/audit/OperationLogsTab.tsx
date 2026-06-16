import { useEffect, useState } from "react";
import { Empty, Pagination, message } from "antd";
import { Search, Info, Settings, Calendar, User, Eye, ChevronDown, ChevronUp } from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditOperationLog } from "../../types/audit";

interface OperationLogsTabProps {
  setLoading: (loading: boolean) => void;
}

export function OperationLogsTab({ setLoading }: OperationLogsTabProps) {
  const token = useAuthStore((s) => s.token) || "";
  const activeRole = useAuthStore((s) => s.activeRole);
  const user = useAuthStore((s) => s.user);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const themeMode = useAuthStore((s) => s.themeMode);

  const [logs, setLogs] = useState<AuditOperationLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [actionType, setActionType] = useState("");

  const [expandedLogId, setExpandedLogId] = useState<string | null>(null);

  const fetchLogs = async (pageVal = page, sizeVal = size) => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const res = await auditApi.listOperations(
        tenantId, token, pageVal, sizeVal, "createdAt,desc", actionType
      );
      if (res) {
        setLogs(res.items);
        setTotal(res.total);
      }
    } catch (e: any) {
      console.error(e);
      message.error(e.message || "加载操作审计日志失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs(1);
    setPage(1);
    setExpandedLogId(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionType, tenantId]);

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
        <div className="flex items-center gap-2 text-zinc-500 text-sm">
          <Info size={16} />
          <span>全局配置审计记录：跟踪流程设计编辑、发布以及权限分配变更。</span>
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto justify-end">
          {/* 操作类型筛选 */}
          <select
            value={actionType}
            onChange={(e) => setActionType(e.target.value)}
            className="sys-input w-48"
          >
            <option value="">全部操作动作</option>
            <option value="CREATE_WORKFLOW">创建流程草稿</option>
            <option value="SAVE_WORKFLOW">修改流程图</option>
            <option value="PUBLISH_VERSION">发布正式版本</option>
            <option value="RECALL_LAUNCH">收回业务入口</option>
            <option value="RESTORE_LAUNCH">恢复业务入口</option>
            <option value="ASSIGN_CAPABILITY">分配能力池</option>
            <option value="ASSIGN_PAGE">页签权限分配</option>
            <option value="CREATE_MEMBER">新建组织成员</option>
            <option value="CREATE_DEPARTMENT">新建部门</option>
          </select>
        </div>
      </div>

      {/* 列表渲染 */}
      {logs.length === 0 ? (
        <div className="py-12 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-xl flex items-center justify-center">
          <Empty description="暂无操作审计日志记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="overflow-hidden bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-xl divide-y divide-zinc-100 dark:divide-zinc-800">
            {logs.map((log) => {
              const isExpanded = expandedLogId === log.id;
              return (
                <div key={log.id} className="transition-colors hover:bg-zinc-50/20 dark:hover:bg-zinc-800/10">
                  <div
                    onClick={() => toggleExpand(log.id)}
                    className="flex flex-col md:flex-row md:items-center justify-between p-4 cursor-pointer"
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <div className="p-1.5 rounded bg-zinc-100 dark:bg-zinc-800 text-zinc-500 shrink-0">
                          <Settings size={14} />
                        </div>
                        <span className="font-semibold text-zinc-800 dark:text-zinc-100 text-sm">
                          {log.description}
                        </span>
                      </div>
                      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-zinc-400 pl-8">
                        <span className="flex items-center gap-1">
                          <User size={12} />
                          操作人: {log.operatorName}
                        </span>
                        <span>动作: {log.actionType}</span>
                        <span>对象: {log.targetType} ({log.targetName || "—"})</span>
                      </div>
                    </div>

                    <div className="mt-2 md:mt-0 flex items-center gap-4 text-right pl-8 md:pl-0">
                      <div className="text-xs text-zinc-400 hidden md:block">
                        <div className="flex items-center gap-1">
                          <Calendar size={12} /> {formatDate(log.createdAt)}
                          {log.clientIp ? ` (IP: ${log.clientIp})` : ""}
                        </div>
                      </div>
                      <div className="text-zinc-400">
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </div>
                  </div>

                  {/* 展开查看 payload 变更细节 */}
                  {isExpanded && (
                    <div className="px-4 pb-4 border-t border-dashed border-zinc-100 dark:border-zinc-800 pt-3 bg-zinc-50/30 dark:bg-zinc-950/10 space-y-2">
                      <div className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">变更细节快照 (Payload Snapshot)</div>
                      <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto max-h-80 text-zinc-700 dark:text-zinc-300">
                        {formatJson(log.payload)}
                      </pre>
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
