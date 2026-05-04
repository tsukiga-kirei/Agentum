import { useEffect, useState } from "react";
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
  PanelLeftClose,
  PanelLeftOpen,
  ShieldCheck,
  Sun,
  UserRoundCheck,
} from "lucide-react";
import { PermissionPage } from "../admin/PermissionPage";
import { AssetsPage } from "../assets/AssetsPage";
import { RunAuditPage } from "../audit/RunAuditPage";
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
    eyebrow: "阶段一：智能体与能力资产",
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
    tone: "bg-indigo-50 text-indigo-700 ring-indigo-200",
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
  // 侧栏折叠属于工作台级偏好，后续接入用户设置 API 后应从服务端恢复并跨设备同步。
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [themeMode, setThemeMode] = useState<ThemeMode>("light");
  const pageTitle = pageTitles[activeSurface];
  const isDarkMode = themeMode === "dark";

  useEffect(() => {
    const savedThemeMode = window.localStorage.getItem("agentum_theme_mode");

    if (savedThemeMode === "dark" || savedThemeMode === "light") {
      setThemeMode(savedThemeMode);
    }
  }, []);

  useEffect(() => {
    // 同步 data-theme 方便后续接入图表、弹窗或第三方组件时复用 AuraOA 同类主题变量。
    document.documentElement.setAttribute("data-theme", themeMode);
    window.localStorage.setItem("agentum_theme_mode", themeMode);
  }, [themeMode]);

  function handleToggleTheme() {
    const nextThemeMode: ThemeMode = isDarkMode ? "light" : "dark";
    const overlay = document.createElement("div");

    // 切换主题时用轻量遮罩承接色彩变化，避免大面积背景和画布同时变色时产生闪烁。
    overlay.style.cssText = `
      position: fixed; inset: 0; z-index: 99999;
      pointer-events: none;
      background: ${nextThemeMode === "dark" ? "rgba(15, 23, 42, 0.38)" : "rgba(248, 250, 252, 0.52)"};
      opacity: 0;
      transition: opacity 0.42s cubic-bezier(0.4, 0, 0.2, 1);
    `;
    document.body.appendChild(overlay);

    requestAnimationFrame(() => {
      overlay.style.opacity = "1";
    });
    window.setTimeout(() => setThemeMode(nextThemeMode), 180);
    window.setTimeout(() => {
      overlay.style.opacity = "0";
    }, 330);
    window.setTimeout(() => overlay.remove(), 760);
  }

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      <div className="flex min-h-screen">
        <aside className={`hidden shrink-0 border-r border-[var(--color-sidebar-border)] bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color,border-color] duration-300 lg:block ${isSidebarCollapsed ? "w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}>
          <div className={`flex h-[var(--header-height)] items-center gap-3 border-b border-[var(--color-sidebar-border)] px-5 ${isSidebarCollapsed ? "justify-center px-0" : ""}`}>
            <div className="flex h-9 w-9 items-center justify-center rounded-[10px] bg-[var(--color-bg-hover)] text-[var(--color-primary)]">
              <Bot className="h-5 w-5" aria-hidden="true" />
            </div>
            <div className={isSidebarCollapsed ? "hidden" : ""}>
              <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
              <p className="text-xs text-[var(--color-text-tertiary)]">智能体装配式工作流</p>
            </div>
          </div>

          <nav className="space-y-1 px-2 py-3" aria-label="主导航">
            <p className={`px-4 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--color-sidebar-section-title)] ${isSidebarCollapsed ? "sr-only" : ""}`}>
              主工作区
            </p>
            {navigationItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeSurface === item.key;

              return (
                <button
                  key={item.label}
                  type="button"
                  onClick={() => setActiveSurface(item.key)}
                  className={`relative flex h-11 w-full items-center rounded-[10px] text-left transition-colors duration-200 ${isSidebarCollapsed ? "justify-center px-0" : "gap-3 px-4"} ${
                    isActive
                      ? "bg-[var(--color-bg-sidebar-active)] text-[var(--color-text-sidebar-active)]"
                      : "text-[var(--color-text-sidebar)] hover:bg-[var(--color-bg-sidebar-hover)] hover:text-[var(--color-text-primary)]"
                  }`}
                  title={item.description}
                >
                  <Icon className={`h-5 w-5 shrink-0 ${isActive ? "text-[var(--color-primary)]" : ""}`} aria-hidden="true" />
                  <span className={`min-w-0 ${isSidebarCollapsed ? "hidden" : ""}`}>
                    <span className="block text-sm font-medium">{item.label}</span>
                    <span className="block text-xs text-[var(--color-text-tertiary)]">
                      {item.description}
                    </span>
                  </span>
                  {isActive ? <span className="absolute right-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-l bg-[var(--color-primary)]" /> : null}
                </button>
              );
            })}
          </nav>
        </aside>

        <section className="min-w-0 flex-1">
          <header className="border-b border-[var(--color-border-light)] bg-[var(--color-bg-card)]/95 backdrop-blur">
            <div className="mx-auto flex min-h-[var(--header-height)] max-w-[1400px] flex-col gap-3 px-5 py-4 md:flex-row md:items-center md:justify-between lg:px-6">
              <div className="flex min-w-0 items-center gap-3">
                <button
                  type="button"
                  onClick={() => setIsSidebarCollapsed((current) => !current)}
                  className="agent-button hidden h-9 w-9 shrink-0 px-0 lg:inline-flex"
                  aria-label={isSidebarCollapsed ? "展开左侧导航" : "收起左侧导航"}
                >
                  {isSidebarCollapsed ? <PanelLeftOpen className="h-4 w-4" aria-hidden="true" /> : <PanelLeftClose className="h-4 w-4" aria-hidden="true" />}
                </button>
                <div className="min-w-0">
                  <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[var(--color-primary)]">{pageTitle.eyebrow}</p>
                  <h1 className="mt-1 truncate text-xl font-semibold text-[var(--color-text-primary)]">{pageTitle.title}</h1>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={handleToggleTheme}
                  className="relative inline-flex h-9 w-[58px] items-center rounded-full border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-0.5 transition-colors duration-300 hover:border-[var(--color-primary)]"
                  aria-label={isDarkMode ? "切换到浅色模式" : "切换到深色模式"}
                >
                  <span className="pointer-events-none absolute left-2 text-[var(--color-text-tertiary)]">
                    <Sun className="h-3.5 w-3.5" aria-hidden="true" />
                  </span>
                  <span className="pointer-events-none absolute right-2 text-[var(--color-text-tertiary)]">
                    <Moon className="h-3.5 w-3.5" aria-hidden="true" />
                  </span>
                  <span className={`relative z-10 flex h-7 w-7 items-center justify-center rounded-full bg-[var(--color-primary)] text-white shadow-sm transition-transform duration-300 ${isDarkMode ? "translate-x-6" : "translate-x-0"}`}>
                    {isDarkMode ? <Moon className="h-4 w-4" aria-hidden="true" /> : <Sun className="h-4 w-4" aria-hidden="true" />}
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => setActiveSurface("workbench")}
                  className="agent-button h-10 px-3 text-sm"
                >
                  <ClipboardList className="h-4 w-4" aria-hidden="true" />
                  我的待办
                </button>
                <button
                  type="button"
                  onClick={() => setActiveSurface("designer")}
                  className="agent-button agent-button-primary h-10 px-3 text-sm"
                >
                  <GitBranch className="h-4 w-4" aria-hidden="true" />
                  设计流程
                </button>
              </div>
            </div>
          </header>

          {activeSurface === "workbench" ? (
            <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
              <section className="agent-card p-5" aria-label="工作台总览">
                <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-center">
                  <div>
                    <p className="text-sm font-medium text-[var(--color-primary)]">今日运行概览</p>
                    <h2 className="mt-2 text-xl font-semibold text-[var(--color-text-primary)]">
                      从待办、运行态和模板入口开始推进核心闭环
                    </h2>
                    <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
                      业务区只展示用户需要处理的节点和交付状态，复杂画布留给流程设计工作台。
                    </p>
                  </div>
                  <div className="rounded-[var(--radius-md)] border border-indigo-200 bg-indigo-50 p-4 dark:border-indigo-900/60 dark:bg-indigo-950/30">
                    <p className="text-sm font-semibold text-indigo-950 dark:text-indigo-100">MVP 进度</p>
                    <div className="mt-3 h-2 rounded-full bg-indigo-100 dark:bg-indigo-950">
                      <div className="h-2 w-2/5 rounded-full bg-[var(--color-primary)]" />
                    </div>
                    <p className="mt-3 text-sm text-indigo-800 dark:text-indigo-100">工作台、列表和编辑器骨架已就绪，下一步接入真实草稿 API。</p>
                  </div>
                </div>
              </section>

              <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="关键指标">
                {metrics.map((metric) => {
                  const Icon = metric.icon;

                  return (
                    <article key={metric.label} className="agent-card p-4">
                      <div className="flex items-center justify-between gap-3">
                        <p className="text-sm text-[var(--color-text-secondary)]">{metric.label}</p>
                        <span className={`flex h-9 w-9 items-center justify-center rounded-lg ring-1 ${metric.tone}`}>
                          <Icon className="h-4 w-4" aria-hidden="true" />
                        </span>
                      </div>
                      <p className="mt-4 text-3xl font-semibold text-[var(--color-text-primary)]">{metric.value}</p>
                      <p className="agent-muted mt-2 text-sm">{metric.detail}</p>
                    </article>
                  );
                })}
              </section>

              <div className="grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(340px,0.65fr)]">
              <section className="agent-card" aria-labelledby="todo-title">
                <div className="agent-card-header flex items-center justify-between">
                  <div>
                    <h2 id="todo-title" className="text-base font-semibold text-[var(--color-text-primary)]">
                      我的待办
                    </h2>
                    <p className="agent-muted mt-1 text-sm">需要输入、确认或审核的流程节点</p>
                  </div>
                  <UserRoundCheck className="h-5 w-5 text-amber-600" aria-hidden="true" />
                </div>
                <div className="divide-y divide-[var(--color-border-light)]">
                  {todoItems.map((item) => (
                    <article key={item.title} className="grid gap-3 px-5 py-4 md:grid-cols-[minmax(0,1fr)_160px] md:items-center">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{item.title}</h3>
                          <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
                            {item.status}
                          </span>
                        </div>
                        <p className="agent-muted mt-2 text-sm">{item.workflow}</p>
                        <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">
                          {item.owner} · {item.deadline}
                        </p>
                      </div>
                      <button
                        type="button"
                        className="agent-button h-9 px-3 text-sm"
                      >
                        <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
                        处理
                      </button>
                    </article>
                  ))}
                </div>
              </section>

              <section className="agent-card" aria-labelledby="run-title">
                <div className="agent-card-header flex items-center justify-between">
                  <div>
                    <h2 id="run-title" className="text-base font-semibold text-[var(--color-text-primary)]">
                      运行态摘要
                    </h2>
                    <p className="agent-muted mt-1 text-sm">当前流程位置和处理状态</p>
                  </div>
                  <Activity className="h-5 w-5 text-sky-700" aria-hidden="true" />
                </div>
                <div className="space-y-3 p-5">
                  {runRecords.map((record) => (
                    <article key={record.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
                      <div className="flex items-center justify-between gap-3">
                        <h3 className="min-w-0 text-sm font-semibold">{record.name}</h3>
                        <span className="shrink-0 rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                          {record.state}
                        </span>
                      </div>
                      <p className="agent-muted mt-3 text-sm">{record.node}</p>
                      <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">已执行 {record.duration}</p>
                    </article>
                  ))}
                </div>
              </section>
            </div>

            <section className="agent-card" aria-labelledby="template-title">
              <div className="agent-card-header flex items-center justify-between">
                <div>
                  <h2 id="template-title" className="text-base font-semibold text-[var(--color-text-primary)]">
                    可用流程模板
                  </h2>
                  <p className="agent-muted mt-1 text-sm">从固定节点类型开始搭建业务流程</p>
                </div>
                <Archive className="h-5 w-5 text-violet-700" aria-hidden="true" />
              </div>
              <div className="grid gap-4 p-5 lg:grid-cols-3">
                {workflowTemplates.map((template) => (
                  <article key={template.title} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 transition-colors duration-200 hover:border-[var(--color-primary)] hover:bg-[var(--color-bg-card)]">
                    <div className="flex items-center justify-between gap-3">
                      <span className="rounded bg-indigo-100 px-2 py-1 text-xs font-medium text-indigo-800">
                        {template.tag}
                      </span>
                      <span className="text-xs text-[var(--color-text-tertiary)]">{template.nodes}</span>
                    </div>
                    <h3 className="mt-4 text-sm font-semibold text-[var(--color-text-primary)]">{template.title}</h3>
                    <p className="agent-muted mt-2 min-h-12 text-sm leading-6">{template.description}</p>
                    <button
                      type="button"
                      className="agent-button mt-4 h-9 px-3 text-sm"
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

          {activeSurface === "assets" ? <AssetsPage /> : null}

          {activeSurface === "audit" ? <RunAuditPage /> : null}

          {activeSurface === "permission" ? <PermissionPage /> : null}
        </section>
      </div>
    </main>
  );
}
