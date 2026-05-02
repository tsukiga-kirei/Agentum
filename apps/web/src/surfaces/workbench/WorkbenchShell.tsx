import {
  Activity,
  Archive,
  Bot,
  CheckCircle2,
  ClipboardList,
  FileText,
  GitBranch,
  LayoutDashboard,
  Library,
  ShieldCheck,
  UserRoundCheck,
} from "lucide-react";

type NavigationItem = {
  label: string;
  description: string;
  icon: typeof LayoutDashboard;
  active?: boolean;
};

type Metric = {
  label: string;
  value: string;
  detail: string;
  tone: string;
};

type TodoItem = {
  title: string;
  workflow: string;
  owner: string;
  deadline: string;
  status: string;
};

type WorkflowTemplate = {
  title: string;
  description: string;
  nodes: string;
  tag: string;
};

type RunRecord = {
  name: string;
  state: string;
  node: string;
  duration: string;
};

const navigationItems: NavigationItem[] = [
  {
    label: "业务工作台",
    description: "待办、发起和结果",
    icon: LayoutDashboard,
    active: true,
  },
  {
    label: "流程设计",
    description: "画布与节点配置",
    icon: GitBranch,
  },
  {
    label: "能力资产",
    description: "智能体、Skills、MCP",
    icon: Library,
  },
  {
    label: "运行审计",
    description: "日志和证据链",
    icon: Activity,
  },
  {
    label: "权限管理",
    description: "角色、空间、授权",
    icon: ShieldCheck,
  },
];

const metrics: Metric[] = [
  {
    label: "待处理事项",
    value: "8",
    detail: "3 个审核，5 个补充输入",
    tone: "border-l-amber-500",
  },
  {
    label: "今日运行",
    value: "24",
    detail: "21 次完成，3 次暂停",
    tone: "border-l-emerald-500",
  },
  {
    label: "已发布流程",
    value: "12",
    detail: "覆盖 5 类企业 SOP",
    tone: "border-l-sky-500",
  },
  {
    label: "能力资产",
    value: "36",
    detail: "智能体、Skills 和 MCP",
    tone: "border-l-violet-500",
  },
];

const todoItems: TodoItem[] = [
  {
    title: "确认合同风险分析结论",
    workflow: "合同审查与交付流程",
    owner: "法务组",
    deadline: "今天 18:00",
    status: "等待人工审核",
  },
  {
    title: "补充项目立项背景材料",
    workflow: "立项材料生成流程",
    owner: "项目经理",
    deadline: "明天 10:00",
    status: "等待用户输入",
  },
  {
    title: "复核月报交付邮件",
    workflow: "经营月报汇总流程",
    owner: "运营组",
    deadline: "明天 16:00",
    status: "等待交付确认",
  },
];

const workflowTemplates: WorkflowTemplate[] = [
  {
    title: "需求分析闭环",
    description: "输入需求材料，自动拆解要点，人工确认后生成评审文档。",
    nodes: "7 个节点",
    tag: "需求",
  },
  {
    title: "合同审查交付",
    description: "识别风险条款，汇总修改建议，审核通过后生成交付记录。",
    nodes: "8 个节点",
    tag: "法务",
  },
  {
    title: "经营报告组装",
    description: "并行获取数据摘要，合并分析结论，输出报告草稿。",
    nodes: "9 个节点",
    tag: "经营",
  },
];

const runRecords: RunRecord[] = [
  {
    name: "客户续约风险评估",
    state: "运行中",
    node: "并行数据获取",
    duration: "12 分钟",
  },
  {
    name: "采购合同审查",
    state: "已暂停",
    node: "人工审核",
    duration: "38 分钟",
  },
  {
    name: "周报生成与发送",
    state: "已完成",
    node: "邮件交付",
    duration: "6 分钟",
  },
];

export function WorkbenchShell() {
  return (
    <main className="min-h-screen bg-zinc-100 text-zinc-950">
      <div className="flex min-h-screen">
        <aside className="hidden w-72 shrink-0 border-r border-zinc-200 bg-white px-4 py-5 lg:block">
          <div className="mb-8 flex items-center gap-3 px-2">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-zinc-950 text-white">
              <Bot className="h-5 w-5" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-semibold">Agentum</p>
              <p className="text-xs text-zinc-500">智能体装配式工作流</p>
            </div>
          </div>

          <nav className="space-y-1" aria-label="主导航">
            {navigationItems.map((item) => {
              const Icon = item.icon;

              return (
                <button
                  key={item.label}
                  type="button"
                  className={`flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition ${
                    item.active
                      ? "bg-zinc-950 text-white"
                      : "text-zinc-700 hover:bg-zinc-100 hover:text-zinc-950"
                  }`}
                  title={item.description}
                >
                  <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{item.label}</span>
                    <span className={`block text-xs ${item.active ? "text-zinc-300" : "text-zinc-500"}`}>
                      {item.description}
                    </span>
                  </span>
                </button>
              );
            })}
          </nav>
        </aside>

        <section className="min-w-0 flex-1">
          <header className="border-b border-zinc-200 bg-white">
            <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-5 md:flex-row md:items-center md:justify-between lg:px-8">
              <div>
                <p className="text-sm text-zinc-500">阶段一：核心闭环</p>
                <h1 className="mt-1 text-2xl font-semibold text-zinc-950">业务工作台</h1>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className="inline-flex h-10 items-center gap-2 rounded-lg border border-zinc-300 bg-white px-3 text-sm font-medium text-zinc-800 hover:bg-zinc-50"
                >
                  <ClipboardList className="h-4 w-4" aria-hidden="true" />
                  我的待办
                </button>
                <button
                  type="button"
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-emerald-700 px-3 text-sm font-medium text-white hover:bg-emerald-800"
                >
                  <GitBranch className="h-4 w-4" aria-hidden="true" />
                  发起流程
                </button>
              </div>
            </div>
          </header>

          <div className="mx-auto max-w-7xl space-y-6 px-5 py-6 lg:px-8">
            <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="关键指标">
              {metrics.map((metric) => (
                <article key={metric.label} className={`rounded-lg border border-zinc-200 border-l-4 ${metric.tone} bg-white p-4`}>
                  <p className="text-sm text-zinc-500">{metric.label}</p>
                  <p className="mt-3 text-3xl font-semibold">{metric.value}</p>
                  <p className="mt-2 text-sm text-zinc-600">{metric.detail}</p>
                </article>
              ))}
            </section>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(340px,0.65fr)]">
              <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="todo-title">
                <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4">
                  <div>
                    <h2 id="todo-title" className="text-base font-semibold">
                      我的待办
                    </h2>
                    <p className="mt-1 text-sm text-zinc-500">需要输入、确认或审核的流程节点</p>
                  </div>
                  <UserRoundCheck className="h-5 w-5 text-amber-600" aria-hidden="true" />
                </div>
                <div className="divide-y divide-zinc-100">
                  {todoItems.map((item) => (
                    <article key={item.title} className="grid gap-3 px-5 py-4 md:grid-cols-[minmax(0,1fr)_160px] md:items-center">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 className="text-sm font-semibold text-zinc-950">{item.title}</h3>
                          <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
                            {item.status}
                          </span>
                        </div>
                        <p className="mt-2 text-sm text-zinc-600">{item.workflow}</p>
                        <p className="mt-1 text-xs text-zinc-500">
                          {item.owner} · {item.deadline}
                        </p>
                      </div>
                      <button
                        type="button"
                        className="inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-zinc-300 px-3 text-sm font-medium text-zinc-800 hover:bg-zinc-50"
                      >
                        <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
                        处理
                      </button>
                    </article>
                  ))}
                </div>
              </section>

              <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="run-title">
                <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4">
                  <div>
                    <h2 id="run-title" className="text-base font-semibold">
                      运行态摘要
                    </h2>
                    <p className="mt-1 text-sm text-zinc-500">当前流程位置和处理状态</p>
                  </div>
                  <Activity className="h-5 w-5 text-sky-700" aria-hidden="true" />
                </div>
                <div className="space-y-3 p-5">
                  {runRecords.map((record) => (
                    <article key={record.name} className="rounded-lg border border-zinc-200 p-4">
                      <div className="flex items-center justify-between gap-3">
                        <h3 className="min-w-0 text-sm font-semibold">{record.name}</h3>
                        <span className="shrink-0 rounded bg-zinc-100 px-2 py-1 text-xs font-medium text-zinc-700">
                          {record.state}
                        </span>
                      </div>
                      <p className="mt-3 text-sm text-zinc-600">{record.node}</p>
                      <p className="mt-1 text-xs text-zinc-500">已执行 {record.duration}</p>
                    </article>
                  ))}
                </div>
              </section>
            </div>

            <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="template-title">
              <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4">
                <div>
                  <h2 id="template-title" className="text-base font-semibold">
                    可用流程模板
                  </h2>
                  <p className="mt-1 text-sm text-zinc-500">从固定节点类型开始搭建业务流程</p>
                </div>
                <Archive className="h-5 w-5 text-violet-700" aria-hidden="true" />
              </div>
              <div className="grid gap-4 p-5 lg:grid-cols-3">
                {workflowTemplates.map((template) => (
                  <article key={template.title} className="rounded-lg border border-zinc-200 p-4">
                    <div className="flex items-center justify-between gap-3">
                      <span className="rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800">
                        {template.tag}
                      </span>
                      <span className="text-xs text-zinc-500">{template.nodes}</span>
                    </div>
                    <h3 className="mt-4 text-sm font-semibold text-zinc-950">{template.title}</h3>
                    <p className="mt-2 min-h-12 text-sm leading-6 text-zinc-600">{template.description}</p>
                    <button
                      type="button"
                      className="mt-4 inline-flex h-9 items-center gap-2 rounded-lg border border-zinc-300 px-3 text-sm font-medium text-zinc-800 hover:bg-zinc-50"
                    >
                      <FileText className="h-4 w-4" aria-hidden="true" />
                      查看模板
                    </button>
                  </article>
                ))}
              </div>
            </section>
          </div>
        </section>
      </div>
    </main>
  );
}
