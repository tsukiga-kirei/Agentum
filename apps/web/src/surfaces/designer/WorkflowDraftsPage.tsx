import { FormEvent, useMemo, useState } from "react";
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
import { WorkflowEditorPage } from "./WorkflowEditorPage";

type WorkflowStatus = "draft" | "published" | "review";

// 工作流草稿列表是设计态入口，不等同于运行实例；发布后需要生成不可变 WorkflowVersion。
export type WorkflowDraft = {
  id: string;
  name: string;
  description: string;
  owner: string;
  status: WorkflowStatus;
  nodeCount: number;
  pausePoints: number;
  updatedAt: string;
};

// 列表数据先模拟工作流定义 API 的返回结构，后续应替换为草稿查询接口和契约生成类型。
const initialWorkflows: WorkflowDraft[] = [
  {
    id: "wf_requirement_review",
    name: "需求分析与评审流程",
    description: "收集需求材料，智能体拆解范围和风险，人工确认后生成评审结论。",
    owner: "产品运营组",
    status: "draft",
    nodeCount: 7,
    pausePoints: 2,
    updatedAt: "今天 15:20",
  },
  {
    id: "wf_contract_delivery",
    name: "合同审查交付流程",
    description: "识别合同风险条款，输出修改建议，审核通过后生成交付记录。",
    owner: "法务组",
    status: "review",
    nodeCount: 8,
    pausePoints: 2,
    updatedAt: "昨天 18:05",
  },
  {
    id: "wf_monthly_report",
    name: "经营月报汇总流程",
    description: "并行获取经营数据摘要，组装月报草稿，人工复核后发送邮件。",
    owner: "经营分析组",
    status: "published",
    nodeCount: 9,
    pausePoints: 1,
    updatedAt: "4 月 28 日",
  },
];

// 前端状态文案先服务设计页可读性，真实状态流转后续以发布校验和版本状态机为准。
const statusMeta: Record<WorkflowStatus, { label: string; className: string }> = {
  draft: {
    label: "草稿",
    className: "bg-amber-100 text-amber-800 ring-amber-200",
  },
  published: {
    label: "已发布",
    className: "bg-emerald-100 text-emerald-800 ring-emerald-200",
  },
  review: {
    label: "待校验",
    className: "bg-sky-100 text-sky-800 ring-sky-200",
  },
};

export function WorkflowDraftsPage() {
  // 当前阶段先让草稿创建和搜索在前端可操作，后续接入后端后只保留表单状态。
  const [workflows, setWorkflows] = useState(initialWorkflows);
  const [searchValue, setSearchValue] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const [draftName, setDraftName] = useState("");
  const [draftDescription, setDraftDescription] = useState("");
  const [formError, setFormError] = useState("");
  const [editingWorkflow, setEditingWorkflow] = useState<WorkflowDraft | null>(null);

  const filteredWorkflows = useMemo(() => {
    const keyword = searchValue.trim().toLowerCase();

    if (!keyword) {
      return workflows;
    }

    return workflows.filter((workflow) => {
      // 搜索只在本地样例数据上过滤，接入后端后应由草稿查询 API 处理分页、权限和关键字匹配。
      return (
        workflow.name.toLowerCase().includes(keyword) ||
        workflow.description.toLowerCase().includes(keyword) ||
        workflow.owner.toLowerCase().includes(keyword)
      );
    });
  }, [searchValue, workflows]);

  const draftCount = workflows.filter((workflow) => workflow.status === "draft").length;
  const publishedCount = workflows.filter((workflow) => workflow.status === "published").length;
  const reviewCount = workflows.filter((workflow) => workflow.status === "review").length;

  function handleCreateDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const name = draftName.trim();
    const description = draftDescription.trim();

    if (!name) {
      setFormError("请输入工作流名称");
      return;
    }

    // 新建草稿会先落到内存列表，后续应改成调用“创建工作流草稿”API 后再刷新列表。
    setWorkflows((currentWorkflows) => [
      {
        id: `wf_${Date.now()}`,
        name,
        description: description || "新建工作流草稿，等待补充节点、变量和交付配置。",
        owner: "当前用户",
        status: "draft",
        nodeCount: 1,
        pausePoints: 0,
        updatedAt: "刚刚",
      },
      ...currentWorkflows,
    ]);
    setDraftName("");
    setDraftDescription("");
    setFormError("");
    setIsCreating(false);
  }

  if (editingWorkflow) {
    return <WorkflowEditorPage workflow={editingWorkflow} onBack={() => setEditingWorkflow(null)} />;
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5" aria-label="流程设计总览">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-end">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">流程定义管理</p>
            <h2 className="mt-2 text-xl font-semibold text-[var(--color-text-primary)]">先把草稿、校验和画布入口做成稳定工作台</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              列表页承接业务模板和画布编辑器，后续会与 WorkflowDefinition API 保持同一份数据。
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
        <SummaryCard icon={GitBranch} label="全部工作流" value={String(workflows.length)} detail="草稿、待校验和已发布" />
        <SummaryCard icon={Clock3} label="草稿" value={String(draftCount)} detail="可继续编辑节点配置" />
        <SummaryCard icon={CheckCircle2} label="已发布" value={String(publishedCount)} detail={`${reviewCount} 个流程等待校验`} />
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
                onChange={(event) => setSearchValue(event.target.value)}
                className="agent-input h-10 w-full pl-9 pr-3 text-sm outline-none"
                placeholder="搜索名称、说明或负责人"
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
                    <span>负责人：{workflow.owner}</span>
                    <span>节点：{workflow.nodeCount}</span>
                    <span>暂停点：{workflow.pausePoints}</span>
                    <span>更新：{workflow.updatedAt}</span>
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

        {filteredWorkflows.length === 0 ? (
          <div className="px-5 py-12 text-center">
            <AlertCircle className="mx-auto h-8 w-8 text-slate-400" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-slate-700">没有找到匹配的工作流</p>
            <p className="mt-1 text-sm text-slate-500">可以调整搜索词，或创建一个新的工作流草稿。</p>
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
              工作流列表完成后，后续会接入后端草稿 API，并把“打开画布”连接到固定节点类型的编辑器。
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
                <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{formError}</p>
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
                  className="agent-button agent-button-primary h-10 px-3 text-sm"
                >
                  <FilePlus2 className="h-4 w-4" aria-hidden="true" />
                  保存草稿
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
