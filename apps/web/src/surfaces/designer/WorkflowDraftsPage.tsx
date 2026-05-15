import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  FilePlus2,
  GitBranch,
  ListChecks,
  PanelRightOpen,
  Search,
  Sparkles,
} from "lucide-react";
import { Pagination } from "antd";
import { AgentumApiError, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { WorkflowDraftRow, WorkflowStatus } from "../../types/workflow-contract";
import { WorkflowEditorPage } from "./WorkflowEditorPage";

// 工作流草稿列表是设计态入口，不等同于运行实例；发布后需要生成不可变 WorkflowVersion。
export type WorkflowDraft = WorkflowDraftRow;

// 前端状态文案先服务设计页可读性，真实状态流转后续以发布校验和版本状态机为准。
const statusMeta: Record<WorkflowStatus, { label: string; className: string }> = {
  draft: {
    label: "草稿",
    className: "bg-amber-100 text-amber-800 ring-amber-200 dark:bg-amber-900/40 dark:text-amber-300 dark:ring-amber-800/60",
  },
  published: {
    label: "已发布",
    className: "bg-emerald-100 text-emerald-800 ring-emerald-200 dark:bg-emerald-900/40 dark:text-emerald-300 dark:ring-emerald-800/60",
  },
  review: {
    label: "待校验",
    className: "bg-sky-100 text-sky-800 ring-sky-200 dark:bg-sky-900/40 dark:text-sky-300 dark:ring-sky-800/60",
  },
};

const workflowPaginationLocale = {
  items_per_page: "条/页",
  jump_to: "跳至",
  jump_to_confirm: "确定",
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
  prev_5: "向前 5 页",
  next_5: "向后 5 页",
  prev_3: "向前 3 页",
  next_3: "向后 3 页",
  page_size: "每页条数",
};

function formatPaginationTotal(count: number, range: [number, number], pageSize: number): string {
  return count <= pageSize ? `共 ${count} 条` : `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`;
}

export function WorkflowDraftsPage() {
  // 草稿列表和画布图都已接入工作流草稿 API；列表仍只承接设计态入口，运行实例会在后续独立建模。
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [workflows, setWorkflows] = useState<WorkflowDraft[]>([]);
  const [searchValue, setSearchValue] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const [draftName, setDraftName] = useState("");
  const [draftDescription, setDraftDescription] = useState("");
  const [formError, setFormError] = useState("");
  const [editingWorkflow, setEditingWorkflow] = useState<WorkflowDraft | null>(null);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(8);
  const [total, setTotal] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState("");

  const loadDrafts = useCallback(async (nextPage = 1, keyword = searchValue, nextPageSize = pageSize) => {
    if (!token || !user?.tenantId) {
      setLoadError("当前账号缺少租户上下文，无法加载工作流草稿");
      setWorkflows([]);
      return;
    }

    setLoading(true);
    setLoadError("");

    try {
      const result = await workflowApi.listDrafts(user.tenantId, token, nextPage, nextPageSize, keyword);
      setWorkflows(result.items);
      setPage(result.page);
      setPageSize(result.size);
      setTotal(result.total);
    } catch (error) {
      console.warn("[workflow] 工作流草稿加载失败", getWorkflowErrorContext(error, user.tenantId));
      setLoadError(error instanceof AgentumApiError ? error.message : "无法加载工作流草稿");
      setWorkflows([]);
    } finally {
      setLoading(false);
    }
  }, [pageSize, searchValue, token, user?.tenantId]);

  useEffect(() => {
    void loadDrafts(1);
  }, [loadDrafts]);

  const filteredWorkflows = useMemo(() => {
    return workflows;
  }, [workflows]);

  const draftCount = workflows.filter((workflow) => workflow.status === "draft").length;
  const publishedCount = workflows.filter((workflow) => workflow.status === "published").length;
  const reviewCount = workflows.filter((workflow) => workflow.status === "review").length;

  async function handleCreateDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || !user?.tenantId) {
      setFormError("当前账号缺少租户上下文，无法保存草稿");
      return;
    }

    const name = draftName.trim();
    const description = draftDescription.trim();

    if (!name) {
      setFormError("请输入工作流名称");
      return;
    }

    setSubmitting(true);
    setFormError("");

    try {
      await workflowApi.createDraft(user.tenantId, token, { name, description });
      setDraftName("");
      setDraftDescription("");
      setIsCreating(false);
      await loadDrafts(1, searchValue);
    } catch (error) {
      console.warn("[workflow] 工作流草稿创建失败", getWorkflowErrorContext(error, user.tenantId, { name }));
      setFormError(error instanceof AgentumApiError ? error.message : "保存草稿失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  if (editingWorkflow) {
    return (
      <WorkflowEditorPage
        workflow={editingWorkflow}
        onBack={() => setEditingWorkflow(null)}
        onDraftSaved={(draft) => {
          setEditingWorkflow(draft);
          setWorkflows((currentWorkflows) => currentWorkflows.map((item) => item.id === draft.id ? draft : item));
        }}
      />
    );
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5" aria-label="流程设计总览">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-end">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">流程定义管理</p>
            <h2 className="mt-2 text-xl font-semibold text-[var(--color-text-primary)]">先把草稿、校验和画布入口做成稳定工作台</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              列表页已接入后端草稿 API，画布配置会围绕固定节点、变量引用和发布校验继续收敛。
            </p>
          </div>
          <button
            type="button"
            onClick={() => setIsCreating(true)}
            className="agent-button agent-button-primary h-11 px-4 text-sm"
          >
            <FilePlus2 className="h-4 w-4" aria-hidden="true" />
            新建工作流草稿
          </button>
        </div>
      </section>

      <section className="grid gap-4 md:grid-cols-3" aria-label="工作流概览">
        <SummaryCard icon={GitBranch} label="全部工作流" value={String(total)} detail="按当前租户与设计权限查询" />
        <SummaryCard icon={Clock3} label="当前页草稿" value={String(draftCount)} detail="可继续编辑节点配置" />
        <SummaryCard icon={CheckCircle2} label="当前页已发布" value={String(publishedCount)} detail={`${reviewCount} 个流程等待校验`} />
      </section>

      <section className="agent-card overflow-hidden" aria-labelledby="workflow-list-title">
        <div className="agent-card-header flex flex-col gap-4 bg-[var(--color-bg-hover)] xl:flex-row xl:items-center xl:justify-between">
          <div>
            <h2 id="workflow-list-title" className="text-base font-semibold text-[var(--color-text-primary)]">
              工作流列表
            </h2>
            <p className="agent-muted mt-1 text-sm">管理草稿、发布版本和后续画布配置入口</p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <label className="relative block sm:w-72">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-tertiary)]" aria-hidden="true" />
              <span className="sr-only">搜索工作流</span>
              <input
                value={searchValue}
                onChange={(event) => {
                  setSearchValue(event.target.value);
                  setPage(1);
                }}
                className="agent-input h-10 w-full pl-9 pr-3 text-sm outline-none"
                placeholder="搜索名称或说明"
              />
            </label>
            <button
              type="button"
              onClick={() => setIsCreating(true)}
              className="agent-button agent-button-primary h-10 px-3 text-sm"
            >
              <FilePlus2 className="h-4 w-4" aria-hidden="true" />
              新建草稿
            </button>
          </div>
        </div>

        {loadError ? (
          <div className="mx-5 mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-200">
            {loadError}
          </div>
        ) : null}

        <div className="divide-y divide-[var(--color-border-light)]">
          {filteredWorkflows.map((workflow) => {
            const status = statusMeta[workflow.status];

            return (
              <article key={workflow.id} className="grid gap-4 px-5 py-5 transition-colors duration-200 hover:bg-[var(--color-bg-hover)] xl:grid-cols-[minmax(0,1fr)_220px] xl:items-center">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{workflow.name}</h3>
                    <span className={`rounded px-2 py-1 text-xs font-medium ring-1 ${status.className}`}>{status.label}</span>
                  </div>
                  <p className="agent-muted mt-2 max-w-3xl text-sm leading-6">{workflow.description}</p>
                  <div className="mt-3 flex flex-wrap gap-x-4 gap-y-2 text-xs text-[var(--color-text-tertiary)]">
                    <span>负责人：{workflow.ownerName}</span>
                    <span>节点：{workflow.nodeCount}</span>
                    <span>暂停点：{workflow.pausePointCount}</span>
                    <span>更新：{formatDateTime(workflow.updatedAt)}</span>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 xl:justify-end">
                  <button
                    type="button"
                    className="agent-button h-9 px-3 text-sm"
                  >
                    <ListChecks className="h-4 w-4" aria-hidden="true" />
                    发布校验
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditingWorkflow(workflow)}
                    className="agent-button agent-button-primary h-9 px-3 text-sm"
                  >
                    <PanelRightOpen className="h-4 w-4" aria-hidden="true" />
                    打开画布
                  </button>
                </div>
              </article>
            );
          })}
        </div>

        {loading ? (
          <div className="px-5 py-12 text-center">
            <Clock3 className="mx-auto h-8 w-8 text-slate-400" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-700 dark:text-slate-200">正在加载工作流草稿</p>
          </div>
        ) : null}

        {!loading && filteredWorkflows.length === 0 ? (
          <div className="px-5 py-12 text-center">
            <AlertCircle className="mx-auto h-8 w-8 text-slate-400" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-700 dark:text-slate-200">没有找到匹配的工作流</p>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">可以调整搜索词，或创建一个新的工作流草稿。</p>
          </div>
        ) : null}

        {total > 0 ? (
          <div className="agent-admin-pagination-wrap px-5 py-4">
            <Pagination
              className="agent-admin-pagination"
              current={page}
              pageSize={pageSize}
              total={total}
              locale={workflowPaginationLocale}
              showSizeChanger={{ className: "agent-admin-select", popupClassName: "agent-select-dropdown agent-admin-select-dropdown" }}
              pageSizeOptions={["8", "16", "32"]}
              showTotal={(count, range) => formatPaginationTotal(count, range, pageSize)}
              disabled={loading}
              onChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize)}
              onShowSizeChange={(nextPage, nextPageSize) => void loadDrafts(nextPage, searchValue, nextPageSize)}
            />
          </div>
        ) : null}
      </section>

      <section className="rounded-[var(--radius-lg)] border border-indigo-200 bg-indigo-50 p-5 shadow-[var(--shadow-xs)] dark:border-indigo-900/60 dark:bg-indigo-950/30" aria-labelledby="next-step-title">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 id="next-step-title" className="text-base font-semibold text-indigo-950 dark:text-indigo-100">
              下一步建设重点
            </h2>
            <p className="mt-2 text-sm leading-6 text-indigo-800 dark:text-indigo-100">
              工作流列表、画布读取和草稿图保存已经进入真实 API，下一步补发布校验和变量声明规则。
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-lg border border-indigo-200 bg-white px-3 py-2 text-sm font-medium text-indigo-800 dark:border-indigo-900 dark:bg-indigo-950/50 dark:text-indigo-100">
            <Sparkles className="h-4 w-4" aria-hidden="true" />
            阶段一：工作流定义管理
          </div>
        </div>
      </section>

      {isCreating ? (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-slate-950/40 px-4 py-6 backdrop-blur-sm">
          <section className="agent-card w-full max-w-lg shadow-[var(--shadow-lg)]" aria-labelledby="create-draft-title">
            <div className="agent-card-header">
              <h2 id="create-draft-title" className="text-base font-semibold text-[var(--color-text-primary)]">
                新建工作流草稿
              </h2>
              <p className="agent-muted mt-1 text-sm">先保存基础信息，之后进入画布补充节点和变量。</p>
            </div>
            <form onSubmit={handleCreateDraft} className="space-y-4 px-5 py-5">
              <label className="block">
                <span className="text-sm font-medium text-[var(--color-text-primary)]">工作流名称</span>
                <input
                  value={draftName}
                  onChange={(event) => {
                    setDraftName(event.target.value);
                    setFormError("");
                  }}
                  className="agent-input mt-2 h-10 w-full px-3 text-sm outline-none"
                  placeholder="例如：客户续约风险评估流程"
                />
              </label>
              <label className="block">
                <span className="text-sm font-medium text-[var(--color-text-primary)]">说明</span>
                <textarea
                  value={draftDescription}
                  onChange={(event) => setDraftDescription(event.target.value)}
                  className="agent-input mt-2 min-h-28 w-full resize-y px-3 py-2 text-sm leading-6 outline-none"
                  placeholder="描述流程适用场景、输入材料和最终交付物"
                />
              </label>
              {formError ? (
                <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-200">{formError}</p>
              ) : null}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => {
                    setIsCreating(false);
                    setFormError("");
                  }}
                  className="agent-button h-10 px-3 text-sm"
                >
                  取消
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="agent-button agent-button-primary h-10 px-3 text-sm"
                >
                  <FilePlus2 className="h-4 w-4" aria-hidden="true" />
                  {submitting ? "保存中" : "保存草稿"}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function SummaryCard({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: typeof GitBranch;
  label: string;
  value: string;
  detail: string;
}) {
  return (
    <article className="agent-card p-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-[var(--color-text-secondary)]">{label}</p>
        <Icon className="h-5 w-5 text-[var(--color-primary)]" aria-hidden="true" />
      </div>
      <p className="mt-3 text-3xl font-semibold text-[var(--color-text-primary)]">{value}</p>
      <p className="agent-muted mt-2 text-sm">{detail}</p>
    </article>
  );
}

function formatDateTime(value: string) {
  if (!value) {
    return "-";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function getWorkflowErrorContext(error: unknown, tenantId?: string, extra?: Record<string, unknown>) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, ...extra };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, ...extra };
}
