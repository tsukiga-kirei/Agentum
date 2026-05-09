import { useState } from "react";
import {
  Activity,
  Archive,
  CheckCircle2,
  ClipboardList,
  FileText,
  GitBranch,
  LayoutDashboard,
  Library,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
  ShieldCheck,
  User,
  UserRoundCheck,
} from "lucide-react";
import { TenantManagementPage } from "../admin/TenantManagementPage";
import { SystemManagementPage } from "../admin/SystemManagementPage";
import { AssetsPage } from "../assets/AssetsPage";
import { RunAuditPage } from "../audit/RunAuditPage";
import { WorkflowDraftsPage } from "../designer/WorkflowDraftsPage";
import { ThemeToggle } from "../../components/ThemeToggle";
import { AgentumMark } from "../../components/brand/AgentumMark";
import { useAuthStore } from "../../stores/authStore";
import type { UserRole } from "../../types/auth";

type SurfaceKey = "workbench" | "designer" | "assets" | "audit" | "tenant" | "system";

type NavigationItem = {
  key: SurfaceKey;
  label: string;
  description: string;
  icon: typeof LayoutDashboard;
  visibleFor?: UserRole[];
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
  startLabel: string;
};

type RunRecord = {
  name: string;
  state: string;
  node: string;
  duration: string;
};

// 产品分区先用前端内存态切换，后续接入路由后应映射到 system-overview.md 中的产品区域。
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
    visibleFor: ["designer", "agent_admin", "capability_admin", "tenant_admin", "system_admin"],
  },
  {
    key: "assets",
    label: "能力资产",
    description: "智能体、Skills、MCP",
    icon: Library,
    visibleFor: ["designer", "agent_admin", "capability_admin", "tenant_admin", "system_admin"],
  },
  {
    key: "audit",
    label: "运行审计",
    description: "只读证据链",
    icon: Activity,
    visibleFor: ["reviewer", "tenant_admin", "system_admin"],
  },
  {
    key: "tenant",
    label: "租户管理",
    description: "人员、角色、权限",
    icon: ShieldCheck,
    visibleFor: ["tenant_admin", "system_admin"],
  },
  {
    key: "system",
    label: "系统管理",
    description: "租户、模型、交付",
    icon: Settings,
    visibleFor: ["system_admin"],
  },
];

// 工作台数据当前用于撑起业务信息层级，后续由待办、运行记录和资产统计 API 替换。
const metrics: Metric[] = [
  {
    label: "待处理事项",
    value: "8",
    detail: "3 个审核，5 个补充输入",
    tone: "bg-amber-50 text-amber-700 ring-amber-200 dark:bg-amber-950/30 dark:text-amber-300 dark:ring-amber-800",
    icon: UserRoundCheck,
  },
  {
    label: "今日运行",
    value: "24",
    detail: "21 次完成，3 次暂停",
    tone: "bg-indigo-50 text-indigo-700 ring-indigo-200 dark:bg-indigo-950/30 dark:text-indigo-300 dark:ring-indigo-800",
    icon: Activity,
  },
  {
    label: "已发布流程",
    value: "12",
    detail: "覆盖 5 类企业 SOP",
    tone: "bg-sky-50 text-sky-700 ring-sky-200 dark:bg-sky-950/30 dark:text-sky-300 dark:ring-sky-800",
    icon: GitBranch,
  },
  {
    label: "能力资产",
    value: "36",
    detail: "智能体、Skills 和 MCP",
    tone: "bg-violet-50 text-violet-700 ring-violet-200 dark:bg-violet-950/30 dark:text-violet-300 dark:ring-violet-800",
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
    startLabel: "发起需求分析",
  },
  {
    title: "合同审查交付",
    description: "识别风险条款，汇总修改建议，审核通过后生成交付记录。",
    nodes: "8 个节点",
    tag: "法务",
    startLabel: "发起合同审查",
  },
  {
    title: "经营报告组装",
    description: "并行获取数据摘要，合并分析结论，输出报告草稿。",
    nodes: "9 个节点",
    tag: "经营",
    startLabel: "发起报告流程",
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

// 运行状态对应的颜色标记
const stateColors: Record<string, string> = {
  "运行中": "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300",
  "已暂停": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "已完成": "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
};

export function WorkbenchShell() {
  // 当前还没有正式路由，先用本地状态模拟产品分区切换，保证设计区可以继续迭代。
  const [activeSurface, setActiveSurface] = useState<SurfaceKey>("workbench");
  // 侧栏折叠属于工作台级偏好，后续接入用户设置 API 后应从服务端恢复并跨设备同步。
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const themeMode = useAuthStore((s) => s.themeMode);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const isDarkMode = themeMode === "dark";
  const currentRole = user?.role ?? "executor";
  const canDesignWorkflow = ["designer", "agent_admin", "capability_admin", "tenant_admin", "system_admin"].includes(currentRole);
  const canOpenSystemManagement = currentRole === "system_admin";
  const visibleNavigationItems = navigationItems.filter((item) => !item.visibleFor || item.visibleFor.includes(currentRole));

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      <div className="flex min-h-screen">
        {/* ===== 侧边栏 ===== */}
        <aside className={`hidden shrink-0 sticky top-0 h-screen max-h-screen overflow-hidden bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color] duration-300 lg:flex lg:flex-col ${isSidebarCollapsed ? "w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}>
          {/* Logo 区 */}
          <div className={`flex h-[var(--header-height)] items-center gap-3 px-5 ${isSidebarCollapsed ? "justify-center px-0" : ""}`}>
            <div className="flex h-9 w-9 items-center justify-center overflow-hidden rounded-lg shadow-sm">
              <AgentumMark className="h-9 w-9" />
            </div>
            <div className={isSidebarCollapsed ? "hidden" : ""}>
              <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
            </div>
          </div>

          {/* 导航菜单 */}
          <nav className="flex-1 overflow-y-auto min-h-0 space-y-1 px-3 py-3" aria-label="主导航">
            <p className={`px-3 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--color-sidebar-section-title)] ${isSidebarCollapsed ? "sr-only" : ""}`}>
              主工作区
            </p>
            {visibleNavigationItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeSurface === item.key;

              return (
                <button
                  key={item.label}
                  type="button"
                  onClick={() => setActiveSurface(item.key)}
                  className={`relative flex w-full items-center rounded-lg text-left transition-all duration-200 ${isSidebarCollapsed ? "h-11 justify-center px-0" : "gap-3 px-3 py-2.5"} ${
                    isActive
                      ? "bg-[var(--color-bg-sidebar-active)] font-medium text-[var(--color-text-sidebar-active)]"
                      : "text-[var(--color-text-sidebar)] hover:bg-[var(--color-bg-sidebar-hover)] hover:text-[var(--color-text-primary)]"
                  }`}
                  title={item.description}
                >
                  <Icon className={`h-5 w-5 shrink-0 ${isActive ? "text-[var(--color-primary)]" : ""}`} aria-hidden="true" />
                  <span className={`min-w-0 ${isSidebarCollapsed ? "hidden" : ""}`}>
                    <span className="block text-sm font-medium">{item.label}</span>
                    <span className="block text-xs text-[var(--color-text-tertiary)]">{item.description}</span>
                  </span>
                  {isActive ? <span className="absolute right-0 top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-l bg-[var(--color-primary)]" /> : null}
                </button>
              );
            })}
          </nav>

          {/* 底部用户区域 */}
          <div className={`border-t border-[var(--color-border-light)] p-3 ${isSidebarCollapsed ? "flex justify-center" : ""}`}>
            {isSidebarCollapsed ? (
              <button
                type="button"
                onClick={logout}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-[var(--color-text-tertiary)] hover:bg-[var(--color-bg-hover)] hover:text-[var(--color-danger)]"
                title="退出登录"
              >
                <LogOut className="h-4 w-4" />
              </button>
            ) : (
              <div className="flex items-center gap-3">
                <div className="flex h-9 w-9 items-center justify-center rounded-full bg-[var(--color-primary-bg)] text-[var(--color-primary)]">
                  <User className="h-4.5 w-4.5" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">{user?.displayName ?? "未登录"}</p>
                  <p className="truncate text-xs text-[var(--color-text-tertiary)]">{user?.organization ?? ""}</p>
                </div>
                <button
                  type="button"
                  onClick={logout}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-[var(--color-text-tertiary)] hover:bg-[var(--color-bg-hover)] hover:text-[var(--color-danger)]"
                  title="退出登录"
                >
                  <LogOut className="h-4 w-4" />
                </button>
              </div>
            )}
          </div>
        </aside>

        {/* ===== 主内容区 ===== */}
        <section className="min-w-0 flex-1">
          {/* 顶部操作栏 —— 去除了与下方工作区的分割线，同色融合 */}
          <header className="bg-[var(--color-bg-page)]">
            <div className="mx-auto flex min-h-[var(--header-height)] max-w-[1400px] items-center justify-between gap-3 px-5 lg:px-6">
              <div className="flex min-w-0 items-center gap-3">
                <button
                  type="button"
                  onClick={() => setIsSidebarCollapsed((current) => !current)}
                  className="agent-button hidden h-8 w-8 shrink-0 px-0 lg:inline-flex"
                  aria-label={isSidebarCollapsed ? "展开左侧导航" : "收起左侧导航"}
                >
                  {isSidebarCollapsed ? <PanelLeftOpen className="h-4 w-4" aria-hidden="true" /> : <PanelLeftClose className="h-4 w-4" aria-hidden="true" />}
                </button>
              </div>
              <div className="flex items-center gap-2">
                {/* 主题切换药丸（与 AuraOA 一致） */}
                <ThemeToggle />
                <button
                  type="button"
                  onClick={() => setActiveSurface("workbench")}
                  className="agent-button h-8 px-3 text-[13px]"
                >
                  <ClipboardList className="h-3.5 w-3.5" aria-hidden="true" />
                  我的待办
                </button>
                {canDesignWorkflow ? (
                  <button
                    type="button"
                    onClick={() => setActiveSurface("designer")}
                    className="agent-button agent-button-primary h-8 px-3 text-[13px]"
                  >
                    <GitBranch className="h-3.5 w-3.5" aria-hidden="true" />
                    设计流程
                  </button>
                ) : null}
                {canOpenSystemManagement ? (
                  <button
                    type="button"
                    onClick={() => setActiveSurface("system")}
                    className="agent-button h-8 px-3 text-[13px]"
                  >
                    <Settings className="h-3.5 w-3.5" aria-hidden="true" />
                    系统管理
                  </button>
                ) : null}
              </div>
            </div>
          </header>

          {/* 业务工作台内容 */}
          {activeSurface === "workbench" ? (
            <div className="mx-auto max-w-[1400px] space-y-4 px-5 py-4 lg:px-6">
              {/* 概览卡片 */}
              <section className="agent-card p-5" aria-label="工作台总览">
                <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px] xl:items-center">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-[var(--color-primary)]">今日运行概览</p>
                    <h2 className="mt-2 text-lg font-semibold text-[var(--color-text-primary)]">
                      从待办、运行态和模板入口开始推进核心闭环
                    </h2>
                    <p className="agent-muted mt-2 max-w-3xl text-sm leading-6">
                      业务区只展示用户需要处理的节点和交付状态，复杂画布留给流程设计工作台。
                    </p>
                  </div>
                  <div className="rounded-[var(--radius-md)] border border-indigo-200 bg-indigo-50 p-4 dark:border-indigo-800/40 dark:bg-indigo-950/30">
                    <p className="text-sm font-semibold text-indigo-900 dark:text-indigo-100">MVP 进度</p>
                    <div className="mt-3 h-1.5 rounded-full bg-indigo-100 dark:bg-indigo-900/50">
                      <div className="h-1.5 w-2/5 rounded-full bg-[var(--color-primary)]" />
                    </div>
                    <p className="mt-3 text-xs text-indigo-700 dark:text-indigo-200">工作台、列表和编辑器骨架已就绪，下一步接入真实草稿 API。</p>
                  </div>
                </div>
              </section>

              {/* 指标卡片 */}
              <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="关键指标">
                {metrics.map((metric) => {
                  const Icon = metric.icon;

                  return (
                    <article key={metric.label} className="agent-card p-4">
                      <div className="flex items-center justify-between gap-3">
                        <p className="text-xs text-[var(--color-text-secondary)]">{metric.label}</p>
                        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ring-1 ${metric.tone}`}>
                          <Icon className="h-4 w-4" aria-hidden="true" />
                        </span>
                      </div>
                      <p className="mt-3 text-2xl font-semibold text-[var(--color-text-primary)]">{metric.value}</p>
                      <p className="agent-muted mt-1.5 text-xs">{metric.detail}</p>
                    </article>
                  );
                })}
              </section>

              {/* 待办 + 运行态 */}
              <div className="grid gap-4 xl:grid-cols-[minmax(0,1.35fr)_minmax(340px,0.65fr)]">
                <section className="agent-card" aria-labelledby="todo-title">
                  <div className="agent-card-header flex items-center justify-between">
                    <div>
                      <h2 id="todo-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
                        我的待办
                      </h2>
                      <p className="agent-muted mt-0.5 text-xs">流程已暂停，并且现在轮到我处理</p>
                    </div>
                    <UserRoundCheck className="h-4 w-4 text-amber-600" aria-hidden="true" />
                  </div>
                  <div className="divide-y divide-[var(--color-border-light)]">
                    {todoItems.map((item) => (
                      <article key={item.title} className="grid gap-3 px-4 py-3.5 md:grid-cols-[minmax(0,1fr)_120px] md:items-center">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <h3 className="text-sm font-medium text-[var(--color-text-primary)]">{item.title}</h3>
                            <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
                              {item.status}
                            </span>
                          </div>
                          <p className="agent-muted mt-1.5 text-xs">{item.workflow}</p>
                          <p className="mt-0.5 text-[11px] text-[var(--color-text-tertiary)]">
                            {item.owner} · {item.deadline}
                          </p>
                        </div>
                        <button
                          type="button"
                          className="agent-button h-8 px-2.5 text-xs"
                        >
                          <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
                          处理
                        </button>
                      </article>
                    ))}
                  </div>
                </section>

                <section className="agent-card" aria-labelledby="run-title">
                  <div className="agent-card-header flex items-center justify-between">
                    <div>
                      <h2 id="run-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
                        运行态摘要
                      </h2>
                      <p className="agent-muted mt-0.5 text-xs">我有权限查看的流程位置，不一定需要我处理</p>
                    </div>
                    <Activity className="h-4 w-4 text-sky-600" aria-hidden="true" />
                  </div>
                  <div className="space-y-2.5 p-4">
                    {runRecords.map((record) => (
                      <article key={record.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                        <div className="flex items-center justify-between gap-3">
                          <h3 className="min-w-0 text-xs font-medium">{record.name}</h3>
                          <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.state] ?? ""}`}>
                            {record.state}
                          </span>
                        </div>
                        <p className="agent-muted mt-2 text-xs">{record.node}</p>
                        <p className="mt-0.5 text-[11px] text-[var(--color-text-tertiary)]">已执行 {record.duration}</p>
                      </article>
                    ))}
                  </div>
                </section>
              </div>

              {/* 流程模板 */}
              <section className="agent-card" aria-labelledby="template-title">
                <div className="agent-card-header flex items-center justify-between">
                  <div>
                    <h2 id="template-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
                      可用流程模板
                    </h2>
                    <p className="agent-muted mt-0.5 text-xs">点击后创建一次流程运行，进入业务步骤页</p>
                  </div>
                  <Archive className="h-4 w-4 text-violet-600" aria-hidden="true" />
                </div>
                <div className="grid gap-3 p-4 lg:grid-cols-3">
                  {workflowTemplates.map((template) => (
                    <article key={template.title} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 transition-all duration-200 hover:border-[var(--color-primary)] hover:bg-[var(--color-bg-card)] hover:shadow-sm">
                      <div className="flex items-center justify-between gap-3">
                        <span className="rounded bg-indigo-100 px-1.5 py-0.5 text-[11px] font-medium text-indigo-800 dark:bg-indigo-900/40 dark:text-indigo-300">
                          {template.tag}
                        </span>
                        <span className="text-[11px] text-[var(--color-text-tertiary)]">{template.nodes}</span>
                      </div>
                      <h3 className="mt-3 text-sm font-medium text-[var(--color-text-primary)]">{template.title}</h3>
                      <p className="agent-muted mt-1.5 min-h-10 text-xs leading-5">{template.description}</p>
                      <button
                        type="button"
                        className="agent-button agent-button-primary mt-3 h-8 px-2.5 text-xs"
                      >
                        <FileText className="h-3.5 w-3.5" aria-hidden="true" />
                        {template.startLabel}
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

          {activeSurface === "tenant" ? <TenantManagementPage /> : null}

          {activeSurface === "system" ? <SystemManagementPage /> : null}
        </section>
      </div>
    </main>
  );
}
