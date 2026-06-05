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
  WorkbenchRunDetail,
  WorkbenchSummary,
  WorkbenchTaskRunRow,
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

// 运行状态对应的颜色标记，后端返回中文 stateLabel，前端只负责稳定映射。
const stateColors: Record<string, string> = {
  "运行中": "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300",
  "已暂停": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "已完成": "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  "等待人工审核": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "等待用户输入": "bg-indigo-100 text-indigo-800 dark:bg-indigo-900/40 dark:text-indigo-300",
  "等待交付确认": "bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300",
};

const AVAILABLE_PAGE_SIZE = 12;
const TASK_RUN_PAGE_SIZE = 10;

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
  const [taskRuns, setTaskRuns] = useState<WorkbenchTaskRunRow[]>([]);
  const [taskRunsTotal, setTaskRunsTotal] = useState(0);
  const [taskRunsPage, setTaskRunsPage] = useState(1);
  const [taskRunsLoading, setTaskRunsLoading] = useState(false);
  const [taskRunsError, setTaskRunsError] = useState<string | null>(null);
  const [openedRunDetail, setOpenedRunDetail] = useState<WorkbenchRunDetail | null>(null);
  const [openedRunLoading, setOpenedRunLoading] = useState(false);
  const [creatingWorkflowId, setCreatingWorkflowId] = useState<string | null>(null);
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

  const loadTaskRuns = useCallback(async (page: number) => {
    if (!tenantId || !token) {
      setTaskRuns([]);
      setTaskRunsTotal(0);
      return;
    }

    setTaskRunsLoading(true);
    setTaskRunsError(null);
    try {
      const data = await workbenchApi.listRuns(tenantId, token, "", "all", page, TASK_RUN_PAGE_SIZE);
      setTaskRuns(data.items);
      setTaskRunsTotal(data.total);
      setTaskRunsPage(data.page);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务中心加载失败";
      console.warn("[workbench] 任务中心加载失败", {
        code: error instanceof AgentumApiError ? error.code : "unknown",
      });
      setTaskRunsError(reason);
      setTaskRuns([]);
      setTaskRunsTotal(0);
    } finally {
      setTaskRunsLoading(false);
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

  useEffect(() => {
    if (activeSurface !== "workbench") {
      return;
    }
    void loadTaskRuns(taskRunsPage);
  }, [activeSurface, taskRunsPage, loadTaskRuns]);

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

  async function handleLaunchTask(workflow: WorkbenchAvailableWorkflowRow) {
    if (!tenantId || !token) {
      return;
    }
    if (!workflow.canLaunch) {
      messageApi.warning(workflow.launchBlockedReason || "当前账号没有发起该流程的权限");
      return;
    }

    setCreatingWorkflowId(workflow.id);
    try {
      const detail = await workbenchApi.createRun(tenantId, token, workflow.id, `${workflow.name}任务`);
      setOpenedRunDetail(detail);
      setWorkflowDrawer(null);
      setActiveWorkbenchTab("tasks");
      messageApi.success(`已创建「${detail.title}」`);
      void loadSummary();
      void loadTaskRuns(1);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务创建失败";
      console.warn("[workbench] 任务创建失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    } finally {
      setCreatingWorkflowId(null);
    }
  }

  async function handleOpenRun(runId: string) {
    if (!tenantId || !token) {
      return;
    }
    setOpenedRunLoading(true);
    try {
      const detail = await workbenchApi.getRun(tenantId, token, runId);
      setOpenedRunDetail(detail);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务详情加载失败";
      console.warn("[workbench] 任务详情加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    } finally {
      setOpenedRunLoading(false);
    }
  }

  async function handleCompleteOpenTodo(comment: string) {
    if (!tenantId || !token || !openedRunDetail?.openTodo) {
      messageApi.info("当前没有需要处理的待办");
      return;
    }
    try {
      const detail = await workbenchApi.completeTodo(tenantId, token, openedRunDetail.openTodo.id, comment);
      setOpenedRunDetail(detail);
      messageApi.success("待办已提交，流程已继续推进");
      void loadSummary();
      void loadTaskRuns(taskRunsPage);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "待办提交失败";
      console.warn("[workbench] 待办提交失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  function handleSubmitKeyword() {
    const trimmed = availableKeywordInput.trim();
    setAvailableKeyword(trimmed);
    setAvailablePage(1);
  }

  // 概览指标卡片基于真实 summary.metrics 渲染；运行态状态异常时保留明确兜底。
  const metricCards: MetricCard[] = useMemo(() => {
    const metrics = summary?.metrics;
    const runtimeReady = summary?.runtimeAvailable ?? false;
    return [
      {
        label: "我的待办",
        value: runtimeReady ? String(metrics?.pendingTodoTotal ?? 0) : "—",
        hint: runtimeReady ? "需要我处理的暂停点" : "运行态状态待确认",
        tone: "primary",
        icon: UserRoundCheck,
      },
      {
        label: "进行中任务",
        value: runtimeReady ? String(metrics?.runningRunTotal ?? 0) : "—",
        hint: runtimeReady ? "我可以查看的运行实例" : "运行态状态待确认",
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
  const runtimeStatusLabel = summary?.runtimeStatusLabel ?? "正在加载运行态";
  const openedRunPreview = useMemo(
    () => openedRunDetail ? buildRuntimePreviewFromRun(openedRunDetail) : null,
    [openedRunDetail],
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
        <section className={`min-w-0 flex-1${activeSurface === "workbench" && tenantId && openedRunDetail ? " overflow-hidden" : ""}`}>
          {activeSurface === null ? (
            <div className="min-h-screen bg-[var(--color-bg-page)] pb-10">
              <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
                <header className="surface-page-chrome surface-page-chrome--actions-only flex justify-end pb-2 pt-4">
                  <WorkbenchGlobalActions />
                </header>
              <section
                className="agent-card mt-16 flex min-h-[360px] items-center justify-center p-8 text-center sm:mt-20"
                aria-label="无可访问页签"
              >
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
            tenantId && openedRunDetail && openedRunPreview ? (
              <div className="workbench-task-run-host">
                <div className="workbench-immersive-topbar">
                  <WorkbenchGlobalActions />
                </div>
                <div className="workbench-task-run-host-inner">
                  <WorkbenchTaskRunDetail
                    run={openedRunDetail}
                    preview={openedRunPreview}
                    runtimeStatusLabel={runtimeStatusLabel}
                    onBack={() => {
                      setOpenedRunDetail(null);
                      setActiveWorkbenchTab("tasks");
                    }}
                    onSaveToTodo={() => messageApi.info("任务已经保存为后端运行实例，可从任务中心继续处理")}
                    onCompleteTodo={handleCompleteOpenTodo}
                    onAction={(label) => messageApi.info(`${label} 的后端动作将在运行状态机后续版本接入`)}
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
                                meta={runtimeAvailable ? `${recentRuns.filter((record) => record.state !== "completed").length} 个可继续任务` : runtimeStatusLabel}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                              <WorkbenchFeatureCard
                                icon={Archive}
                                title="历史完成"
                                description="查看已完成任务与交付结果，后续可进入运行详情追溯过程。"
                                meta={runtimeAvailable ? `${recentRuns.filter((record) => record.state === "completed").length} 个完成任务` : runtimeStatusLabel}
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
                              <RuntimePlaceholder label={runtimeStatusLabel} hint="请稍后刷新，或检查当前账号的业务工作台访问权限。" />
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
                          {runtimeAvailable ? (
                            pendingTodos.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无需要我处理的待办" />
                            ) : (
                              <div className="space-y-2">
                                {pendingTodos.map((todo) => (
                                  <PendingTodoListItem key={todo.id} todo={todo} onOpen={() => void handleOpenRun(todo.runId)} />
                                ))}
                              </div>
                            )
                          ) : (
                            <RuntimePlaceholder label={runtimeStatusLabel} hint="请稍后刷新，或检查当前账号是否存在可处理的运行暂停点。" />
                          )}
                        </section>

                        <section className="sys-preview-card">
                          <div className="sys-preview-card-title"><History size={16} /> 任务记录</div>
                          {taskRunsError ? (
                            <RuntimePlaceholder label="任务中心加载失败" hint={taskRunsError} />
                          ) : taskRunsLoading || openedRunLoading ? (
                            <div className="workflow-definition-empty-state">
                              <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                              <p>正在加载任务记录</p>
                            </div>
                          ) : runtimeAvailable ? (
                            taskRuns.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无任务记录" />
                            ) : (
                              <div className="space-y-2">
                                {taskRuns.map((record) => (
                                  <TaskRunListItem
                                    key={record.id}
                                    record={record}
                                    actionLabel={record.state === "completed" ? "查看" : "继续"}
                                    onOpen={() => void handleOpenRun(record.id)}
                                  />
                                ))}
                              </div>
                            )
                          ) : (
                            <RuntimePlaceholder label={runtimeStatusLabel} hint="请稍后刷新，或检查当前账号是否有可查看的任务运行记录。" />
                          )}
                          {taskRunsTotal > TASK_RUN_PAGE_SIZE ? (
                            <div className="agent-admin-pagination-wrap mt-4">
                              <Pagination
                                className="agent-admin-pagination"
                                current={taskRunsPage}
                                total={taskRunsTotal}
                                pageSize={TASK_RUN_PAGE_SIZE}
                                showSizeChanger={false}
                                onChange={(page) => setTaskRunsPage(page)}
                              />
                            </div>
                          ) : null}
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
                  launching={creatingWorkflowId === workflowDrawer?.id}
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
  const visibilityLabel = workflow.canLaunch
    ? workflow.visibility === "owner"
      ? "我创建的流程"
      : workflow.visibility === "manager"
        ? "管理视角可发起"
        : "已开放"
    : "无发起权限";
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
        <span className={`workflow-launch-card-tag ${workflow.canLaunch ? "workflow-launch-card-tag--owner" : "workflow-launch-card-tag--time"}`}>
          {visibilityLabel}
        </span>
      </span>
      <span className="workflow-launch-card-meta">
        {workflow.canLaunch ? "查看并发起" : "查看权限状态"}
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
  launching,
}: {
  workflow: WorkbenchAvailableWorkflowRow | null;
  rootClassName: string;
  onClose: () => void;
  onLaunch: (workflow: WorkbenchAvailableWorkflowRow) => void;
  launching: boolean;
}) {
  if (!workflow) {
    return null;
  }

  const publishedAt = workflow.publishedAt ? new Date(workflow.publishedAt) : null;
  const publishedLabel = publishedAt ? publishedAt.toLocaleString("zh-CN", { hour12: false }) : "—";

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
          <h3>权限状态</h3>
          <p>{workflow.canLaunch ? "当前账号可以基于该发布版本创建任务，创建后会生成运行实例、节点链路和首个待办。" : workflow.launchBlockedReason || "当前账号没有该流程的读取或发起权限。"}</p>
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
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onLaunch(workflow)} disabled={!workflow.canLaunch || launching}>
            {launching ? <Loader2 size={16} className="animate-spin" aria-hidden="true" /> : <PlayCircle size={16} aria-hidden="true" />}
            {workflow.canLaunch ? "发起任务" : "无权限发起"}
          </button>
        </div>
      </div>
    </Drawer>
  );
}

function buildRuntimePreviewFromRun(run: WorkbenchRunDetail): RuntimePreview {
  const activeNode = run.currentNodeName ?? run.nodes.find((node) => node.state === "waiting" || node.state === "running")?.name ?? "已结束";
  const steps = run.nodes.map((node): RuntimePreviewStep => {
    const state = mapNodeState(node.state);
    return {
      title: node.name,
      subtitle: node.stateLabel,
      state,
      kind: mapNodeKind(node.nodeType),
      description: nodeDescription(node.nodeType, node.config),
      inputs: objectToFields(node.inputs),
      outputs: objectToFields(node.outputs),
      completedAt: state === "done" ? formatTime(run.updatedAt) : undefined,
      chatMessages: nodeMessages(node),
      capabilities: nodeCapabilities(node),
      allowsFollowUp: node.nodeType === "agent" || node.nodeType === "parallel_group",
      allowsRegenerate: node.nodeType === "agent" || node.nodeType === "parallel_group",
      allowsInterrupt: node.state === "running",
    };
  });

  return {
    runId: run.id.slice(0, 8).toUpperCase(),
    statusLabel: run.stateLabel,
    activeNode,
    progress: run.progressPercent,
    startedAt: formatDateTime(run.startedAt),
    ownerName: run.ownerName,
    workflowVersion: run.workflowVersionNumber,
    steps,
    agents: run.nodes
      .filter((node) => node.nodeType === "agent" || node.nodeType === "parallel_group")
      .map((node) => ({
        name: node.name,
        capability: node.nodeType === "parallel_group" ? "智能体集群节点" : "智能体节点",
        status: node.stateLabel,
        statusTone: node.state === "completed" ? "done" : node.state === "waiting" ? "waiting" : "running",
        output: stringifyValue(node.outputs.summary ?? "等待节点输出"),
        duration: "记录中",
      })),
    events: run.events.map((event) => ({
      time: formatTime(event.eventTime),
      title: event.title,
      description: event.description,
      tone: event.eventType === "node_waiting" ? "warning" : event.eventType === "node_completed" || event.eventType === "run_completed" ? "success" : "info",
      stepTitle: run.nodes.find((node) => node.nodeId === event.nodeId)?.name ?? "任务",
    })),
    deliveries: run.nodes
      .filter((node) => node.nodeType === "delivery")
      .map((node) => ({
        name: node.name,
        status: node.stateLabel,
        meta: stringifyValue(node.outputs.summary ?? "交付确认后生成"),
      })),
  };
}

function mapNodeState(state: string): RuntimeStepState {
  if (state === "completed") return "done";
  if (state === "waiting") return "waiting";
  if (state === "running") return "running";
  return "pending";
}

function mapNodeKind(nodeType: string): RuntimeNodeKind {
  if (nodeType === "trigger") return "launch";
  if (nodeType === "user_input") return "input";
  if (nodeType === "parallel_group") return "multiAgent";
  if (nodeType === "human_review") return "approval";
  if (nodeType === "delivery") return "delivery";
  return "agent";
}

function objectToFields(values: Record<string, unknown>): RuntimeNodeField[] {
  return Object.entries(values ?? {}).map(([label, value]) => ({ label, value: stringifyValue(value) }));
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

function nodeDescription(nodeType: string, config: Record<string, unknown>): string {
  const summary = stringifyValue(config.summary);
  if (summary) return summary;
  if (nodeType === "user_input") return "等待业务用户补充当前节点所需资料，提交后后端会继续推进后续节点。";
  if (nodeType === "human_review") return "等待人工审核意见，审核记录会写入运行事件。";
  if (nodeType === "delivery") return "等待交付确认，确认后生成或触发交付动作。";
  if (nodeType === "parallel_group") return "并行智能体节点已按运行快照记录，真实执行器接入后会展示各子任务输出。";
  return "节点运行状态来自后端运行实例。";
}

function nodeMessages(node: WorkbenchRunDetail["nodes"][number]): RuntimeChatMessage[] {
  const summary = stringifyValue(node.outputs.summary);
  if (summary) {
    return [{ id: `${node.id}-summary`, role: "assistant", author: node.name, content: summary }];
  }
  if (node.state === "waiting") {
    return [{ id: `${node.id}-waiting`, role: "system", author: "系统", content: `${node.name}正在等待处理。` }];
  }
  return [{ id: `${node.id}-pending`, role: "system", author: "系统", content: `${node.name}尚未开始执行。` }];
}

function nodeCapabilities(node: WorkbenchRunDetail["nodes"][number]): RuntimeCapabilityItem[] {
  if (node.nodeType !== "agent" && node.nodeType !== "parallel_group") {
    return [];
  }
  return [{
    id: `${node.id}-runtime`,
    name: node.nodeType === "parallel_group" ? "智能体集群" : "智能体运行器",
    kind: "agent",
    status: node.state === "completed" ? "done" : node.state === "waiting" ? "waiting" : node.state === "running" ? "running" : "idle",
    statusLabel: node.stateLabel,
    summary: "状态来自后端节点运行记录",
  }];
}

function formatDateTime(value: string): string {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
}

function formatTime(value: string): string {
  return value ? new Date(value).toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" }) : "—";
}

function WorkbenchTaskRunDetail({
  run,
  preview,
  runtimeStatusLabel,
  onBack,
  onSaveToTodo,
  onCompleteTodo,
  onAction,
}: {
  run: WorkbenchRunDetail;
  preview: RuntimePreview;
  runtimeStatusLabel: string;
  onBack: () => void;
  onSaveToTodo: () => void;
  onCompleteTodo: (comment: string) => void;
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
            <h2>{run.title}</h2>
            <span className="workbench-run-status">
              <span className="workbench-run-status-dot" />
              {preview.statusLabel}
            </span>
          </div>
          <p>{run.workflowName} · 运行编号 {preview.runId} · v{preview.workflowVersion} · 当前节点：{activeStep.title}</p>
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
            {activeRunTab === "overview" ? <RunOverviewPanel run={run} preview={preview} /> : null}
            {activeRunTab === "current" ? (
              <RunCurrentPanel
                preview={preview}
                activeStep={activeStep}
                onSaveToTodo={onSaveToTodo}
                onCompleteTodo={onCompleteTodo}
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

function RunOverviewPanel({ run, preview }: { run: WorkbenchRunDetail; preview: RuntimePreview }) {
  return (
    <div className="workbench-panel-grid">
      <section className="sys-preview-card workbench-run-section">
        <div className="sys-preview-card-title"><LayoutDashboard size={16} /> 任务概览</div>
        <p className="workbench-panel-copy">任务运行详情来自后端运行实例，节点状态、输入输出和事件链路均按发布版本快照生成。</p>
        <div className="workbench-run-meta-grid workbench-run-meta-grid--compact">
          <RunMetaCard icon={FileText} label="运行编号" value={preview.runId} />
          <RunMetaCard icon={GitBranch} label="流程版本" value={`v${preview.workflowVersion}`} />
          <RunMetaCard icon={UserRoundCheck} label="发起人" value={preview.ownerName} />
          <RunMetaCard icon={GitBranch} label="流程名称" value={run.workflowName} />
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
  onCompleteTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onCompleteTodo: (comment: string) => void;
  onAction: (label: string) => void;
}) {
  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onCompleteTodo("提交当前输入资料")}>
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
  onCompleteTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onCompleteTodo: (comment: string) => void;
  onAction: (label: string) => void;
}) {
  const messages = activeStep.chatMessages ?? [];

  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onCompleteTodo("审核通过")}>
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
  onCompleteTodo,
  onAction,
}: {
  activeStep: RuntimePreviewStep;
  preview: RuntimePreview;
  onCompleteTodo: (comment: string) => void;
  onAction: (label: string) => void;
}) {
  return (
    <CurrentNodeShell
      activeStep={activeStep}
      footer={(
        <div className="workbench-current-actions">
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onCompleteTodo("确认交付")}>
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
  onCompleteTodo,
  onAction,
}: {
  preview: RuntimePreview;
  activeStep: RuntimePreviewStep;
  onSaveToTodo: () => void;
  onCompleteTodo: (comment: string) => void;
  onAction: (label: string) => void;
}) {
  if (activeStep.kind === "input") {
    return <RunCurrentInputPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onCompleteTodo={onCompleteTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "multiAgent") {
    return <RunCurrentMultiAgentPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "agent") {
    return <RunCurrentAgentPanel activeStep={activeStep} onAction={onAction} />;
  }

  if (activeStep.kind === "approval") {
    return <RunCurrentApprovalPanel activeStep={activeStep} onSaveToTodo={onSaveToTodo} onCompleteTodo={onCompleteTodo} onAction={onAction} />;
  }

  if (activeStep.kind === "delivery") {
    return <RunCurrentDeliveryPanel activeStep={activeStep} preview={preview} onCompleteTodo={onCompleteTodo} onAction={onAction} />;
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

function RuntimePlaceholder({ label, hint }: { label: string; hint: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-md border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-page)] px-4 py-8 text-center">
      <PauseCircle className="h-7 w-7 text-[var(--color-text-tertiary)]" aria-hidden="true" />
      <p className="text-sm font-medium text-[var(--color-text-primary)]">{label}</p>
      <p className="agent-muted max-w-sm text-xs leading-relaxed">{hint}</p>
    </div>
  );
}

function PendingTodoListItem({ todo, onOpen }: { todo: WorkbenchPendingTodoRow; onOpen: () => void }) {
  const createdLabel = todo.createdAt ? new Date(todo.createdAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <UserRoundCheck size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{todo.title}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{todo.workflowName} · {todo.nodeName} · {todo.waitingFor}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{todo.action} · 创建于 {createdLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[todo.waitingReason] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {todo.waitingReason}
        </span>
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          处理
        </button>
      </div>
    </div>
  );
}

function RecentRunListItem({ record, actionLabel }: { record: WorkbenchRecentRunRow; actionLabel?: string }) {
  const Icon = record.state === "completed" || record.stateLabel === "已完成" ? CheckCircle2 : record.state === "paused" || record.stateLabel === "已暂停" ? PauseCircle : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.currentNode} · {record.ownerName}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.stateLabel] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.stateLabel}
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

function TaskRunListItem({
  record,
  actionLabel,
  onOpen,
}: {
  record: WorkbenchTaskRunRow;
  actionLabel: string;
  onOpen: () => void;
}) {
  const Icon = record.state === "completed" ? CheckCircle2 : record.state === "paused" ? PauseCircle : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} v{record.workflowVersionNumber} · {record.currentNodeName}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · {record.progressPercent}% · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {record.hasOpenTodo ? <span className="workbench-run-pill workbench-run-pill--waiting">有待办</span> : null}
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.stateLabel] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.stateLabel}
        </span>
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          {actionLabel}
        </button>
      </div>
    </div>
  );
}
