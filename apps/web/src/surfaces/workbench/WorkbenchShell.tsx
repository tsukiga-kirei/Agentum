import { useState } from "react";
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
  Moon,
  ShieldCheck,
  Sun,
  UserRoundCheck,
} from "lucide-react";
import { WorkflowDraftsPage } from "../designer/WorkflowDraftsPage";

type SurfaceKey = "workbench" | "designer" | "assets" | "audit" | "permission";
type ThemeMode = "light" | "dark";

type NavigationItem = {
  key: SurfaceKey;
  label: string;
  description: string;
  icon: typeof LayoutDashboard;
};

type Metric = {
  label: string;
  value: string;
  detail: string;
  tone: string;
  icon: typeof LayoutDashboard;
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

// 产品分区先用前端内存态切换，后续接入路由后应映射到 product-surfaces.md 中的推荐路径。
const navigationItems: NavigationItem[] = [
  {
    key: "workbench",
    label: "业务工作台",
    description: "待办、发起和结果",
    icon: LayoutDashboard,
  },
  {
    key: "designer",
    label: "流程设计",
    description: "画布与节点配置",
    icon: GitBranch,
  },
  {
    key: "assets",
    label: "能力资产",
    description: "智能体、Skills、MCP",
    icon: Library,
  },
  {
    key: "audit",
    label: "运行审计",
    description: "日志和证据链",
    icon: Activity,
  },
  {
    key: "permission",
    label: "权限管理",
    description: "角色、空间、授权",
    icon: ShieldCheck,
  },
];

// 页面标题与导航分离，避免后续接入权限后在多个位置重复维护同一组产品分区文案。
const pageTitles: Record<SurfaceKey, { title: string; eyebrow: string }> = {
  workbench: {
    title: "业务工作台",
    eyebrow: "阶段一：核心闭环",
  },
  designer: {
    title: "流程设计工作台",
    eyebrow: "阶段一：工作流定义管理",
  },
  assets: {
    title: "能力资产",
    eyebrow: "阶段二：资产治理",
  },
  audit: {
    title: "运行审计",
    eyebrow: "阶段一：执行证据链",
  },
  permission: {
    title: "权限管理",
    eyebrow: "阶段二：权限与治理",
  },
};

// 工作台数据当前用于撑起业务信息层级，后续由待办、运行记录和资产统计 API 替换。
const metrics: Metric[] = [
  {
    label: "待处理事项",
    value: "8",
    detail: "3 个审核，5 个补充输入",
    tone: "bg-amber-50 text-amber-700 ring-amber-200",
    icon: UserRoundCheck,
  },
  {
    label: "今日运行",
    value: "24",
    detail: "21 次完成，3 次暂停",
    tone: "bg-emerald-50 text-emerald-700 ring-emerald-200",
    icon: Activity,
  },
  {
    label: "已发布流程",
    value: "12",
    detail: "覆盖 5 类企业 SOP",
    tone: "bg-sky-50 text-sky-700 ring-sky-200",
    icon: GitBranch,
  },
  {
    label: "能力资产",
    value: "36",
    detail: "智能体、Skills 和 MCP",
    tone: "bg-violet-50 text-violet-700 ring-violet-200",
    icon: Library,
  },
];

// 待办项模拟了三类暂停点：用户输入、人工审核和交付确认，便于验证业务区不暴露画布也能完成处理。
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

// 模板卡片先展示 MVP 场景，后续接入工作流模板库后应带上模板版本和适用权限。
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

// 运行态摘要暂时展示最近运行位置，后续应来自 WorkflowRun 和 NodeRun 的聚合结果。
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
  // 当前还没有正式路由，先用本地状态模拟产品分区切换，保证设计区可以继续迭代。
  const [activeSurface, setActiveSurface] = useState<SurfaceKey>("workbench");
  const [themeMode, setThemeMode] = useState<ThemeMode>("light");
  const pageTitle = pageTitles[activeSurface];
  const isDarkMode = themeMode === "dark";

  return (
    <main className={`min-h-screen bg-slate-100 text-slate-950 transition-colors duration-200 dark:bg-slate-950 dark:text-slate-100 ${isDarkMode ? "dark" : ""}`}>
      <div className="flex min-h-screen">
        <aside className="hidden w-72 shrink-0 border-r border-slate-900/20 bg-slate-950 px-4 py-5 text-white lg:block">
          <div className="mb-8 flex items-center gap-3 px-2">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-emerald-500 text-slate-950 shadow-sm shadow-emerald-950/20">
              <Bot className="h-5 w-5" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-semibold">Agentum</p>
              <p className="text-xs text-slate-400">智能体装配式工作流</p>
            </div>
          </div>

          <nav className="space-y-1" aria-label="主导航">
            {navigationItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeSurface === item.key;

              return (
                <button
                  key={item.label}
                  type="button"
                  onClick={() => setActiveSurface(item.key)}
                  className={`flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors duration-200 ${
                    isActive
                      ? "bg-white text-slate-950 shadow-sm"
                      : "text-slate-300 hover:bg-white/10 hover:text-white"
                  }`}
                  title={item.description}
                >
                  <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{item.label}</span>
                    <span className={`block text-xs ${isActive ? "text-slate-500" : "text-slate-500"}`}>
                      {item.description}
                    </span>
                  </span>
                </button>
              );
            })}
          </nav>
        </aside>

        <section className="min-w-0 flex-1">
          <header className="border-b border-slate-200 bg-white/90 backdrop-blur dark:border-slate-800 dark:bg-slate-950/90">
            <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-5 md:flex-row md:items-center md:justify-between lg:px-8">
              <div>
                <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">{pageTitle.eyebrow}</p>
                <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-950 dark:text-white">{pageTitle.title}</h1>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => setThemeMode(isDarkMode ? "light" : "dark")}
                  className="inline-flex h-10 items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800"
                  aria-label={isDarkMode ? "切换到浅色模式" : "切换到深色模式"}
                >
                  {isDarkMode ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
                  {isDarkMode ? "浅色" : "深色"}
                </button>
                <button
                  type="button"
                  onClick={() => setActiveSurface("workbench")}
                  className="inline-flex h-10 items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800"
                >
                  <ClipboardList className="h-4 w-4" aria-hidden="true" />
                  我的待办
                </button>
                <button
                  type="button"
                  onClick={() => setActiveSurface("designer")}
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-emerald-700 px-3 text-sm font-semibold text-white shadow-sm shadow-emerald-900/20 transition-colors duration-200 hover:bg-emerald-800 dark:bg-emerald-600 dark:hover:bg-emerald-500"
                >
                  <GitBranch className="h-4 w-4" aria-hidden="true" />
                  设计流程
                </button>
              </div>
            </div>
          </header>

          {activeSurface === "workbench" ? (
            <div className="mx-auto max-w-7xl space-y-6 px-5 py-6 lg:px-8">
              <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900" aria-label="工作台总览">
                <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-center">
                  <div>
                    <p className="text-sm font-medium text-emerald-700">今日运行概览</p>
                    <h2 className="mt-2 text-2xl font-semibold tracking-tight text-slate-950 dark:text-white">
                      从待办、运行态和模板入口开始推进核心闭环
                    </h2>
                    <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">
                      业务区只展示用户需要处理的节点和交付状态，复杂画布留给流程设计工作台。
                    </p>
                  </div>
                  <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-4 dark:border-emerald-900/70 dark:bg-emerald-950/40">
                    <p className="text-sm font-semibold text-emerald-900 dark:text-emerald-200">MVP 进度</p>
                    <div className="mt-3 h-2 rounded-full bg-emerald-100">
                      <div className="h-2 w-2/5 rounded-full bg-emerald-700" />
                    </div>
                    <p className="mt-3 text-sm text-emerald-800 dark:text-emerald-200">工作台、列表和编辑器骨架已就绪，下一步接入真实草稿 API。</p>
                  </div>
                </div>
              </section>

              <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="关键指标">
                {metrics.map((metric) => {
                  const Icon = metric.icon;

                  return (
                    <article key={metric.label} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition-colors duration-200 hover:border-slate-300 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700">
                      <div className="flex items-center justify-between gap-3">
                        <p className="text-sm text-slate-500 dark:text-slate-400">{metric.label}</p>
                        <span className={`flex h-9 w-9 items-center justify-center rounded-lg ring-1 ${metric.tone}`}>
                          <Icon className="h-4 w-4" aria-hidden="true" />
                        </span>
                      </div>
                      <p className="mt-4 text-3xl font-semibold tracking-tight text-slate-950 dark:text-white">{metric.value}</p>
                      <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">{metric.detail}</p>
                    </article>
                  );
                })}
              </section>

              <div className="grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(340px,0.65fr)]">
              <section className="rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900" aria-labelledby="todo-title">
                <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
                  <div>
                    <h2 id="todo-title" className="text-base font-semibold text-slate-950 dark:text-white">
                      我的待办
                    </h2>
                    <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">需要输入、确认或审核的流程节点</p>
                  </div>
                  <UserRoundCheck className="h-5 w-5 text-amber-600" aria-hidden="true" />
                </div>
                <div className="divide-y divide-slate-100 dark:divide-slate-800">
                  {todoItems.map((item) => (
                    <article key={item.title} className="grid gap-3 px-5 py-4 md:grid-cols-[minmax(0,1fr)_160px] md:items-center">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 className="text-sm font-semibold text-slate-950 dark:text-white">{item.title}</h3>
                          <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
                            {item.status}
                          </span>
                        </div>
                        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">{item.workflow}</p>
                        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                          {item.owner} · {item.deadline}
                        </p>
                      </div>
                      <button
                        type="button"
                        className="inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-slate-300 px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-100 dark:hover:bg-slate-800"
                      >
                        <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
                        处理
                      </button>
                    </article>
                  ))}
                </div>
              </section>

              <section className="rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900" aria-labelledby="run-title">
                <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
                  <div>
                    <h2 id="run-title" className="text-base font-semibold text-slate-950 dark:text-white">
                      运行态摘要
                    </h2>
                    <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">当前流程位置和处理状态</p>
                  </div>
                  <Activity className="h-5 w-5 text-sky-700" aria-hidden="true" />
                </div>
                <div className="space-y-3 p-5">
                  {runRecords.map((record) => (
                    <article key={record.name} className="rounded-lg border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-950/60">
                      <div className="flex items-center justify-between gap-3">
                        <h3 className="min-w-0 text-sm font-semibold">{record.name}</h3>
                        <span className="shrink-0 rounded bg-white px-2 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-200 dark:ring-slate-700">
                          {record.state}
                        </span>
                      </div>
                      <p className="mt-3 text-sm text-slate-600 dark:text-slate-300">{record.node}</p>
                      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">已执行 {record.duration}</p>
                    </article>
                  ))}
                </div>
              </section>
            </div>

            <section className="rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900" aria-labelledby="template-title">
              <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
                <div>
                  <h2 id="template-title" className="text-base font-semibold text-slate-950 dark:text-white">
                    可用流程模板
                  </h2>
                  <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">从固定节点类型开始搭建业务流程</p>
                </div>
                <Archive className="h-5 w-5 text-violet-700" aria-hidden="true" />
              </div>
              <div className="grid gap-4 p-5 lg:grid-cols-3">
                {workflowTemplates.map((template) => (
                  <article key={template.title} className="rounded-lg border border-slate-200 bg-slate-50/60 p-4 transition-colors duration-200 hover:border-emerald-300 hover:bg-white dark:border-slate-800 dark:bg-slate-950/60 dark:hover:border-emerald-800 dark:hover:bg-slate-950">
                    <div className="flex items-center justify-between gap-3">
                      <span className="rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800">
                        {template.tag}
                      </span>
                      <span className="text-xs text-slate-500 dark:text-slate-400">{template.nodes}</span>
                    </div>
                    <h3 className="mt-4 text-sm font-semibold text-slate-950 dark:text-white">{template.title}</h3>
                    <p className="mt-2 min-h-12 text-sm leading-6 text-slate-600 dark:text-slate-300">{template.description}</p>
                    <button
                      type="button"
                      className="mt-4 inline-flex h-9 items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 text-sm font-medium text-slate-800 transition-colors duration-200 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800"
                    >
                      <FileText className="h-4 w-4" aria-hidden="true" />
                      查看模板
                    </button>
                  </article>
                ))}
              </div>
            </section>
            </div>
          ) : null}

          {activeSurface === "designer" ? <WorkflowDraftsPage /> : null}

          {activeSurface !== "workbench" && activeSurface !== "designer" ? (
            <PlaceholderSurface title={pageTitle.title} />
          ) : null}
        </section>
      </div>
    </main>
  );
}

function PlaceholderSurface({ title }: { title: string }) {
  return (
    <div className="mx-auto max-w-7xl px-5 py-6 lg:px-8">
      <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <p className="text-sm font-medium text-emerald-700">待开发模块</p>
        <h2 className="mt-2 text-lg font-semibold text-slate-950 dark:text-white">{title}</h2>
        <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600 dark:text-slate-300">
          当前阶段先打通工作流核心闭环，该区域会在后续阶段按 `docs/development-plan.md` 继续补齐。
        </p>
      </section>
    </div>
  );
}
