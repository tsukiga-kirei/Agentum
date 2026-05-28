import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertCircle,
  ArrowRight,
  Archive,
  CheckCircle2,
  GitBranch,
  History,
  LayoutDashboard,
  Library,
  ListTodo,
  Loader2,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  PauseCircle,
  PlayCircle,
  Search,
  Settings,
  ShieldAlert,
  ShieldCheck,
  User,
  UserRoundCheck,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Pagination, Segmented, message } from "antd";
import { TenantManagementPage } from "../admin/TenantManagementPage";
import { SystemManagementPage } from "../admin/SystemManagementPage";
import { AssetsPage } from "../assets/AssetsPage";
import { WorkflowDraftsPage } from "../designer/WorkflowDraftsPage";
import { ThemeToggle } from "../../components/ThemeToggle";
import { RoleSwitcher } from "../../components/RoleSwitcher";
import { AgentumMark } from "../../components/brand/AgentumMark";
import { useAuthStore } from "../../stores/authStore";
import { AgentumApiError, workbenchApi } from "../../services/apiClient";
import type {
  WorkbenchAvailableWorkflowRow,
  WorkbenchPendingTodoRow,
  WorkbenchRecentRunRow,
  WorkbenchSummary,
} from "../../types/workbench";

type SurfaceKey = "workbench" | "designer" | "assets" | "tenant" | "system";
type WorkbenchTab = "overview" | "create" | "tasks";

// 图标映射：后端返回菜单 icon 字符串，前端映射为 lucide-react 组件
const ICON_MAP: Record<string, typeof LayoutDashboard> = {
  LayoutDashboard,
  GitBranch,
  Library,
  Activity,
  ShieldCheck,
  Settings,
};

type MetricTone = "primary" | "success" | "info" | "cap";

type MetricCard = {
  label: string;
  value: string;
  hint?: string;
  tone: MetricTone;
  icon: LucideIcon;
};

type WorkbenchTabMeta = {
  key: WorkbenchTab;
  label: string;
  icon: LucideIcon;
  description: string;
};

// 业务工作台运行态状态：第一阶段后端 runtimeAvailable 固定为 false，
// 前端按此标记展示“运行态建设中”空态，等待运行实例 API 上线。

const workbenchTabs: WorkbenchTabMeta[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, description: "查看今日待办、可创建流程和运行态概况" },
  { key: "create", label: "创建任务", icon: PlayCircle, description: "浏览全部开放智能体流程，有权限的流程可创建任务" },
  { key: "tasks", label: "任务中心", icon: ListTodo, description: "合并查看待办、运行中、暂停和历史完成任务" },
];

// 运行状态对应的颜色标记（保留供后续运行态接入后直接复用）
const stateColors: Record<string, string> = {
  "运行中": "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300",
  "已暂停": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "已完成": "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  "等待人工审核": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "等待用户输入": "bg-indigo-100 text-indigo-800 dark:bg-indigo-900/40 dark:text-indigo-300",
  "等待交付确认": "bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300",
};

const AVAILABLE_PAGE_SIZE = 12;

export function WorkbenchShell() {
  // 菜单来自后端（通过 authStore.menus），不再前端硬编码 visibleFor。
  // 切换角色后后端返回新的 menus，前端自动更新导航。
  const menus = useAuthStore((s) => s.menus);
  const themeMode = useAuthStore((s) => s.themeMode);
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const logout = useAuthStore((s) => s.logout);
  const tenantId = user?.tenantId ?? null;
  const isDarkMode = themeMode === "dark";
  const [messageApi, messageContextHolder] = message.useMessage();

  // 根据菜单列表确定初始页面（第一个菜单项）
  const [activeSurface, setActiveSurface] = useState<SurfaceKey | null>(() => {
    const firstMenu = menus[0];
    return firstMenu ? (firstMenu.key as SurfaceKey) : null;
  });

  useEffect(() => {
    if (menus.length === 0) {
      setActiveSurface(null);
      return;
    }

    if (!menus.some((menu) => menu.key === activeSurface)) {
      setActiveSurface(menus[0].key as SurfaceKey);
    }
  }, [activeSurface, menus]);

  // 侧栏折叠属于工作台级偏好，后续接入用户设置 API 后应从服务端恢复并跨设备同步。
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isSidebarTransitioning, setIsSidebarTransitioning] = useState(false);
  const [activeWorkbenchTab, setActiveWorkbenchTab] = useState<WorkbenchTab>("overview");
  const sidebarTransitionTimer = useRef<number | null>(null);
  const isSidebarCompact = isSidebarCollapsed || isSidebarTransitioning;
  const showSidebarText = !isSidebarCompact;
  const activeWorkbenchTabMeta = workbenchTabs.find((tab) => tab.key === activeWorkbenchTab) ?? workbenchTabs[0];
  const workbenchSegmentedOptions = workbenchTabs.map((tab) => {
    const Icon = tab.icon;
    return {
      value: tab.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden="true" />
          <span>{tab.label}</span>
        </span>
      ),
    };
  });

  // 业务工作台真实数据：概览统计、待办、最近运行均由 /api/tenants/{tenantId}/workbench/summary 返回。
  const [summary, setSummary] = useState<WorkbenchSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  // 可发起的已发布工作流来自后端分页查询，结合 keyword 在前端搜索。
  const [availableWorkflows, setAvailableWorkflows] = useState<WorkbenchAvailableWorkflowRow[]>([]);
  const [availableTotal, setAvailableTotal] = useState(0);
  const [availablePage, setAvailablePage] = useState(1);
  const [availableLoading, setAvailableLoading] = useState(false);
  const [availableKeyword, setAvailableKeyword] = useState("");
  const [availableKeywordInput, setAvailableKeywordInput] = useState("");
  const [availableError, setAvailableError] = useState<string | null>(null);

  useEffect(() => () => {
    if (sidebarTransitionTimer.current !== null) {
      window.clearTimeout(sidebarTransitionTimer.current);
    }
  }, []);

  // 仅当业务工作台 surface 处于激活态、并且已有有效 tenantId / token 时，才发起概览请求。
  // 系统管理员入口没有 tenantId，业务工作台暂不为系统管理员渲染。
  const loadSummary = useCallback(async () => {
    if (!tenantId || !token) {
      setSummary(null);
      return;
    }

    setSummaryLoading(true);
    setSummaryError(null);
    try {
      const data = await workbenchApi.summary(tenantId, token);
      setSummary(data);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "业务工作台概览加载失败";
      console.warn("[workbench] 概览加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      setSummaryError(reason);
      setSummary(null);
    } finally {
      setSummaryLoading(false);
    }
  }, [tenantId, token]);

  const loadAvailableWorkflows = useCallback(async (page: number, keyword: string) => {
    if (!tenantId || !token) {
      setAvailableWorkflows([]);
      setAvailableTotal(0);
      return;
    }

    setAvailableLoading(true);
    setAvailableError(null);
    try {
      const data = await workbenchApi.listAvailableWorkflows(tenantId, token, keyword, page, AVAILABLE_PAGE_SIZE);
      setAvailableWorkflows(data.items);
      setAvailableTotal(data.total);
      setAvailablePage(data.page);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "可发起流程加载失败";
      console.warn("[workbench] 可发起流程加载失败", {
        code: error instanceof AgentumApiError ? error.code : "unknown",
      });
      setAvailableError(reason);
      setAvailableWorkflows([]);
      setAvailableTotal(0);
    } finally {
      setAvailableLoading(false);
    }
  }, [tenantId, token]);

  useEffect(() => {
    if (activeSurface !== "workbench") {
      return;
    }
    void loadSummary();
  }, [activeSurface, loadSummary]);

  useEffect(() => {
    if (activeSurface !== "workbench") {
      return;
    }
    void loadAvailableWorkflows(availablePage, availableKeyword);
  }, [activeSurface, availableKeyword, availablePage, loadAvailableWorkflows]);

  function handleToggleSidebar() {
    if (sidebarTransitionTimer.current !== null) {
      window.clearTimeout(sidebarTransitionTimer.current);
    }
    setIsSidebarTransitioning(true);
    setIsSidebarCollapsed((current) => !current);
    sidebarTransitionTimer.current = window.setTimeout(() => {
      setIsSidebarTransitioning(false);
      sidebarTransitionTimer.current = null;
    }, 320);
  }

  function handleCreateTask(workflow: WorkbenchAvailableWorkflowRow) {
    // 第一阶段尚未接入运行实例 API，仅给出明确提示，避免误以为流程已经真正启动。
    messageApi.info(`「${workflow.name}」 v${workflow.latestVersionNumber} 的运行态正在建设中，发起入口将与运行实例 API 一同上线`);
  }

  function handleSubmitKeyword() {
    const trimmed = availableKeywordInput.trim();
    setAvailableKeyword(trimmed);
    setAvailablePage(1);
  }

  // 概览指标卡片基于真实 summary.metrics 渲染；运行态相关指标在 runtimeAvailable=false 时显示“—”。
  const metricCards: MetricCard[] = useMemo(() => {
    const metrics = summary?.metrics;
    const runtimeReady = summary?.runtimeAvailable ?? false;
    return [
      {
        label: "我的待办",
        value: runtimeReady ? String(metrics?.pendingTodoTotal ?? 0) : "—",
        hint: runtimeReady ? "需要我处理的暂停点" : "运行态建设中",
        tone: "primary",
        icon: UserRoundCheck,
      },
      {
        label: "进行中任务",
        value: runtimeReady ? String(metrics?.runningRunTotal ?? 0) : "—",
        hint: runtimeReady ? "我可以查看的运行实例" : "运行态建设中",
        tone: "info",
        icon: Activity,
      },
      {
        label: "已发布流程",
        value: metrics ? String(metrics.publishedWorkflowTotal) : "—",
        hint: metrics ? `${metrics.availableWorkflowTotal} 个对当前账号开放` : "加载中",
        tone: "success",
        icon: GitBranch,
      },
      {
        label: "可用能力",
        value: metrics ? String(metrics.openedCapabilityTotal) : "—",
        hint: metrics ? `我自建草稿 ${metrics.myAssetTotal} 个` : "加载中",
        tone: "cap",
        icon: Library,
      },
    ];
  }, [summary]);

  const pendingTodos = summary?.pendingTodos ?? [];
  const recentRuns = summary?.recentRuns ?? [];
  const runtimeAvailable = summary?.runtimeAvailable ?? false;
  const runtimeStatusLabel = summary?.runtimeStatusLabel ?? "运行态建设中";

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      {messageContextHolder}
      <div className="flex min-h-screen">
        {/* ===== 侧边栏 ===== */}
        <aside className={`hidden shrink-0 sticky top-0 h-screen max-h-screen overflow-hidden bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color] duration-300 lg:flex lg:flex-col ${isSidebarCollapsed ? "w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}>
          {/* Logo 区 */}
          <div className={`flex h-[var(--header-height)] items-center gap-3 px-5 ${isSidebarCompact ? "justify-center px-0" : ""}`}>
            <div className="flex h-9 w-9 items-center justify-center overflow-hidden rounded-lg shadow-sm">
              <AgentumMark className="h-9 w-9" />
            </div>
            <div className={`workbench-sidebar-text ${showSidebarText ? "workbench-sidebar-text--visible" : ""}`}>
              <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
            </div>
          </div>

          {/* 导航菜单 —— 由后端 menus 驱动，不再硬编码 visibleFor */}
          <nav className="flex-1 overflow-y-auto min-h-0 space-y-1 px-3 py-3" aria-label="主导航">
            <p className={`px-3 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--color-sidebar-section-title)] ${showSidebarText ? "" : "sr-only"}`}>
              主工作区
            </p>
            {menus.map((menuItem) => {
              const Icon = ICON_MAP[menuItem.icon] ?? LayoutDashboard;
              const isActive = activeSurface === menuItem.key;

              return (
                <button
                  key={menuItem.key}
                  type="button"
                  onClick={() => setActiveSurface(menuItem.key as SurfaceKey)}
                  className={`relative flex w-full items-center rounded-lg text-left transition-all duration-200 ${isSidebarCompact ? "h-11 justify-center px-0" : "gap-3 px-3 py-2.5"} ${
                    isActive
                      ? "bg-[var(--color-bg-sidebar-active)] font-medium text-[var(--color-text-sidebar-active)]"
                      : "text-[var(--color-text-sidebar)] hover:bg-[var(--color-bg-sidebar-hover)] hover:text-[var(--color-text-primary)]"
                  }`}
                  title={menuItem.description}
                >
                  <Icon className={`h-5 w-5 shrink-0 ${isActive ? "text-[var(--color-primary)]" : ""}`} aria-hidden="true" />
                  <span className={`workbench-sidebar-text min-w-0 ${showSidebarText ? "workbench-sidebar-text--visible" : ""}`} aria-hidden={!showSidebarText}>
                    <span className="block text-sm font-medium">{menuItem.label}</span>
                    <span className="block text-xs text-[var(--color-text-tertiary)]">{menuItem.description}</span>
                  </span>
                  {isActive ? <span className="absolute right-0 top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-l bg-[var(--color-primary)]" /> : null}
                </button>
              );
            })}
          </nav>

          {/* 底部用户区域 */}
          <div className={`border-t border-[var(--color-border-light)] p-3 ${isSidebarCompact ? "flex justify-center" : ""}`}>
            {isSidebarCompact ? (
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
          {/* 顶部操作栏 —— 右侧替换为角色切换器 + 主题切换 */}
          <header className="bg-[var(--color-bg-page)]">
            <div className="mx-auto flex min-h-[var(--header-height)] max-w-[1400px] items-center justify-between gap-3 px-5 lg:px-6">
              <div className="flex min-w-0 items-center gap-3">
                <button
                  type="button"
                  onClick={handleToggleSidebar}
                  className="agent-button hidden h-8 w-8 shrink-0 px-0 lg:inline-flex"
                  aria-label={isSidebarCollapsed ? "展开左侧导航" : "收起左侧导航"}
                >
                  {isSidebarCollapsed ? <PanelLeftOpen className="h-4 w-4" aria-hidden="true" /> : <PanelLeftClose className="h-4 w-4" aria-hidden="true" />}
                </button>
              </div>
              <div className="flex items-center gap-2">
                {/* 主题切换药丸（与 AuraOA 一致） */}
                <ThemeToggle />
                {/* 角色切换器（参照 AuraOA，替换原来的硬编码操作按钮） */}
                <RoleSwitcher />
              </div>
            </div>
          </header>

          {activeSurface === null ? (
            <div className="mx-auto max-w-[1400px] px-5 py-4 lg:px-6">
              <section className="agent-card flex min-h-[360px] items-center justify-center p-8 text-center" aria-label="无可访问页签">
                <div>
                  <ShieldCheck className="mx-auto h-10 w-10 text-[var(--color-text-tertiary)]" aria-hidden="true" />
                  <h2 className="mt-4 text-base font-semibold text-[var(--color-text-primary)]">暂无可访问页签</h2>
                  <p className="agent-muted mt-2 text-sm">当前账号尚未获得租户内页签分配，请联系租户管理员配置业务入口。</p>
                </div>
              </section>
            </div>
          ) : null}

          {/* 业务工作台内容 */}
          {activeSurface === "workbench" ? (
            <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
              <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
                <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
                  <div className="flex min-w-0 gap-4">
                    <div className="workbench-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
                      <LayoutDashboard className="h-6 w-6" aria-hidden="true" />
                    </div>
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">业务工作台</h1>
                        <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                          任务运行
                        </span>
                      </div>
                      <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                        面向业务用户的任务入口：从总览进入任务创建、待办处理和任务续办；全部开放智能体流程可查看，有创建范围的流程才可发起任务。
                      </p>
                    </div>
                  </div>
                </header>

                <div className="system-mgmt-module-switch mb-5">
                  <div className="system-mgmt-segmented-scroll">
                    <Segmented<WorkbenchTab>
                      aria-label="业务工作台模块"
                      value={activeWorkbenchTab}
                      options={workbenchSegmentedOptions}
                      onChange={setActiveWorkbenchTab}
                      className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
                    />
                  </div>
                  <div className="login-portal-description login-portal-description--business">
                    <span className="login-portal-description-dot" />
                    {activeWorkbenchTabMeta.description}
                  </div>
                </div>

                {!tenantId ? (
                  <section className="sys-preview-card" aria-label="业务工作台不可用">
                    <div className="flex flex-col items-center gap-3 py-10 text-center">
                      <ShieldAlert className="h-10 w-10 text-[var(--color-text-tertiary)]" aria-hidden="true" />
                      <p className="text-sm font-semibold text-[var(--color-text-primary)]">业务工作台需要租户上下文</p>
                      <p className="agent-muted text-xs">系统管理员入口不绑定租户，请切换到业务用户或租户管理员角色后访问。</p>
                    </div>
                  </section>
                ) : (
                  <>
                    {summaryError ? (
                      <section className="sys-preview-card mb-4 border-rose-200 dark:border-rose-900/40" aria-label="业务工作台加载失败">
                        <div className="flex items-start gap-3 text-sm text-[var(--color-text-primary)]">
                          <ShieldAlert className="h-5 w-5 text-rose-500" aria-hidden="true" />
                          <div>
                            <p className="font-semibold">业务工作台数据暂时无法加载</p>
                            <p className="agent-muted mt-1 text-xs">{summaryError}</p>
                          </div>
                          <button type="button" className="agent-button ml-auto h-8 px-3 text-xs" onClick={() => void loadSummary()}>
                            重试
                          </button>
                        </div>
                      </section>
                    ) : null}

                    {activeWorkbenchTab === "overview" ? (
                      <>
                        <section className="sys-overview-stats" aria-label="业务工作台概览">
                          {metricCards.map((metric) => (
                            <WorkbenchOverviewStat key={metric.label} icon={metric.icon} label={metric.label} value={metric.value} hint={metric.hint} tone={metric.tone} loading={summaryLoading} />
                          ))}
                        </section>

                        <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]" aria-label="业务工作台总览">
                          <section className="sys-preview-card">
                            <div className="sys-preview-card-title"><LayoutDashboard size={16} /> 工作台功能入口</div>
                            <div className="grid gap-3 md:grid-cols-2">
                              <WorkbenchFeatureCard
                                icon={PlayCircle}
                                title="创建任务"
                                description="浏览全部已发布智能体流程，按版本和创建权限发起业务任务。"
                                meta={summary ? `${summary.metrics.availableWorkflowTotal} 个可发起流程` : "加载中..."}
                                onClick={() => setActiveWorkbenchTab("create")}
                              />
                              <WorkbenchFeatureCard
                                icon={ListTodo}
                                title="我的待办"
                                description="处理需要我补充资料、确认结果、人工审核或交付确认的暂停点。"
                                meta={runtimeAvailable ? `${pendingTodos.length} 个待办` : runtimeStatusLabel}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                              <WorkbenchFeatureCard
                                icon={PauseCircle}
                                title="暂停续办"
                                description="从正在进行和已暂停任务中恢复上下文，继续推进下一步。"
                                meta={runtimeAvailable ? `${recentRuns.filter((record) => record.state !== "已完成").length} 个可继续任务` : runtimeStatusLabel}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                              <WorkbenchFeatureCard
                                icon={Archive}
                                title="历史完成"
                                description="查看已完成任务与交付结果，后续可进入运行详情追溯过程。"
                                meta={runtimeAvailable ? `${recentRuns.filter((record) => record.state === "已完成").length} 个完成任务` : runtimeStatusLabel}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                            </div>
                          </section>

                          <aside className="sys-preview-card">
                            <div className="sys-preview-card-title"><History size={16} /> 最近任务</div>
                            {runtimeAvailable ? (
                              recentRuns.length === 0 ? (
                                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无最近任务" />
                              ) : (
                                <div className="space-y-2">
                                  {recentRuns.slice(0, 4).map((record) => (
                                    <RecentRunListItem key={record.id} record={record} />
                                  ))}
                                </div>
                              )
                            ) : (
                              <RuntimePlaceholder label={runtimeStatusLabel} hint="运行实例、节点运行和暂停事件将随运行态 API 一并上线。" />
                            )}
                          </aside>
                        </section>
                      </>
                    ) : null}

                    {activeWorkbenchTab === "create" ? (
                      <section className="sys-fade-in" aria-label="创建任务">
                        <div className="workflow-library-toolbar">
                          <div className="workflow-library-toolbar-actions">
                            <label className="workflow-definition-search">
                              <Search className="h-[18px] w-[18px]" aria-hidden="true" />
                              <span className="sr-only">搜索流程</span>
                              <input
                                value={availableKeywordInput}
                                onChange={(event) => setAvailableKeywordInput(event.target.value)}
                                onKeyDown={(event) => {
                                  if (event.key === "Enter") handleSubmitKeyword();
                                }}
                                onBlur={handleSubmitKeyword}
                                placeholder="按流程名称或描述搜索"
                              />
                            </label>
                          </div>
                        </div>

                        {availableError ? (
                          <div className="workflow-definition-empty-state">
                            <AlertCircle className="h-8 w-8" aria-hidden="true" />
                            <p>{availableError}</p>
                            <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => void loadAvailableWorkflows(availablePage, availableKeyword)}>
                              重试
                            </button>
                          </div>
                        ) : availableLoading ? (
                          <div className="workflow-definition-empty-state">
                            <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                            <p>正在加载已发布流程</p>
                          </div>
                        ) : availableWorkflows.length === 0 ? (
                          <div className="workflow-definition-empty-state">
                            <AlertCircle className="h-8 w-8" aria-hidden="true" />
                            <p>{availableKeyword ? `未找到包含「${availableKeyword}」的流程` : "当前租户暂无已发布流程"}</p>
                            <span>{availableKeyword ? "可以调整搜索词后重试。" : "可前往流程设计进行发布。"}</span>
                          </div>
                        ) : (
                          <div className="sys-card-grid">
                            {availableWorkflows.map((workflow) => (
                              <WorkflowLaunchCard key={workflow.id} workflow={workflow} onCreate={() => handleCreateTask(workflow)} />
                            ))}
                          </div>
                        )}

                        {availableTotal > AVAILABLE_PAGE_SIZE ? (
                          <div className="agent-admin-pagination-wrap mt-4">
                            <Pagination
                              className="agent-admin-pagination"
                              current={availablePage}
                              total={availableTotal}
                              pageSize={AVAILABLE_PAGE_SIZE}
                              showSizeChanger={false}
                              onChange={(page) => setAvailablePage(page)}
                            />
                          </div>
                        ) : null}
                      </section>
                    ) : null}

                    {activeWorkbenchTab === "tasks" ? (
                      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]" aria-label="任务中心">
                        <section className="sys-preview-card">
                          <div className="sys-preview-card-title"><UserRoundCheck size={16} /> 我的待办</div>
                          {runtimeAvailable ? (
                            pendingTodos.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无需要我处理的待办" />
                            ) : (
                              <div className="space-y-2">
                                {pendingTodos.map((todo) => (
                                  <PendingTodoListItem key={todo.id} todo={todo} />
                                ))}
                              </div>
                            )
                          ) : (
                            <RuntimePlaceholder label={runtimeStatusLabel} hint="人工审核、用户输入、交付确认等待办来源都依赖运行态接入。" />
                          )}
                        </section>

                        <section className="sys-preview-card">
                          <div className="sys-preview-card-title"><History size={16} /> 任务记录</div>
                          {runtimeAvailable ? (
                            recentRuns.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无任务记录" />
                            ) : (
                              <div className="space-y-2">
                                {recentRuns.map((record) => (
                                  <RecentRunListItem key={record.id} record={record} actionLabel={record.state === "已完成" ? "查看" : "继续"} />
                                ))}
                              </div>
                            )
                          ) : (
                            <RuntimePlaceholder label={runtimeStatusLabel} hint="任务运行实例的取消、重试、补偿会在运行监控模块单独承接。" />
                          )}
                        </section>
                      </section>
                    ) : null}
                  </>
                )}
              </div>
            </div>
          ) : null}

          {activeSurface === "designer" ? <WorkflowDraftsPage /> : null}

          {activeSurface === "assets" ? <AssetsPage /> : null}

          {activeSurface === "tenant" ? <TenantManagementPage /> : null}

          {activeSurface === "system" ? <SystemManagementPage /> : null}
        </section>
      </div>
    </main>
  );
}

function WorkbenchOverviewStat({
  icon: Icon,
  label,
  value,
  hint,
  tone,
  loading,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
  hint?: string;
  tone: MetricTone;
  loading: boolean;
}) {
  return (
    <div className="sys-overview-stat">
      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${tone}`}>
        <Icon size={20} aria-hidden="true" />
      </div>
      <div>
        <div className="sys-overview-stat-value">
          {loading ? <Loader2 className="inline h-5 w-5 animate-spin" aria-hidden="true" /> : value}
        </div>
        <div className="sys-overview-stat-label">{label}</div>
        {hint ? <div className="mt-0.5 text-[11px] text-[var(--color-text-tertiary)]">{hint}</div> : null}
      </div>
    </div>
  );
}

function WorkbenchFeatureCard({
  icon: Icon,
  title,
  description,
  meta,
  onClick,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  meta: string;
  onClick: () => void;
}) {
  return (
    <button type="button" onClick={onClick} className="workflow-feature-card">
      <span className="workflow-feature-card-head">
        <span className="workflow-feature-card-icon">
          <Icon size={16} aria-hidden="true" />
        </span>
        <span className="workflow-feature-card-title">{title}</span>
      </span>
      <span className="workflow-feature-card-description">{description}</span>
      <span className="workflow-feature-card-meta">
        {meta}
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function WorkflowLaunchCard({ workflow, onCreate }: { workflow: WorkbenchAvailableWorkflowRow; onCreate: () => void }) {
  const publishedAt = workflow.publishedAt ? new Date(workflow.publishedAt) : null;
  const publishedLabel = publishedAt ? publishedAt.toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <button type="button" onClick={onCreate} className="workflow-feature-card">
      <span className="workflow-feature-card-head">
        <span className="workflow-feature-card-icon">
          <PlayCircle size={16} aria-hidden="true" />
        </span>
        <span className="min-w-0">
          <span className="workflow-feature-card-title block truncate">{workflow.name}</span>
          <span className="mt-1 block text-[11px] text-[var(--color-text-tertiary)]">v{workflow.latestVersionNumber} · {workflow.nodeCount} 个节点</span>
        </span>
      </span>
      <span className="workflow-feature-card-description">
        {workflow.description?.trim() ? workflow.description : "尚未填写流程说明，发起前请联系流程负责人或在流程设计中补充。"}
      </span>
      <span className="mt-3 flex flex-wrap gap-1.5 text-[11px] text-[var(--color-text-tertiary)]">
        <span className="rounded border border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-2 py-1">发布人：{workflow.ownerName}</span>
        <span className="rounded border border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-2 py-1">发布于 {publishedLabel}</span>
      </span>
      <span className="workflow-feature-card-meta">
        发起任务
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function RuntimePlaceholder({ label, hint }: { label: string; hint: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-md border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-page)] px-4 py-8 text-center">
      <PauseCircle className="h-7 w-7 text-[var(--color-text-tertiary)]" aria-hidden="true" />
      <p className="text-sm font-medium text-[var(--color-text-primary)]">{label}</p>
      <p className="agent-muted max-w-sm text-xs leading-relaxed">{hint}</p>
    </div>
  );
}

function PendingTodoListItem({ todo }: { todo: WorkbenchPendingTodoRow }) {
  const dueLabel = todo.dueAt ? new Date(todo.dueAt).toLocaleString("zh-CN", { hour12: false }) : "无截止";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <UserRoundCheck size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{todo.title}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{todo.workflowName} · {todo.waitingFor}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{todo.action} · {dueLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[todo.waitingReason] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {todo.waitingReason}
        </span>
        <button type="button" className="agent-button h-7 px-2 text-xs">
          处理
        </button>
      </div>
    </div>
  );
}

function RecentRunListItem({ record, actionLabel }: { record: WorkbenchRecentRunRow; actionLabel?: string }) {
  const Icon = record.state === "已完成" ? CheckCircle2 : record.state === "已暂停" ? PauseCircle : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.workflowName}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.currentNode} · {record.ownerName}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.state] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.state}
        </span>
        {actionLabel ? (
          <button type="button" className="agent-button h-7 px-2 text-xs">
            {actionLabel}
          </button>
        ) : null}
      </div>
    </div>
  );
}
