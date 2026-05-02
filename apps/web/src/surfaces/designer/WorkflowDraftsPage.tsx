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
    <div className="mx-auto max-w-7xl space-y-6 px-5 py-6 lg:px-8">
      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm" aria-label="流程设计总览">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-end">
          <div>
            <p className="text-sm font-medium text-emerald-700">流程定义管理</p>
            <h2 className="mt-2 text-2xl font-semibold tracking-tight text-slate-950">先把草稿、校验和画布入口做成稳定工作台</h2>
            <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-600">
              列表页承接业务模板和画布编辑器，后续会与 WorkflowDefinition API 保持同一份数据。
            </p>
          </div>
          <button
            type="button"
            onClick={() => setIsCreating(true)}
            className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-emerald-700 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-900/20 transition-colors duration-200 hover:bg-emerald-800"
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

      <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm" aria-labelledby="workflow-list-title">
        <div className="flex flex-col gap-4 border-b border-slate-200 bg-slate-50/80 px-5 py-4 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <h2 id="workflow-list-title" className="text-base font-semibold text-slate-950">
              工作流列表
            </h2>
            <p className="mt-1 text-sm text-slate-500">管理草稿、发布版本和后续画布配置入口</p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <label className="relative block sm:w-72">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
              <span className="sr-only">搜索工作流</span>
              <input
                value={searchValue}
                onChange={(event) => setSearchValue(event.target.value)}
                className="h-10 w-full rounded-lg border border-slate-300 bg-white pl-9 pr-3 text-sm outline-none transition-colors duration-200 focus:border-emerald-500"
                placeholder="搜索名称、说明或负责人"
              />
            </label>
            <button
              type="button"
              onClick={() => setIsCreating(true)}
              className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-emerald-700 px-3 text-sm font-semibold text-white shadow-sm shadow-emerald-900/20 transition-colors duration-200 hover:bg-emerald-800"
            >
              <FilePlus2 className="h-4 w-4" aria-hidden="true" />
              新建草稿
            </button>
          </div>
        </div>

        <div className="divide-y divide-slate-100">
          {filteredWorkflows.map((workflow) => {
            const status = statusMeta[workflow.status];

            return (
              <article key={workflow.id} className="grid gap-4 px-5 py-5 transition-colors duration-200 hover:bg-slate-50/70 xl:grid-cols-[minmax(0,1fr)_220px] xl:items-center">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-semibold text-slate-950">{workflow.name}</h3>
                    <span className={`rounded px-2 py-1 text-xs font-medium ring-1 ${status.className}`}>{status.label}</span>
                  </div>
                  <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">{workflow.description}</p>
                  <div className="mt-3 flex flex-wrap gap-x-4 gap-y-2 text-xs text-slate-500">
                    <span>负责人：{workflow.owner}</span>
                    <span>节点：{workflow.nodeCount}</span>
                    <span>暂停点：{workflow.pausePoints}</span>
                    <span>更新：{workflow.updatedAt}</span>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 xl:justify-end">
                  <button
                    type="button"
                    className="inline-flex h-9 items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50"
                  >
                    <ListChecks className="h-4 w-4" aria-hidden="true" />
                    发布校验
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditingWorkflow(workflow)}
                    className="inline-flex h-9 items-center gap-2 rounded-lg bg-slate-950 px-3 text-sm font-medium text-white transition-colors duration-200 hover:bg-slate-800"
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

      <section className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 shadow-sm" aria-labelledby="next-step-title">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 id="next-step-title" className="text-base font-semibold text-emerald-950">
              下一步建设重点
            </h2>
            <p className="mt-2 text-sm leading-6 text-emerald-800">
              工作流列表完成后，后续会接入后端草稿 API，并把“打开画布”连接到固定节点类型的编辑器。
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-lg border border-emerald-300 bg-white px-3 py-2 text-sm font-medium text-emerald-800">
            <Sparkles className="h-4 w-4" aria-hidden="true" />
            阶段一：工作流定义管理
          </div>
        </div>
      </section>

      {isCreating ? (
        <div className="fixed inset-0 z-20 flex items-center justify-center bg-slate-950/40 px-4 py-6 backdrop-blur-sm">
          <section className="w-full max-w-lg rounded-xl border border-slate-200 bg-white shadow-xl" aria-labelledby="create-draft-title">
            <div className="border-b border-slate-200 px-5 py-4">
              <h2 id="create-draft-title" className="text-base font-semibold text-slate-950">
                新建工作流草稿
              </h2>
              <p className="mt-1 text-sm text-slate-500">先保存基础信息，之后进入画布补充节点和变量。</p>
            </div>
            <form onSubmit={handleCreateDraft} className="space-y-4 px-5 py-5">
              <label className="block">
                <span className="text-sm font-medium text-slate-800">工作流名称</span>
                <input
                  value={draftName}
                  onChange={(event) => {
                    setDraftName(event.target.value);
                    setFormError("");
                  }}
                  className="mt-2 h-10 w-full rounded-lg border border-slate-300 px-3 text-sm outline-none transition-colors duration-200 focus:border-emerald-500"
                  placeholder="例如：客户续约风险评估流程"
                />
              </label>
              <label className="block">
                <span className="text-sm font-medium text-slate-800">说明</span>
                <textarea
                  value={draftDescription}
                  onChange={(event) => setDraftDescription(event.target.value)}
                  className="mt-2 min-h-28 w-full resize-y rounded-lg border border-slate-300 px-3 py-2 text-sm leading-6 outline-none transition-colors duration-200 focus:border-emerald-500"
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
                  className="inline-flex h-10 items-center rounded-lg border border-slate-300 px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50"
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-emerald-700 px-3 text-sm font-semibold text-white transition-colors duration-200 hover:bg-emerald-800"
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
    <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-slate-500">{label}</p>
        <Icon className="h-5 w-5 text-emerald-700" aria-hidden="true" />
      </div>
      <p className="mt-3 text-3xl font-semibold tracking-tight text-slate-950">{value}</p>
      <p className="mt-2 text-sm text-slate-600">{detail}</p>
    </article>
  );
}
