import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  Activity,
  AlertCircle,
  ArrowRight,
  ChevronLeft,
  Bot,
  CheckCircle2,
  Clock3,
  FileText,
  GitBranch,
  History,
  Inbox,
  LayoutDashboard,
  Library,
  ListTodo,
  Loader2,
  LogOut,
  PanelLeft,
  PauseCircle,
  PlayCircle,
  Plug,
  RotateCcw,
  Save,
  Search,
  Send,
  Trash2,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  User,
  UserRoundCheck,
  UsersRound,
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
import { TaskRunWorkspace } from "../../components/runtime/TaskRunWorkspace";
import type {
  WorkbenchAvailableWorkflowNodeRow,
  WorkbenchAvailableWorkflowRow,
  WorkbenchRecentRunRow,
  WorkbenchRunDetail,
  WorkbenchSummary,
  WorkbenchTaskRunRow,
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



const workbenchTabs: WorkbenchTabMeta[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, description: "查看今日待办、可创建流程和运行态概况" },
  { key: "create", label: "创建任务", icon: PlayCircle, description: "浏览全部开放智能体流程，有权限的流程可创建任务" },
  { key: "tasks", label: "任务中心", icon: ListTodo, description: "待办处理未完成任务，任务记录仅查看已完成任务" },
];



// 运行状态对应的颜色标记，后端返回中文 stateLabel，前端只负责稳定映射。
const stateColors: Record<string, string> = {
  "运行中": "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300",
  "已暂停": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "已完成": "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  "已失败": "bg-rose-100 text-rose-800 dark:bg-rose-950/50 dark:text-rose-300",
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
  const [activeTasks, setActiveTasks] = useState<WorkbenchTaskRunRow[]>([]);
  const [activeTasksTotal, setActiveTasksTotal] = useState(0);
  const [activeTasksPage, setActiveTasksPage] = useState(1);
  const [activeTasksLoading, setActiveTasksLoading] = useState(false);
  const [activeTasksError, setActiveTasksError] = useState<string | null>(null);
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
  const { launchableWorkflows, blockedWorkflows } = useMemo(() => ({
    launchableWorkflows: availableWorkflows.filter((workflow) => workflow.canLaunch),
    blockedWorkflows: availableWorkflows.filter((workflow) => !workflow.canLaunch),
  }), [availableWorkflows]);
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

  const loadActiveTasks = useCallback(async (page: number) => {
    if (!tenantId || !token) {
      setActiveTasks([]);
      setActiveTasksTotal(0);
      return;
    }

    setActiveTasksLoading(true);
    setActiveTasksError(null);
    try {
      const data = await workbenchApi.listActiveRuns(tenantId, token, "", page, TASK_RUN_PAGE_SIZE);
      setActiveTasks(data.items);
      setActiveTasksTotal(data.total);
      setActiveTasksPage(data.page);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "待办列表加载失败";
      console.warn("[workbench] 待办列表加载失败", {
        code: error instanceof AgentumApiError ? error.code : "unknown",
      });
      setActiveTasksError(reason);
      setActiveTasks([]);
      setActiveTasksTotal(0);
    } finally {
      setActiveTasksLoading(false);
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
      const data = await workbenchApi.listRuns(tenantId, token, "", page, TASK_RUN_PAGE_SIZE);
      setTaskRuns(data.items);
      setTaskRunsTotal(data.total);
      setTaskRunsPage(data.page);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务记录加载失败";
      console.warn("[workbench] 任务记录加载失败", {
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
    void loadActiveTasks(activeTasksPage);
  }, [activeSurface, activeTasksPage, loadActiveTasks]);

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
      const detail = await workbenchApi.createRun(tenantId, token, workflow.id, workflow.name);
      setOpenedRunDetail(detail);
      setWorkflowDrawer(null);
      messageApi.success(`已发起「${detail.title}」，请先保存后才会进入待办`);
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
    if (!tenantId || !token || !openedRunDetail?.openTodo?.openTodoId) {
      messageApi.info("当前没有需要处理的待办");
      return;
    }
    if (openedRunDetail.readOnly) {
      messageApi.warning("已完成任务只能查看，不能继续处理");
      return;
    }
    try {
      const detail = await workbenchApi.completeTodo(tenantId, token, openedRunDetail.openTodo.openTodoId, comment);
      setOpenedRunDetail(detail);
      messageApi.success("待办已提交，流程已继续推进");
      void loadSummary();
      void loadActiveTasks(activeTasksPage);
      void loadTaskRuns(taskRunsPage);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "待办提交失败";
      console.warn("[workbench] 待办提交失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  async function handleSaveRun() {
    if (!tenantId || !token || !openedRunDetail) {
      return;
    }
    if (openedRunDetail.saved) {
      messageApi.info("任务已保存");
      return;
    }
    try {
      const savedDetail = await workbenchApi.saveRun(tenantId, token, openedRunDetail.id, openedRunDetail.title);
      setOpenedRunDetail(savedDetail);
      messageApi.success(savedDetail.readOnly ? "任务已保存，可在任务记录中查看" : "任务已保存，可在待办中继续处理");
      void loadSummary();
      if (savedDetail.readOnly) {
        void loadTaskRuns(1);
        setTaskRunsPage(1);
      } else {
        void loadActiveTasks(1);
        setActiveTasksPage(1);
      }
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务保存失败";
      console.warn("[workbench] 任务保存失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  async function handleDeleteRun(runId: string, closeWorkspace = false) {
    if (!tenantId || !token) {
      return;
    }
    try {
      await workbenchApi.deleteRun(tenantId, token, runId);
      if (openedRunDetail?.id === runId) {
        setOpenedRunDetail(null);
        if (closeWorkspace) {
          setActiveWorkbenchTab("tasks");
        }
      }
      messageApi.success("任务已删除");
      void loadSummary();
      void loadActiveTasks(activeTasksPage);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务删除失败";
      console.warn("[workbench] 任务删除失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  async function handleRollbackRun(nodeRunId: string) {
    if (!tenantId || !token || !openedRunDetail) {
      return;
    }
    if (openedRunDetail.readOnly) {
      messageApi.warning("已完成任务不能回退");
      return;
    }
    try {
      const detail = await workbenchApi.rollbackRun(tenantId, token, openedRunDetail.id, nodeRunId);
      setOpenedRunDetail(detail);
      messageApi.success("已回退到选定步骤，流程将从此处重新开始");
      void loadSummary();
      void loadActiveTasks(activeTasksPage);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "步骤回退失败";
      console.warn("[workbench] 步骤回退失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  async function handleBackFromRun() {
    if (!tenantId || !token || !openedRunDetail) {
      setOpenedRunDetail(null);
      setActiveWorkbenchTab("tasks");
      return;
    }
    if (!openedRunDetail.saved) {
      try {
        await workbenchApi.deleteRun(tenantId, token, openedRunDetail.id);
      } catch (error) {
        console.warn("[workbench] 未保存任务清理失败", error);
      }
    }
    setOpenedRunDetail(null);
    setActiveWorkbenchTab("tasks");
    void loadSummary();
    void loadActiveTasks(activeTasksPage);
    void loadTaskRuns(taskRunsPage);
  }

  function handleSubmitKeyword() {
    const trimmed = availableKeywordInput.trim();
    setAvailableKeyword(trimmed);
    setAvailablePage(1);
  }

  // 概览指标卡片基于真实 summary.metrics 渲染。
  const metricCards: MetricCard[] = useMemo(() => {
    const metrics = summary?.metrics;
    return [
      {
        label: "我的待办",
        value: metrics ? String(metrics.pendingTodoTotal) : "—",
        hint: "已保存且未完成的任务",
        tone: "primary",
        icon: UserRoundCheck,
      },
      {
        label: "进行中任务",
        value: metrics ? String(metrics.runningRunTotal) : "—",
        hint: "运行中或已暂停的实例",
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

  const recentRuns = summary?.recentRuns ?? [];

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
            tenantId && openedRunDetail ? (
              <div className="workbench-task-run-host flex flex-col h-[calc(100vh-var(--topbar-height,0px))] overflow-hidden">
                <div className="workbench-immersive-topbar shrink-0">
                  <WorkbenchGlobalActions />
                </div>
                <div className="workbench-task-run-host-inner flex-1 overflow-hidden p-6">
                  <TaskRunWorkspace
                    run={openedRunDetail}
                    tenantId={tenantId}
                    token={token || ""}
                    onBack={() => void handleBackFromRun()}
                    onSave={() => void handleSaveRun()}
                    onDelete={() => void handleDeleteRun(openedRunDetail.id, true)}
                    onReload={(updated) => {
                      setOpenedRunDetail(updated);
                      void loadSummary();
                      void loadActiveTasks(activeTasksPage);
                      void loadTaskRuns(taskRunsPage);
                    }}
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
                                description="处理已保存且未完成的任务，可继续推进、回退步骤或删除。"
                                meta={summary ? `${summary.metrics.pendingTodoTotal} 个待办` : "加载中..."}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                              <WorkbenchFeatureCard
                                icon={History}
                                title="任务记录"
                                description="查看已完成任务与交付结果，仅支持只读查看。"
                                meta={summary ? `${recentRuns.length} 个最近完成` : "加载中..."}
                                onClick={() => setActiveWorkbenchTab("tasks")}
                              />
                            </div>
                          </section>

                          <aside className="sys-preview-card">
                            <div className="sys-preview-card-title"><History size={16} /> 最近完成</div>
                            {recentRuns.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已完成任务" />
                            ) : (
                              <div className="space-y-2">
                                {recentRuns.slice(0, 4).map((record) => (
                                  <RecentRunListItem key={record.id} record={record} onOpen={() => void handleOpenRun(record.id)} />
                                ))}
                              </div>
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
                          <div className="workflow-launch-sections">
                            <section className="workflow-launch-section" aria-label="可发起流程">
                              <div className="workflow-launch-section-head">
                                <h3 className="workflow-launch-section-title">可发起流程</h3>
                                {launchableWorkflows.length > 0 ? (
                                  <p className="workflow-launch-section-desc">
                                    当前页共 {launchableWorkflows.length} 个流程，可直接创建任务。
                                  </p>
                                ) : null}
                              </div>
                              {launchableWorkflows.length > 0 ? (
                                <div className="sys-card-grid">
                                  {launchableWorkflows.map((workflow) => (
                                    <WorkflowLaunchCard key={workflow.id} workflow={workflow} onOpen={() => setWorkflowDrawer(workflow)} />
                                  ))}
                                </div>
                              ) : (
                                <div className="workflow-definition-empty-state">
                                  <PlayCircle className="h-8 w-8" aria-hidden="true" />
                                  <p>暂无可发起流程</p>
                                  <span>可先查看下方无权限流程，或联系流程负责人开通读取权限。</span>
                                </div>
                              )}
                            </section>

                            {blockedWorkflows.length > 0 ? (
                              <section className="workflow-launch-section workflow-launch-section--restricted" aria-label="暂无发起权限的流程">
                                <div className="workflow-launch-section-head">
                                  <h3 className="workflow-launch-section-title">暂无发起权限</h3>
                                  <p className="workflow-launch-section-desc">
                                    以下流程已发布，但当前账号尚未获得读取或发起权限，可查看权限状态并联系流程负责人。
                                  </p>
                                </div>
                                <div className="sys-card-grid">
                                  {blockedWorkflows.map((workflow) => (
                                    <WorkflowLaunchCard key={workflow.id} workflow={workflow} restricted onOpen={() => setWorkflowDrawer(workflow)} />
                                  ))}
                                </div>
                              </section>
                            ) : null}
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
                          {activeTasksError ? (
                            <RuntimePlaceholder label="待办加载失败" hint={activeTasksError} />
                          ) : activeTasksLoading ? (
                            <div className="workflow-definition-empty-state">
                              <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                              <p>正在加载待办</p>
                            </div>
                          ) : activeTasks.length === 0 ? (
                            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已保存的未完成任务" />
                          ) : (
                            <div className="space-y-2">
                              {activeTasks.map((record) => (
                                <ActiveTaskListItem
                                  key={record.id}
                                  record={record}
                                  onOpen={() => void handleOpenRun(record.id)}
                                  onDelete={() => void handleDeleteRun(record.id)}
                                />
                              ))}
                            </div>
                          )}
                          {activeTasksTotal > TASK_RUN_PAGE_SIZE ? (
                            <div className="agent-admin-pagination-wrap mt-4">
                              <Pagination
                                className="agent-admin-pagination"
                                current={activeTasksPage}
                                total={activeTasksTotal}
                                pageSize={TASK_RUN_PAGE_SIZE}
                                showSizeChanger={false}
                                onChange={(page) => setActiveTasksPage(page)}
                              />
                            </div>
                          ) : null}
                        </section>

                        <section className="sys-preview-card">
                          <div className="sys-preview-card-title"><History size={16} /> 任务记录</div>
                          {taskRunsError ? (
                            <RuntimePlaceholder label="任务记录加载失败" hint={taskRunsError} />
                          ) : taskRunsLoading ? (
                            <div className="workflow-definition-empty-state">
                              <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                              <p>正在加载任务记录</p>
                            </div>
                          ) : taskRuns.length === 0 ? (
                            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已完成任务记录" />
                          ) : (
                            <div className="space-y-2">
                              {taskRuns.map((record) => (
                                <TaskRunListItem
                                  key={record.id}
                                  record={record}
                                  onOpen={() => void handleOpenRun(record.id)}
                                />
                              ))}
                            </div>
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

function WorkflowLaunchCard({
  workflow,
  restricted = false,
  onOpen,
}: {
  workflow: WorkbenchAvailableWorkflowRow;
  restricted?: boolean;
  onOpen: () => void;
}) {
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
        <span className={`workflow-launch-card-tag ${restricted ? "workflow-launch-card-tag--restricted" : "workflow-launch-card-tag--owner"}`}>
          {visibilityLabel}
        </span>
      </span>
      <span className={`workflow-launch-card-meta${restricted ? " workflow-launch-card-meta--restricted" : ""}`}>
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
  const token = useAuthStore((state) => state.token);
  const tenantId = useAuthStore((state) => state.user?.tenantId);
  const [previewNodes, setPreviewNodes] = useState<WorkbenchAvailableWorkflowNodeRow[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");

  useEffect(() => {
    if (!workflow || !token || !tenantId) {
      setPreviewNodes([]);
      setPreviewLoading(false);
      setPreviewError("");
      return;
    }

    let cancelled = false;
    setPreviewLoading(true);
    setPreviewError("");

    void workbenchApi.getAvailableWorkflowPreview(tenantId, token, workflow.id)
      .then((preview) => {
        if (!cancelled) {
          setPreviewNodes(preview.nodes);
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        console.warn("[workbench] 流程节点预览加载失败", error);
        setPreviewError(error instanceof AgentumApiError ? error.message : "无法加载流程节点");
        setPreviewNodes([]);
      })
      .finally(() => {
        if (!cancelled) {
          setPreviewLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [tenantId, token, workflow?.id]);

  if (!workflow) {
    return null;
  }

  const publishedAt = workflow.publishedAt ? new Date(workflow.publishedAt) : null;
  const publishedLabel = publishedAt ? publishedAt.toLocaleString("zh-CN", { hour12: false }) : "—";

  return (
    <Drawer
      title="可发起流程详情"
      size={560}
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
          <h3>流程节点</h3>
          <p className="workbench-launch-drawer-section-lead">基于 v{workflow.latestVersionNumber} 发布快照，发起任务后将按以下步骤依次执行。</p>
          {previewLoading ? (
            <div className="workflow-drawer-loading">
              <Loader2 size={16} className="animate-spin" aria-hidden="true" />
              正在读取流程节点
            </div>
          ) : previewError ? (
            <p className="agent-muted text-sm leading-6">{previewError}</p>
          ) : previewNodes.length === 0 ? (
            <p className="agent-muted text-sm leading-6">当前发布版本还没有可展示的业务节点。</p>
          ) : (
            <div className="workflow-drawer-step-list">
              {previewNodes.map((node, index) => (
                <LaunchPreviewStep key={node.nodeId} node={node} index={index} />
              ))}
            </div>
          )}
        </section>

        <section className="workbench-launch-drawer-section">
          <h3>权限状态</h3>
          <p>{workflow.canLaunch ? "当前账号可以基于该发布版本创建任务，创建后会生成运行实例、节点链路和首个待办。" : workflow.launchBlockedReason || "当前账号没有该流程的读取或发起权限。"}</p>
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

function LaunchPreviewStep({ node, index }: { node: WorkbenchAvailableWorkflowNodeRow; index: number }) {
  const nodeType = formatLaunchNodeType(node.nodeType);
  const summary = node.summary || "尚未配置节点说明";
  const Icon = launchNodeIcon(node.nodeType);
  const tone = launchNodeTone(node.nodeType);

  return (
    <article className="workflow-drawer-step">
      <span className={`workflow-drawer-step-index workflow-drawer-step-index--${tone}`}>
        <Icon size={14} aria-hidden="true" />
      </span>
      <span className="min-w-0">
        <strong>{node.name}</strong>
        <small>{index + 1}. {nodeType} · {summary}</small>
      </span>
    </article>
  );
}

function formatLaunchNodeType(nodeType: string) {
  const labels: Record<string, string> = {
    trigger: "系统触发",
    user_input: "输入节点",
    agent: "单智能体节点",
    parallel_group: "智能体集群节点",
    merge: "组装节点",
    condition: "条件分支",
    human_review: "人工审核",
    delivery: "交付节点",
  };
  return labels[nodeType] ?? nodeType;
}

function launchNodeIcon(nodeType: string): LucideIcon {
  const icons: Record<string, LucideIcon> = {
    trigger: GitBranch,
    user_input: Inbox,
    agent: Bot,
    parallel_group: UsersRound,
    merge: GitBranch,
    condition: GitBranch,
    human_review: ShieldCheck,
    delivery: Send,
  };
  return icons[nodeType] ?? Bot;
}

function launchNodeTone(nodeType: string) {
  if (nodeType === "user_input") return "user_input";
  if (nodeType === "agent") return "agent";
  if (nodeType === "parallel_group") return "parallel_group";
  if (nodeType === "human_review") return "human_review";
  if (nodeType === "delivery") return "delivery";
  if (nodeType === "merge" || nodeType === "condition") return "merge";
  return "agent";
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

function ActiveTaskListItem({
  record,
  onOpen,
  onDelete,
}: {
  record: WorkbenchTaskRunRow;
  onOpen: () => void;
  onDelete: () => void;
}) {
  const Icon = record.state === "failed" ? AlertCircle : record.hasOpenTodo ? UserRoundCheck : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title} · {record.runNumber}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} · {record.currentNodeName}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · {record.progressPercent}% · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.stateLabel] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.stateLabel}
        </span>
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          {record.hasOpenTodo ? "处理" : "继续"}
        </button>
        <button type="button" className="sys-btn sys-btn--danger sys-btn--sm" onClick={onDelete}>
          删除
        </button>
      </div>
    </div>
  );
}

function RecentRunListItem({ record, onOpen }: { record: WorkbenchRecentRunRow; onOpen?: () => void }) {
  const Icon = record.state === "completed" || record.stateLabel === "已完成" ? CheckCircle2 : record.state === "paused" || record.stateLabel === "已暂停" ? PauseCircle : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title} · {record.runNumber}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} · {record.currentNode}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.stateLabel] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.stateLabel}
        </span>
        {onOpen ? (
          <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
            查看
          </button>
        ) : null}
      </div>
    </div>
  );
}

function TaskRunListItem({
  record,
  onOpen,
}: {
  record: WorkbenchTaskRunRow;
  onOpen: () => void;
}) {
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <CheckCircle2 size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title} · {record.runNumber}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} · {record.currentNodeName}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · {record.progressPercent}% · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[record.stateLabel] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {record.stateLabel}
        </span>
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          查看
        </button>
      </div>
    </div>
  );
}
