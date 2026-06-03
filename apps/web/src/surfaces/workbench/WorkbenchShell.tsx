import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  Activity,
  AlertCircle,
  ArrowRight,
  ChevronLeft,
  Archive,
  Bot,
  CheckCircle2,
  CircleStop,
  Clock3,
  FileText,
  GitBranch,
  History,
  LayoutDashboard,
  Library,
  ListTodo,
  Loader2,
  LogOut,
  PanelLeft,
  PauseCircle,
  PlayCircle,
  Plug,
  RefreshCcw,
  Search,
  Send,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Undo2,
  User,
  UserRoundCheck,
  Wrench,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Drawer, Empty, Pagination, Segmented, message } from "antd";
import { TenantManagementPage } from "../admin/TenantManagementPage";
import { SystemManagementPage } from "../admin/SystemManagementPage";
import { AssetsPage } from "../assets/AssetsPage";
import { WorkflowDraftsPage } from "../designer/WorkflowDraftsPage";
import { SurfacePageLayout, WorkbenchGlobalActions } from "../../components/workbench/SurfacePageLayout";
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
type RunWorkspaceTab = "overview" | "current" | "trace" | "deliveries";

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

type RuntimeStepState = "done" | "running" | "waiting" | "pending";
type RuntimeNodeKind = "launch" | "input" | "agent" | "multiAgent" | "approval" | "delivery";

type RuntimeNodeField = {
  label: string;
  value: string;
  sensitive?: boolean;
};

type RuntimeChatMessage = {
  id: string;
  role: "user" | "assistant" | "system";
  author: string;
  content: string;
  streaming?: boolean;
};

type RuntimeCapabilityItem = {
  id: string;
  name: string;
  kind: "mcp" | "skill" | "agent";
  status: "idle" | "running" | "waiting" | "done" | "error";
  statusLabel: string;
  summary: string;
  highRisk?: boolean;
};

type RuntimePreviewStep = {
  title: string;
  subtitle: string;
  state: RuntimeStepState;
  kind: RuntimeNodeKind;
  description: string;
  inputs?: RuntimeNodeField[];
  outputs?: RuntimeNodeField[];
  completedAt?: string;
  chatMessages?: RuntimeChatMessage[];
  capabilities?: RuntimeCapabilityItem[];
  allowsFollowUp?: boolean;
  allowsRegenerate?: boolean;
  allowsInterrupt?: boolean;
};

type RuntimePreviewAgent = {
  name: string;
  capability: string;
  status: string;
  statusTone: "running" | "waiting" | "done";
  output: string;
  duration: string;
};

type RuntimePreviewEvent = {
  time: string;
  title: string;
  description: string;
  tone: "info" | "success" | "warning";
  stepTitle: string;
};

type RuntimePreview = {
  runId: string;
  statusLabel: string;
  activeNode: string;
  progress: number;
  startedAt: string;
  ownerName: string;
  workflowVersion: number;
  steps: RuntimePreviewStep[];
  agents: RuntimePreviewAgent[];
  events: RuntimePreviewEvent[];
  deliveries: Array<{ name: string; status: string; meta: string }>;
};

// 业务工作台运行态状态：第一阶段后端 runtimeAvailable 固定为 false，
// 前端按此标记展示“运行态建设中”空态，等待运行实例 API 上线。

const workbenchTabs: WorkbenchTabMeta[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, description: "查看今日待办、可创建流程和运行态概况" },
  { key: "create", label: "创建任务", icon: PlayCircle, description: "浏览全部开放智能体流程，有权限的流程可创建任务" },
  { key: "tasks", label: "任务中心", icon: ListTodo, description: "合并查看待办、运行中、暂停和历史完成任务" },
];

const runWorkspaceTabs: Array<{ key: RunWorkspaceTab; label: string; icon: LucideIcon }> = [
  { key: "overview", label: "概览", icon: LayoutDashboard },
  { key: "current", label: "当前处理", icon: Sparkles },
  { key: "trace", label: "执行链路", icon: History },
  { key: "deliveries", label: "交付物", icon: Send },
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
  const activeRole = useAuthStore((s) => s.activeRole);
  const roles = useAuthStore((s) => s.roles);
  const themeMode = useAuthStore((s) => s.themeMode);
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const logout = useAuthStore((s) => s.logout);
  const tenantId = user?.tenantId ?? null;
  const isDarkMode = themeMode === "dark";
  const currentBusinessRoleHasNoEntry = activeRole?.role === "business" && menus.length === 0;
  const hasTenantAdminRoleForCurrentTenant = roles.some((role) => role.role === "tenant_admin" && role.tenantId === activeRole?.tenantId);
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
  const [createdTaskWorkflow, setCreatedTaskWorkflow] = useState<WorkbenchAvailableWorkflowRow | null>(null);
  const [openedTaskWorkflow, setOpenedTaskWorkflow] = useState<WorkbenchAvailableWorkflowRow | null>(null);
  const [workflowDrawer, setWorkflowDrawer] = useState<WorkbenchAvailableWorkflowRow | null>(null);
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

  function handleLaunchTask(workflow: WorkbenchAvailableWorkflowRow) {
    // 第一阶段尚未接入运行实例创建 API，这里先直接进入任务处理页。
    // 后续替换为 POST 创建 WorkflowRun 成功后，按 runId 打开真实任务详情；只有保存/暂停后才刷新待办。
    setOpenedTaskWorkflow(null);
    window.setTimeout(() => setOpenedTaskWorkflow(workflow), 0);
    setWorkflowDrawer(null);
    messageApi.info(`已进入「${workflow.name}」任务处理页，保存后会出现在待办中`);
  }

  function handleSaveTaskToTodo() {
    if (!openedTaskWorkflow) {
      return;
    }
    setCreatedTaskWorkflow(openedTaskWorkflow);
    messageApi.info(`「${openedTaskWorkflow.name}」已保存到待办预览，真实保存将写入 WaitingEvent / WorkflowRun`);
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
        hint: metrics ? `我创建能力 ${metrics.myAssetTotal} 项` : "加载中",
        tone: "cap",
        icon: Library,
      },
    ];
  }, [summary]);

  const pendingTodos = summary?.pendingTodos ?? [];
  const recentRuns = summary?.recentRuns ?? [];
  const runtimeAvailable = summary?.runtimeAvailable ?? false;
  const runtimeStatusLabel = summary?.runtimeStatusLabel ?? "运行态建设中";
  const createdTaskPreview = useMemo(
    () => createdTaskWorkflow ? buildRuntimePreview(createdTaskWorkflow, user?.displayName ?? "当前用户") : null,
    [createdTaskWorkflow, user?.displayName],
  );
  const openedTaskPreview = useMemo(
    () => openedTaskWorkflow ? buildRuntimePreview(openedTaskWorkflow, user?.displayName ?? "当前用户") : null,
    [openedTaskWorkflow, user?.displayName],
  );

  return (
    <main className={`min-h-screen bg-[var(--color-bg-page)] text-[var(--color-text-primary)] transition-colors duration-300 ${isDarkMode ? "dark" : ""}`}>
      {messageContextHolder}
      <div className="flex min-h-screen">
        {/* ===== 侧边栏 ===== */}
        <aside
          className={`workbench-sidebar hidden shrink-0 sticky top-0 z-20 h-screen max-h-screen border-r border-[var(--color-sidebar-border)] bg-[var(--color-bg-sidebar)] text-[var(--color-text-sidebar)] transition-[width,background-color] duration-300 lg:flex lg:flex-col ${isSidebarCollapsed ? "workbench-sidebar--collapsed w-[var(--sidebar-collapsed-width)]" : "w-[var(--sidebar-width)]"}`}
        >
          {/* 侧栏头：展开时右侧收起并悬停提示；收起时 Logo 位悬停/聚焦替换为侧栏图标，右侧浮出「打开边栏」 */}
          <div
            className={`workbench-sidebar-header shrink-0 ${isSidebarCollapsed ? "workbench-sidebar-header--compact" : "workbench-sidebar-header--expanded"}`}
          >
            {isSidebarCollapsed ? (
              <>
                <button
                  type="button"
                  onClick={handleToggleSidebar}
                  className="workbench-sidebar-compact-brand"
                  aria-label="打开边栏"
                >
                  <span className="workbench-sidebar-mark-slot overflow-hidden rounded-lg shadow-sm">
                    <AgentumMark className="workbench-sidebar-mark-logo h-9 w-9 shrink-0 object-contain" />
                    <span className="workbench-sidebar-mark-toggle" aria-hidden="true">
                      <PanelLeft className="h-4 w-4" />
                    </span>
                  </span>
                </button>
                <span className="workbench-sidebar-expand-hint" aria-hidden="true">
                  打开边栏
                </span>
              </>
            ) : (
              <>
                <div className="workbench-sidebar-brand">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-lg shadow-sm">
                    <AgentumMark className="h-9 w-9 shrink-0 object-contain" />
                  </div>
                  {showSidebarText ? (
                    <div className="workbench-sidebar-text workbench-sidebar-text--visible">
                      <p className="text-lg font-bold text-[var(--color-sidebar-logo-text)]">Agentum</p>
                    </div>
                  ) : null}
                </div>
                <button
                  type="button"
                  onClick={handleToggleSidebar}
                  className="workbench-sidebar-toggle workbench-sidebar-hint-below"
                  aria-label="关闭边栏"
                  data-hint="关闭边栏"
                >
                  <PanelLeft className="h-4 w-4" aria-hidden="true" />
                </button>
              </>
            )}
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
        <section className={`min-w-0 flex-1${activeSurface === "workbench" && tenantId && openedTaskWorkflow ? " overflow-hidden" : ""}`}>
          {activeSurface === null ? (
            <div className="min-h-screen bg-[var(--color-bg-page)] pb-10">
              <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
                <header className="surface-page-chrome surface-page-chrome--actions-only flex justify-end pt-3">
                  <WorkbenchGlobalActions />
                </header>
              <section className="agent-card flex min-h-[360px] items-center justify-center p-8 text-center" aria-label="无可访问页签">
                <div>
                  <ShieldCheck className="mx-auto h-10 w-10 text-[var(--color-text-tertiary)]" aria-hidden="true" />
                  <h2 className="mt-4 text-base font-semibold text-[var(--color-text-primary)]">{currentBusinessRoleHasNoEntry ? "业务入口尚未配置" : "暂无可访问页签"}</h2>
                  <p className="agent-muted mt-2 text-sm">
                    {currentBusinessRoleHasNoEntry && hasTenantAdminRoleForCurrentTenant
                      ? "当前业务用户身份尚未获得页签分配，请切回租户管理，在资源分配中为人员、部门或角色配置业务入口。"
                      : "当前账号尚未获得租户内页签分配，请联系租户管理员配置业务入口。"}
                  </p>
                </div>
              </section>
              </div>
            </div>
          ) : null}

          {/* 业务工作台内容 */}
          {activeSurface === "workbench" ? (
            tenantId && openedTaskWorkflow && openedTaskPreview ? (
              <div className="workbench-task-run-host">
                <div className="workbench-immersive-topbar">
                  <WorkbenchGlobalActions />
                </div>
                <div className="workbench-task-run-host-inner">
                  <WorkbenchTaskRunDetail
                    workflow={openedTaskWorkflow}
                    preview={openedTaskPreview}
                    runtimeStatusLabel={runtimeStatusLabel}
                    onBack={() => {
                      setOpenedTaskWorkflow(null);
                      setActiveWorkbenchTab("tasks");
                    }}
                    onSaveToTodo={handleSaveTaskToTodo}
                    onAction={(label) => messageApi.info(`${label} 将在运行态 API 上线后写入真实任务`)}
                  />
                </div>
              </div>
            ) : (
            <SurfacePageLayout
              markClassName="workbench-page-mark"
              icon={LayoutDashboard}
              title="业务工作台"
              badge="任务运行"
              description="面向业务用户的任务入口：从总览进入任务创建、待办处理和任务续办；全部开放智能体流程可查看，有创建范围的流程才可发起任务。"
            >
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
                                placeholder="按流程名称或描述搜索"
                              />
                            </label>
                            <button type="button" className="sys-btn sys-btn--default" onClick={() => handleSubmitKeyword()}>
                              <Search size={18} aria-hidden="true" />
                              查询
                            </button>
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
                              <WorkflowLaunchCard key={workflow.id} workflow={workflow} onOpen={() => setWorkflowDrawer(workflow)} />
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
                          {createdTaskWorkflow && createdTaskPreview ? (
                            <CreatedTaskTodoItem
                              workflow={createdTaskWorkflow}
                              preview={createdTaskPreview}
                              onOpen={() => setOpenedTaskWorkflow(createdTaskWorkflow)}
                            />
                          ) : null}
                          {runtimeAvailable ? (
                            pendingTodos.length === 0 ? (
                              createdTaskWorkflow ? null : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无需要我处理的待办" />
                            ) : (
                              <div className="space-y-2">
                                {pendingTodos.map((todo) => (
                                  <PendingTodoListItem key={todo.id} todo={todo} />
                                ))}
                              </div>
                            )
                          ) : !createdTaskWorkflow ? (
                            <RuntimePlaceholder label={runtimeStatusLabel} hint="人工审核、用户输入、交付确认等待办来源都依赖运行态接入。" />
                          ) : null}
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
                <WorkflowLaunchDrawer
                  workflow={workflowDrawer}
                  rootClassName={isDarkMode ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"}
                  onClose={() => setWorkflowDrawer(null)}
                  onLaunch={handleLaunchTask}
                />
            </SurfacePageLayout>
            )
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

function WorkflowLaunchCard({ workflow, onOpen }: { workflow: WorkbenchAvailableWorkflowRow; onOpen: () => void }) {
  const publishedAt = workflow.publishedAt ? new Date(workflow.publishedAt) : null;
  const publishedLabel = publishedAt ? publishedAt.toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <button type="button" onClick={onOpen} className="workflow-launch-card">
      <span className="workflow-feature-card-head">
        <span className="workflow-launch-card-icon">
          <PlayCircle size={18} aria-hidden="true" />
        </span>
        <span className="min-w-0">
          <span className="workflow-launch-card-title block truncate">{workflow.name}</span>
          <span className="workflow-launch-card-version">v{workflow.latestVersionNumber} · {workflow.nodeCount} 个节点</span>
        </span>
      </span>
      <span className="workflow-launch-card-description">
        {workflow.description?.trim() ? workflow.description : "尚未填写流程说明，发起前请联系流程负责人或在流程设计中补充。"}
      </span>
      <span className="workflow-launch-card-tags">
        <span className="workflow-launch-card-tag workflow-launch-card-tag--owner">发布人：{workflow.ownerName}</span>
        <span className="workflow-launch-card-tag workflow-launch-card-tag--time">发布于 {publishedLabel}</span>
      </span>
      <span className="workflow-launch-card-meta">
        查看详情
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function WorkflowLaunchDrawer({
  workflow,
  rootClassName,
  onClose,
  onLaunch,
}: {
  workflow: WorkbenchAvailableWorkflowRow | null;
  rootClassName: string;
  onClose: () => void;
  onLaunch: (workflow: WorkbenchAvailableWorkflowRow) => void;
}) {
  if (!workflow) {
    return null;
  }

  const publishedAt = workflow.publishedAt ? new Date(workflow.publishedAt) : null;
  const publishedLabel = publishedAt ? publishedAt.toLocaleString("zh-CN", { hour12: false }) : "—";
  const preview = buildRuntimePreview(workflow, workflow.ownerName);

  return (
    <Drawer
      title="可发起流程详情"
      width={560}
      open
      onClose={onClose}
      rootClassName={rootClassName}
    >
      <div className="workbench-launch-drawer">
        <section className="workbench-launch-drawer-hero">
          <span className="workflow-launch-card-icon">
            <PlayCircle size={18} aria-hidden="true" />
          </span>
          <div>
            <h2>{workflow.name}</h2>
            <p>v{workflow.latestVersionNumber} · {workflow.nodeCount} 个节点 · 发布于 {publishedLabel}</p>
          </div>
        </section>

        <section className="workbench-launch-drawer-section">
          <h3>流程说明</h3>
          <p>{workflow.description?.trim() ? workflow.description : "尚未填写流程说明，发起前请联系流程负责人或在流程设计中补充。"}</p>
        </section>

        <section className="workbench-launch-drawer-section">
          <h3>发起后将进入的处理链路</h3>
          <div className="workbench-launch-drawer-steps">
            {preview.steps.slice(0, 5).map((step, index) => (
              <div key={step.title}>
                <span>{index + 1}</span>
                <div>
                  <strong>{step.title}</strong>
                  <small>{step.subtitle}</small>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="workbench-launch-drawer-section">
          <h3>运行能力</h3>
          <div className="workbench-capability-stack">
            <span>输入节点</span>
            <span>智能体集群</span>
            <span>MCP 审批</span>
            <span>节点输入输出</span>
            <span>交付物生成</span>
          </div>
        </section>

        <div className="workbench-launch-drawer-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>
            取消
          </button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onLaunch(workflow)}>
            <PlayCircle size={16} aria-hidden="true" />
            发起任务
          </button>
        </div>
      </div>
    </Drawer>
  );
}

function buildRuntimePreview(workflow: WorkbenchAvailableWorkflowRow, ownerName: string): RuntimePreview {
  return {
    runId: "RUN-20260601-018",
    statusLabel: "运行中",
    activeNode: "外部数据核验",
    progress: 58,
    startedAt: "2026-06-01 09:42",
    ownerName,
    workflowVersion: workflow.latestVersionNumber,
    steps: [
      {
        title: "创建任务",
        subtitle: "流程版本已冻结",
        state: "done",
        kind: "launch",
        description: "发起人确认流程版本、任务名称和处理范围，系统生成不可变运行快照。",
        outputs: [
          { label: "运行编号", value: "RUN-20260601-018" },
          { label: "流程版本", value: `v${workflow.latestVersionNumber}` },
        ],
        completedAt: "09:42",
      },
      {
        title: "补充资料",
        subtitle: "授信主体与附件已提交",
        state: "done",
        kind: "input",
        description: "业务人员补充授信主体、报告用途和附件材料，后续节点只能读取快照数据。",
        inputs: [
          { label: "授信主体", value: "云程科技有限公司" },
          { label: "报告用途", value: "年度授信复核" },
          { label: "补充材料", value: "征信授权书、近三年财务报表" },
        ],
        outputs: [
          { label: "主体信息快照", value: "已固化" },
          { label: "附件校验", value: "3 份材料可读" },
        ],
        completedAt: "09:47",
      },
      {
        title: "智能体追问",
        subtitle: "缺失口径已确认",
        state: "done",
        kind: "agent",
        description: "智能体发现报告口径缺失后向处理人追问，并将确认结果写入后续上下文。",
        inputs: [{ label: "追问", value: "本次报告用于新增授信还是年度复核？" }],
        outputs: [
          { label: "确认口径", value: "年度授信复核" },
          { label: "补充担保资料", value: "无需追加" },
        ],
        completedAt: "09:51",
        allowsFollowUp: true,
        allowsRegenerate: true,
        chatMessages: [
          { id: "agent-q1", role: "assistant", author: "授信分析智能体", content: "检测到报告用途未明确，请确认本次报告用于新增授信还是年度复核？" },
          { id: "agent-q2", role: "user", author: "处理人", content: "用于年度授信复核，无需追加担保人资料。" },
          { id: "agent-q3", role: "assistant", author: "授信分析智能体", content: "已确认口径为「年度授信复核」，并将结论写入后续节点上下文。" },
        ],
        capabilities: [
          { id: "skill-followup", name: "口径追问 Skill", kind: "skill", status: "done", statusLabel: "已完成", summary: "识别缺失字段并生成追问" },
        ],
      },
      {
        title: "外部数据核验",
        subtitle: "多智能体并行执行中",
        state: "running",
        kind: "multiAgent",
        description: "当前节点由多个支撑智能体并行执行，处理人可以查看实时回复、审批 MCP 调用、追问或要求重新生成。",
        inputs: [
          { label: "主体信息", value: "云程科技有限公司" },
          { label: "报告口径", value: "年度授信复核" },
        ],
        allowsFollowUp: true,
        allowsRegenerate: true,
        allowsInterrupt: true,
        chatMessages: [
          { id: "ma-sys", role: "system", author: "系统", content: "外部数据核验节点已启动，4 个支撑智能体并行执行中。" },
          { id: "ma-a1", role: "assistant", author: "授信核验编排智能体", content: "已完成工商登记与财务指标的初步核验。主体名称、统一社会信用代码和经营状态一致，现金流覆盖率低于同业中位，需要结合司法风险结果判断是否触发人工复核。" },
          { id: "ma-a2", role: "assistant", author: "行业研究智能体", content: "正在补充行业景气度和政策影响。当前生成内容只作为模型文本，可由处理人追问、改写或要求重新生成。", streaming: true },
        ],
        capabilities: [
          { id: "cap-mcp-corp", name: "企查 MCP", kind: "mcp", status: "running", statusLabel: "执行中", summary: "主体登记、股东结构与经营状态" },
          { id: "cap-mcp-legal", name: "司法查询 MCP", kind: "mcp", status: "waiting", statusLabel: "待审批", summary: "涉诉与被执行数据源", highRisk: true },
          { id: "cap-skill-finance", name: "财务分析 Skill", kind: "skill", status: "done", statusLabel: "已完成", summary: "近三年营收、现金流与负债率摘要" },
          { id: "cap-agent-industry", name: "行业研究智能体", kind: "agent", status: "running", statusLabel: "生成中", summary: "同业区间、政策风险与景气度" },
        ],
      },
      {
        title: "报告组装",
        subtitle: "等待核验结果汇总",
        state: "pending",
        kind: "agent",
        description: "等待外部数据核验完成后，将事实结果和模型分析组装为报告草稿。",
        allowsFollowUp: true,
        allowsRegenerate: true,
        chatMessages: [
          { id: "asm-hint", role: "system", author: "系统", content: "等待上游节点输出全部就绪后，组装智能体将统一章节口径并生成报告草稿。" },
        ],
        capabilities: [
          { id: "cap-asm", name: "报告组装 Skill", kind: "skill", status: "idle", statusLabel: "等待中", summary: "章节合并与口径统一" },
        ],
      },
      {
        title: "人工审核",
        subtitle: "风控经理复核",
        state: "pending",
        kind: "approval",
        description: "风控经理复核报告草稿、事实来源和高风险调用审批记录。",
        chatMessages: [
          { id: "ap-preview", role: "assistant", author: "报告预览", content: "报告草稿已生成，包含主体概况、财务分析、司法风险与行业补充四个章节。其中司法风险章节依赖待审批 MCP 调用结果。" },
        ],
      },
      {
        title: "文档交付",
        subtitle: "生成 Word / PDF",
        state: "pending",
        kind: "delivery",
        description: "审核通过后生成交付物并进入归档或下游系统推送。",
      },
    ],
    agents: [
      { name: "工商信息核验", capability: "企查 MCP · 只读查询", status: "执行中", statusTone: "running", output: "已获取主体登记、股东和经营状态，正在比对统一社会信用代码。", duration: "01:48" },
      { name: "司法风险检索", capability: "司法查询 MCP · 高风险", status: "待审批", statusTone: "waiting", output: "即将调用涉诉与被执行数据源，需要当前处理人审批后继续。", duration: "等待" },
      { name: "财务指标解析", capability: "财务分析 Skill", status: "已完成", statusTone: "done", output: "完成近三年营收、现金流和负债率摘要，输出变量 finance_ratio_summary。", duration: "00:54" },
      { name: "行业补充分析", capability: "行业研究智能体", status: "执行中", statusTone: "running", output: "正在生成同业区间、政策风险和行业景气度补充段落。", duration: "02:13" },
    ],
    events: [
      { time: "09:42", title: "任务已创建", description: `${workflow.name} v${workflow.latestVersionNumber} 生成运行实例快照。`, tone: "success", stepTitle: "创建任务" },
      { time: "09:47", title: "用户补充资料", description: "补充授信主体名称、征信授权附件和报告用途。", tone: "success", stepTitle: "补充资料" },
      { time: "09:51", title: "智能体完成追问", description: "确认报告口径为“年度授信复核”，无需追加担保人资料。", tone: "info", stepTitle: "智能体追问" },
      { time: "09:56", title: "触发高风险调用审批", description: "司法风险检索需要审批后继续，审批记录将写入审计链路。", tone: "warning", stepTitle: "外部数据核验" },
    ],
    deliveries: [
      { name: "授信分析报告.docx", status: "待生成", meta: "报告组装节点完成后生成" },
      { name: "风险摘要.pdf", status: "待生成", meta: "人工审核通过后固化" },
      { name: "OA 归档流程", status: "未触发", meta: "交付确认后创建" },
    ],
  };
}

function WorkbenchTaskRunDetail({
  workflow,
  preview,
  runtimeStatusLabel,
  onBack,
  onSaveToTodo,
  onAction,
}: {
  workflow: WorkbenchAvailableWorkflowRow;
  preview: RuntimePreview;
  runtimeStatusLabel: string;
  onBack: () => void;
  onSaveToTodo: () => void;
  onAction: (label: string) => void;
}) {
  const [activeRunTab, setActiveRunTab] = useState<RunWorkspaceTab>("current");
  const currentStepIndex = Math.max(0, preview.steps.findIndex((step) => step.state === "running" || step.state === "waiting"));
  const activeStep = preview.steps[currentStepIndex] ?? preview.steps[0];
  const [selectedTraceStepIndex, setSelectedTraceStepIndex] = useState<number | null>(null);

  function handleTabChange(tab: RunWorkspaceTab) {
    setActiveRunTab(tab);
    if (tab === "trace") {
      setSelectedTraceStepIndex(null);
    }
  }

  function handleStepSelect(step: RuntimePreviewStep, index: number) {
    if (step.state === "done") {
      setSelectedTraceStepIndex(index);
      setActiveRunTab("trace");
      return;
    }

    if (step.state === "running" || step.state === "waiting") {
      setSelectedTraceStepIndex(index);
      setActiveRunTab("current");
    }
  }

  return (
    <section className="workbench-task-workspace sys-fade-in" aria-label="任务处理工作区">
      <header className="workbench-task-topbar">
        <div className="workbench-task-title">
          <div className="workbench-run-kicker">
            <span>业务工作台 / 任务处理</span>
            <span className="workbench-run-soft-badge">{runtimeStatusLabel}</span>
          </div>
          <div className="workbench-run-title-row">
            <h2>{workflow.name}</h2>
            <span className="workbench-run-status">
              <span className="workbench-run-status-dot" />
              {preview.statusLabel}
            </span>
          </div>
          <p>运行编号 {preview.runId} · v{preview.workflowVersion} · 当前节点：{activeStep.title}</p>
        </div>
        <div className="workbench-run-actions">
          <button type="button" className="sys-btn sys-btn--default" onClick={() => onAction("退回上一步")}>
            <Undo2 size={16} aria-hidden="true" />
            退回上一步
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={onSaveToTodo}>
            <Archive size={16} aria-hidden="true" />
            保存
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={() => onAction("暂停任务")}>
            <PauseCircle size={16} aria-hidden="true" />
            暂停
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={onBack}>
            <History size={16} aria-hidden="true" />
            返回任务中心
          </button>
        </div>
      </header>

      <div className="workbench-task-layout">
        <aside className="workbench-task-rail" aria-label="任务流程进度">
          <div className="workbench-task-rail-head">
            <strong>流程进度</strong>
            <span>{preview.progress}%</span>
          </div>
          <div className="workbench-run-progress workbench-run-progress--rail">
            <div><i style={{ width: `${preview.progress}%` }} /></div>
          </div>
          <div className="workbench-node-rail">
            {preview.steps.map((step, index) => (
              <button
                key={step.title}
                type="button"
                className={[
                  "workbench-node-step",
                  `workbench-node-step--${step.state}`,
                  selectedTraceStepIndex === index || (activeRunTab === "current" && activeStep.title === step.title) ? "workbench-node-step--selected" : "",
                  step.state === "pending" ? "workbench-node-step--disabled" : "",
                ].filter(Boolean).join(" ")}
                onClick={() => handleStepSelect(step, index)}
                disabled={step.state === "pending"}
              >
                <span className="workbench-node-step-index">{index + 1}</span>
                <span className="workbench-node-step-text">
                  <strong>{step.title}</strong>
                  <small>{step.subtitle}</small>
                </span>
              </button>
            ))}
          </div>
        </aside>

        <section className="workbench-task-main">
          <nav className="workbench-runtime-tabs" aria-label="任务处理页签">
            {runWorkspaceTabs.map((tab) => {
              const Icon = tab.icon;
              return (
                <button
                  key={tab.key}
                  type="button"
                  className={activeRunTab === tab.key ? "workbench-runtime-tab workbench-runtime-tab--active" : "workbench-runtime-tab"}
                  onClick={() => handleTabChange(tab.key)}
                >
                  <Icon size={16} aria-hidden="true" />
                  {tab.label}
                </button>
              );
            })}
          </nav>

          <div className="workbench-task-panel-scroll">
            {activeRunTab === "overview" ? <RunOverviewPanel workflow={workflow} preview={preview} /> : null}
            {activeRunTab === "current" ? (
              <RunCurrentPanel
                preview={preview}
                activeStep={activeStep}
                onSaveToTodo={onSaveToTodo}
                onAction={onAction}
              />
            ) : null}
            {activeRunTab === "trace" ? (
              <RunTracePanel
                preview={preview}
                selectedStepIndex={selectedTraceStepIndex}
                onSelectStep={setSelectedTraceStepIndex}
                onClearStep={() => setSelectedTraceStepIndex(null)}
              />
            ) : null}
            {activeRunTab === "deliveries" ? <RunDeliveriesPanel preview={preview} onAction={onAction} /> : null}
          </div>
        </section>
      </div>
    </section>
  );
}

function RunOverviewPanel({ workflow, preview }: { workflow: WorkbenchAvailableWorkflowRow; preview: RuntimePreview }) {
  return (
    <div className="workbench-panel-grid">
      <section className="sys-preview-card workbench-run-section">
        <div className="sys-preview-card-title"><LayoutDashboard size={16} /> 任务概览</div>
        <p className="workbench-panel-copy">{workflow.description?.trim() ? workflow.description : "当前流程未填写说明，任务处理页会按节点展示输入、智能体输出、外部调用和交付结果。"}</p>
        <div className="workbench-run-meta-grid workbench-run-meta-grid--compact">
          <RunMetaCard icon={FileText} label="运行编号" value={preview.runId} />
          <RunMetaCard icon={GitBranch} label="流程版本" value={`v${preview.workflowVersion}`} />
          <RunMetaCard icon={UserRoundCheck} label="发起人" value={preview.ownerName} />
          <RunMetaCard icon={Clock3} label="开始时间" value={preview.startedAt} />
        </div>
      </section>
    </div>
  );
}

function currentStepStatusLabel(step: RuntimePreviewStep): string {
  if (step.state === "waiting") {
    return "等待处理";
  }
  if (step.state === "running") {
    return step.kind === "input" ? "等待填写" : "执行中";
  }
  return "处理中";
}

function capabilityKindIcon(kind: RuntimeCapabilityItem["kind"]) {
  if (kind === "mcp") {
    return Plug;
  }
  if (kind === "skill") {
    return Wrench;
  }
  return Bot;
}

function AgentChatStream({ messages }: { messages: RuntimeChatMessage[] }) {
  return (
    <div className="workbench-ai-stream" aria-live="polite">
      {messages.map((message) => (
        <article
          key={message.id}
          className={[
            "workbench-ai-bubble",
            `workbench-ai-bubble--${message.role}`,
            message.streaming ? "workbench-ai-bubble--streaming" : "",
          ].filter(Boolean).join(" ")}
        >
          <header className="workbench-ai-bubble-head">
            <strong>{message.author}</strong>
            {message.streaming ? (
              <span className="workbench-ai-streaming-badge">
                <Loader2 size={12} className="workbench-ai-streaming-icon" aria-hidden="true" />
                流式输出中
              </span>
            ) : null}
          </header>
          <p>{message.content}{message.streaming ? <span className="workbench-ai-cursor" aria-hidden="true" /> : null}</p>
        </article>
      ))}
    </div>
  );
}

function AgentCapabilityDock({
  capabilities,
  onAction,
}: {
  capabilities: RuntimeCapabilityItem[];
  onAction: (label: string) => void;
}) {
  if (capabilities.length === 0) {
    return null;
  }

  return (
    <div className="workbench-capability-dock" aria-label="节点能力调用">
      <div className="workbench-capability-dock-head">
        <span className="workbench-subsection-title"><Library size={15} /> 能力调用</span>
        <small>MCP / Skill / 子智能体实时状态，点击可查看调用明细</small>
      </div>
      <div className="workbench-capability-dock-list">
        {capabilities.map((capability) => {
          const Icon = capabilityKindIcon(capability.kind);
          return (
            <button
              key={capability.id}
              type="button"
              className={[
                "workbench-capability-chip",
                `workbench-capability-chip--${capability.status}`,
                capability.highRisk ? "workbench-capability-chip--risk" : "",
              ].filter(Boolean).join(" ")}
              onClick={() => onAction(`查看${capability.name}调用明细`)}
            >
              <span className="workbench-capability-chip-icon">
                <Icon size={14} aria-hidden="true" />
              </span>
              <span className="workbench-capability-chip-main">
                <strong>{capability.name}</strong>
                <small>{capability.summary}</small>
              </span>
              <span className={`workbench-run-pill workbench-run-pill--${capability.status === "done" ? "done" : capability.status === "waiting" ? "waiting" : "running"}`}>
                {capability.statusLabel}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function AgentChatComposer({
  activeStep,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onAction: (label: string) => void;
}) {
  const [draft, setDraft] = useState("");

  if (!activeStep.allowsFollowUp && !activeStep.allowsRegenerate) {
    return null;
  }

  return (
    <div className="workbench-ai-composer">
      <div className="workbench-ai-composer-toolbar">
        {activeStep.allowsRegenerate ? (
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onAction("重新生成当前节点输出")}>
            <RefreshCcw size={14} aria-hidden="true" />
            重新生成
          </button>
        ) : null}
        {activeStep.allowsInterrupt ? (
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm workbench-ai-interrupt-btn" onClick={() => onAction("中断当前节点执行")}>
            <CircleStop size={14} aria-hidden="true" />
            中断执行
          </button>
        ) : null}
        <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onAction("查看事实来源")}>
          <FileText size={14} aria-hidden="true" />
          查看来源
        </button>
      </div>
      {activeStep.allowsFollowUp ? (
        <div className="workbench-ai-composer-input">
          <textarea
            rows={3}
            value={draft}
            placeholder="追问智能体，例如：补充现金流压力分析，并标注事实来源"
            onChange={(event) => setDraft(event.target.value)}
          />
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            disabled={draft.trim().length === 0}
            onClick={() => {
              onAction("发送追问");
              setDraft("");
            }}
          >
            <Send size={16} aria-hidden="true" />
            发送
          </button>
        </div>
      ) : null}
    </div>
  );
}

function CurrentNodeShell({
  activeStep,
  children,
  footer,
}: {
  activeStep: RuntimePreviewStep;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="workbench-current-shell">
      <header className="workbench-current-head">
        <div>
          <div className="sys-preview-card-title"><Sparkles size={16} /> 当前处理：{activeStep.title}</div>
          <p>{activeStep.description}</p>
        </div>
        <span className={`workbench-run-pill workbench-run-pill--${activeStep.state === "waiting" ? "waiting" : "running"}`}>
          {currentStepStatusLabel(activeStep)}
        </span>
      </header>
      <div className="workbench-current-content">
        {children}
        {footer ? <div className="workbench-current-foot">{footer}</div> : null}
      </div>
    </div>
  );
}

function RunCurrentInputPanel({
  activeStep,
  onSaveToTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onAction: (label: string) => void;
}) {
  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onAction("提交当前输入")}>
            <Send size={16} aria-hidden="true" />
            提交输入
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={onSaveToTodo}>
            <Archive size={16} aria-hidden="true" />
            保存草稿
          </button>
        </div>
      )}
    >
      <div className="workbench-input-form">
        {(activeStep.inputs ?? [
          { label: "授信主体", value: "" },
          { label: "报告用途", value: "" },
          { label: "补充材料", value: "" },
          { label: "处理说明", value: "" },
        ]).map((field) => (
          <label key={field.label} className="workbench-input-form-field">
            <span>{field.label}</span>
            <input defaultValue={field.value} placeholder={`请输入${field.label}`} />
          </label>
        ))}
      </div>
    </CurrentNodeShell>
  );
}

function RunCurrentAgentPanel({
  activeStep,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onAction: (label: string) => void;
}) {
  const messages = activeStep.chatMessages ?? [];
  const capabilities = activeStep.capabilities ?? [];

  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <>
          <AgentCapabilityDock capabilities={capabilities} onAction={onAction} />
          <AgentChatComposer activeStep={activeStep} onAction={onAction} />
        </>
      )}
    >
      <AgentChatStream messages={messages} />
    </CurrentNodeShell>
  );
}

function RunCurrentMultiAgentPanel({
  activeStep,
  onSaveToTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onAction: (label: string) => void;
}) {
  const messages = activeStep.chatMessages ?? [];
  const capabilities = activeStep.capabilities ?? [];
  const waitingCapability = capabilities.find((item) => item.status === "waiting" && item.highRisk);

  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <>
          <AgentCapabilityDock capabilities={capabilities} onAction={onAction} />
          <AgentChatComposer activeStep={activeStep} onAction={onAction} />
        </>
      )}
    >
      <AgentChatStream messages={messages} />
      {waitingCapability ? (
        <section className="workbench-approval-inline">
          <div className="workbench-approval-inline-head">
            <ShieldAlert size={16} aria-hidden="true" />
            <div>
              <strong>待审批：{waitingCapability.name}</strong>
              <p>{waitingCapability.summary}。这是高风险只读查询，继续前需要记录处理意见。</p>
            </div>
          </div>
          <textarea className="workbench-approval-textarea" placeholder="填写审批意见，后续将写入 WaitingEvent 恢复记录" />
          <div className="workbench-approval-inline-actions">
            <button type="button" className="sys-btn sys-btn--primary sys-btn--sm" onClick={() => onAction("通过并继续")}>
              <ShieldCheck size={14} aria-hidden="true" />
              通过并继续
            </button>
            <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onAction("要求补充来源")}>
              要求补充来源
            </button>
            <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={onSaveToTodo}>
              保存待办
            </button>
          </div>
        </section>
      ) : null}
      {activeStep.inputs && activeStep.inputs.length > 0 ? (
        <details className="workbench-current-context">
          <summary>查看节点输入上下文</summary>
          <NodeIoSummary step={activeStep} />
        </details>
      ) : null}
    </CurrentNodeShell>
  );
}

function RunCurrentApprovalPanel({
  activeStep,
  onSaveToTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onAction: (label: string) => void;
}) {
  const messages = activeStep.chatMessages ?? [];

  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onAction("审核通过")}>
            <ShieldCheck size={16} aria-hidden="true" />
            审核通过
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={() => onAction("驳回并退回")}>
            驳回修改
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={onSaveToTodo}>
            <Archive size={16} aria-hidden="true" />
            保存意见
          </button>
        </div>
      )}
    >
      <AgentChatStream messages={messages} />
      <label className="workbench-approval-form">
        <span>审核意见</span>
        <textarea className="workbench-approval-textarea" placeholder="填写审核结论、需修改章节或补充说明" />
      </label>
      <NodeIoSummary step={activeStep} />
    </CurrentNodeShell>
  );
}

function RunCurrentDeliveryPanel({
  activeStep,
  preview,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  preview: RuntimePreview;
  onAction: (label: string) => void;
}) {
  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onAction("确认交付")}>
            <Send size={16} aria-hidden="true" />
            确认交付
          </button>
          <button type="button" className="sys-btn sys-btn--default" onClick={() => onAction("预览交付物")}>
            <FileText size={16} aria-hidden="true" />
            预览文件
          </button>
        </div>
      )}
    >
      <div className="workbench-delivery-preview-list">
        {preview.deliveries.map((delivery) => (
          <div key={delivery.name} className="workbench-delivery-preview-row">
            <FileText size={16} aria-hidden="true" />
            <div>
              <strong>{delivery.name}</strong>
              <small>{delivery.status} · {delivery.meta}</small>
            </div>
            <span className="workbench-run-pill workbench-run-pill--waiting">{delivery.status}</span>
          </div>
        ))}
      </div>
    </CurrentNodeShell>
  );
}

function RunCurrentDefaultPanel({
  activeStep,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onAction: (label: string) => void;
}) {
  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onAction("继续执行")}>
            继续执行
          </button>
        </div>
      )}
    >
      <NodeIoSummary step={activeStep} />
    </CurrentNodeShell>
  );
}

function RunCurrentPanel({
  preview,
  activeStep,
  onSaveToTodo,
  onAction,
}: {
  preview: RuntimePreview;
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onAction: (label: string) => void;
}) {
  if (activeStep.kind === "input") {
    return <RunCurrentInputPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "multiAgent") {
    return <RunCurrentMultiAgentPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "agent") {
    return <RunCurrentAgentPanel activeStep={activeStep} onAction={onAction} />;
  }

  if (activeStep.kind === "approval") {
    return <RunCurrentApprovalPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "delivery") {
    return <RunCurrentDeliveryPanel activeStep={activeStep} preview={preview} onAction={onAction} />;
  }

  return <RunCurrentDefaultPanel activeStep={activeStep} onAction={onAction} />;
}

function NodeIoSummary({ step }: { step: RuntimePreviewStep }) {
  const inputs = step.inputs ?? [];
  const outputs = step.outputs ?? [];

  if (inputs.length === 0 && outputs.length === 0) {
    return <p className="workbench-panel-copy">该节点暂无可展示的输入输出快照，真实运行态接入后会按节点类型展示结构化数据。</p>;
  }

  return (
    <div className="workbench-node-io-grid">
      <div className="workbench-node-field-list">
        <div className="workbench-subsection-title"><FileText size={15} /> 节点输入</div>
        {inputs.length > 0 ? inputs.map((field) => (
          <div key={field.label} className="workbench-node-field">
            <span>{field.label}</span>
            <strong>{field.sensitive ? "******" : field.value}</strong>
          </div>
        )) : <p>无额外输入</p>}
      </div>
      <div className="workbench-node-field-list">
        <div className="workbench-subsection-title"><CheckCircle2 size={15} /> 节点输出</div>
        {outputs.length > 0 ? outputs.map((field) => (
          <div key={field.label} className="workbench-node-field">
            <span>{field.label}</span>
            <strong>{field.sensitive ? "******" : field.value}</strong>
          </div>
        )) : <p>等待节点完成后生成</p>}
      </div>
    </div>
  );
}

function traceStepPillLabel(state: RuntimeStepState): string {
  if (state === "done") {
    return "已完成";
  }
  if (state === "running") {
    return "执行中";
  }
  if (state === "waiting") {
    return "等待处理";
  }
  return "待执行";
}

function RunTracePanel({
  preview,
  selectedStepIndex,
  onSelectStep,
  onClearStep,
}: {
  preview: RuntimePreview;
  selectedStepIndex: number | null;
  onSelectStep: (index: number) => void;
  onClearStep: () => void;
}) {
  const selectedStep = selectedStepIndex === null ? null : preview.steps[selectedStepIndex] ?? null;
  const traceableSteps = preview.steps
    .map((step, index) => ({ step, index }))
    .filter(({ step }) => step.state !== "pending");

  if (selectedStep && selectedStepIndex !== null) {
    const relatedEvents = preview.events.filter((event) => event.stepTitle === selectedStep.title);

    return (
      <div className="workbench-trace-layout">
        <section className="sys-preview-card workbench-run-section">
          <div className="workbench-run-section-head">
            <div>
              <button type="button" className="workbench-trace-back" onClick={onClearStep}>
                <ChevronLeft size={16} aria-hidden="true" />
                返回总览链路
              </button>
              <div className="sys-preview-card-title"><History size={16} /> 链路详情：{selectedStep.title}</div>
              <p>{selectedStep.description}</p>
            </div>
            <span className={`workbench-run-pill workbench-run-pill--${selectedStep.state === "done" ? "done" : selectedStep.state === "waiting" ? "waiting" : "running"}`}>
              {traceStepPillLabel(selectedStep.state)}
            </span>
          </div>
          {selectedStep.completedAt ? (
            <p className="workbench-trace-step-meta">完成时间 {selectedStep.completedAt}</p>
          ) : null}
          <div className="workbench-subsection-title"><FileText size={15} /> 审计事件</div>
          <div className="workbench-event-timeline">
            {relatedEvents.length > 0 ? relatedEvents.map((event) => (
              <div key={`${event.time}-${event.title}`} className={`workbench-event-item workbench-event-item--${event.tone}`}>
                <span>{event.time}</span>
                <div>
                  <strong>{event.title}</strong>
                  <p>{event.description}</p>
                </div>
              </div>
            )) : (
              <p className="workbench-panel-copy">该步骤暂无审计事件，真实运行态接入后会按节点写入调用与审批记录。</p>
            )}
          </div>
        </section>

        <section className="sys-preview-card workbench-run-section workbench-node-detail-card">
          <div className="sys-preview-card-title"><FileText size={16} /> 节点输入输出</div>
          <NodeIoSummary step={selectedStep} />
        </section>
      </div>
    );
  }

  return (
    <div className="workbench-trace-layout">
      <section className="sys-preview-card workbench-run-section">
        <div className="workbench-run-section-head">
          <div>
            <div className="sys-preview-card-title"><History size={16} /> 执行链路</div>
            <p>按流程顺序展示已发生或进行中的节点，点击任一链路可查看该步骤的输入、输出和审计事件。</p>
          </div>
        </div>
        <div className="workbench-trace-step-list">
          {traceableSteps.map(({ step, index }) => (
            <button
              key={step.title}
              type="button"
              className={`workbench-trace-step-link workbench-trace-step-link--${step.state}`}
              onClick={() => onSelectStep(index)}
            >
              <span className="workbench-trace-step-link-index">{index + 1}</span>
              <span className="workbench-trace-step-link-main">
                <strong>{step.title}</strong>
                <small>{step.subtitle}{step.completedAt ? ` · ${step.completedAt} 完成` : ""}</small>
              </span>
              <span className={`workbench-run-pill workbench-run-pill--${step.state === "done" ? "done" : step.state === "waiting" ? "waiting" : "running"}`}>
                {traceStepPillLabel(step.state)}
              </span>
              <ArrowRight size={14} aria-hidden="true" />
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}

function RunDeliveriesPanel({ preview, onAction }: { preview: RuntimePreview; onAction: (label: string) => void }) {
  return (
    <section className="sys-preview-card workbench-run-section">
      <div className="workbench-run-section-head">
        <div className="sys-preview-card-title"><Send size={16} /> 交付物</div>
        <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onAction("刷新交付物")}>
          <RefreshCcw size={14} aria-hidden="true" />
          刷新
        </button>
      </div>
      <div className="workbench-delivery-list">
        {preview.deliveries.map((delivery) => (
          <div key={delivery.name} className="workbench-delivery-row">
            <FileText size={16} aria-hidden="true" />
            <div>
              <strong>{delivery.name}</strong>
              <small>{delivery.status} · {delivery.meta}</small>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function RunMetaCard({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: string }) {
  return (
    <div className="workbench-run-meta-card">
      <span><Icon size={16} aria-hidden="true" /></span>
      <div>
        <small>{label}</small>
        <strong>{value}</strong>
      </div>
    </div>
  );
}

function CreatedTaskTodoItem({
  workflow,
  preview,
  onOpen,
}: {
  workflow: WorkbenchAvailableWorkflowRow;
  preview: RuntimePreview;
  onOpen: () => void;
}) {
  return (
    <div className="workbench-created-todo">
      <div className="workbench-created-todo-main">
        <span className="workbench-agent-icon workbench-agent-icon--waiting">
          <ShieldAlert size={16} aria-hidden="true" />
        </span>
        <div>
          <strong>{workflow.name}</strong>
          <p>{preview.activeNode} · 等待处理人审批高风险 MCP 调用</p>
          <small>{preview.runId} · v{preview.workflowVersion} · {preview.startedAt}</small>
        </div>
      </div>
      <div className="workbench-created-todo-actions">
        <span className="workbench-run-pill workbench-run-pill--waiting">待处理</span>
        <button type="button" className="sys-btn sys-btn--primary sys-btn--sm" onClick={onOpen}>
          处理
          <ArrowRight size={14} aria-hidden="true" />
        </button>
      </div>
    </div>
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
