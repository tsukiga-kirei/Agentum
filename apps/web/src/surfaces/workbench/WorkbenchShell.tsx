import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  Activity,
  AlertCircle,
  ArrowRight,
  CalendarClock,
  ChevronLeft,
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock3,
  FileText,
  GitBranch,
  History,
  Inbox,
  LayoutDashboard,
  Library,
  ListTodo,
  Loader2,
  PauseCircle,
  PlayCircle,
  Plug,
  RotateCcw,
  Save,
  Search,
  Send,
  Trash2,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  UserRoundCheck,
  UsersRound,
  Wrench,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Drawer, Empty, Pagination, Segmented, Select, message } from "antd";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { SurfacePageLayout } from "../../components/workbench/SurfacePageLayout";
import { TaskRunWorkspace } from "../../components/runtime/TaskRunWorkspace";
import { WorkflowSchedulesPanel } from "./WorkflowSchedulesPanel";
import { useAuthStore } from "../../stores/authStore";
import { useFlipText } from "../../motion/useFlipText";
import { AgentumApiError, workbenchApi } from "../../services/apiClient";
import { parsePositiveInt, paths } from "../../routes/paths";
import type {
  WorkbenchAvailableWorkflowNodeRow,
  WorkbenchAvailableWorkflowRow,
  WorkbenchRecentRunRow,
  WorkbenchRunDetail,
  WorkbenchSummary,
  WorkbenchTaskRunRow,
} from "../../types/workbench";
import { getThemedDrawerRootClassName } from "../../utils/theme";

type WorkbenchTab = "overview" | "create" | "tasks" | "schedules";
type TaskCenterTab = "active" | "history";

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
  { key: "schedules", label: "定时任务", icon: CalendarClock, description: "配置系统按 cron 或快捷定时自动执行有权限的流程" },
];

const taskCenterTabs: { key: TaskCenterTab; label: string; icon: LucideIcon }[] = [
  { key: "active", label: "我的待办", icon: UserRoundCheck },
  { key: "history", label: "任务记录", icon: History },
];

const activeTaskStateOptions = [
  { value: "all", label: "全部状态" },
  { value: "running", label: "运行中" },
  { value: "paused", label: "已暂停" },
  { value: "failed", label: "已失败" },
] as const;

type ActiveTaskStateFilter = (typeof activeTaskStateOptions)[number]["value"];

const taskSourceOptions = [
  { value: "all", label: "全部来源" },
  { value: "manual", label: "手工创建" },
  { value: "schedule", label: "定时创建" },
] as const;

type TaskSourceFilter = (typeof taskSourceOptions)[number]["value"];

const taskCenterSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const taskCenterSelectSuffixIcon = <ChevronDown className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />;



// 运行状态对应的 sys-status 变体；后端返回中文 stateLabel，前端只负责稳定映射。
function stateStatusClass(label: string): string {
  if (label === "运行中") {
    return "sys-status--running";
  }
  if (label === "已完成") {
    return "sys-status--success";
  }
  if (label === "已失败") {
    return "sys-status--failed";
  }
  if (label === "已暂停" || label.startsWith("等待")) {
    return "sys-status--paused";
  }
  return "sys-status--neutral";
}

function RunStateLabelBadge({ label }: { label: string }) {
  return (
    <span className={`sys-status ${stateStatusClass(label)}`}>
      <span className="sys-status-dot" aria-hidden="true" />
      {label}
    </span>
  );
}

const AVAILABLE_PAGE_SIZE = 12;
const TASK_RUN_PAGE_SIZE = 10;

export function WorkbenchShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const { runId } = useParams<{ runId?: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const themeMode = useAuthStore((s) => s.themeMode);
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const permissions = useAuthStore((s) => s.permissions);
  const tenantId = user?.tenantId ?? null;
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();

  const activeWorkbenchTab = useMemo<WorkbenchTab>(() => {
    if (location.pathname.startsWith(paths.workbench.create)) {
      return "create";
    }
    if (location.pathname.startsWith(paths.workbench.tasks)) {
      return "tasks";
    }
    if (location.pathname.startsWith(paths.workbench.schedules)) {
      return "schedules";
    }
    return "overview";
  }, [location.pathname]);

  const availablePage = parsePositiveInt(searchParams.get("page"), 1);
  const availableKeyword = searchParams.get("q") ?? "";
  const availableKeywordInput = searchParams.get("q") ?? "";
  const activeTasksPage = parsePositiveInt(searchParams.get("activePage"), 1);
  const taskRunsPage = parsePositiveInt(searchParams.get("historyPage"), 1);
  const taskCenterTab = searchParams.get("taskTab") === "history" ? "history" : "active";
  const activeTasksKeyword = searchParams.get("activeQ") ?? "";
  const historyKeyword = searchParams.get("historyQ") ?? "";
  const activeTasksStateFilter = parseActiveTaskStateFilter(searchParams.get("activeState"));
  const taskSourceFilter = parseTaskSourceFilter(searchParams.get("source"));

  // 业务工作台真实数据：概览统计、待办、最近运行均由 /api/tenants/{tenantId}/workbench/summary 返回。
  const [summary, setSummary] = useState<WorkbenchSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  // 可发起的已发布工作流来自后端分页查询，结合 keyword 在前端搜索。
  const [availableWorkflows, setAvailableWorkflows] = useState<WorkbenchAvailableWorkflowRow[]>([]);
  const [availableTotal, setAvailableTotal] = useState(0);
  const [availableLoading, setAvailableLoading] = useState(false);
  const [availableError, setAvailableError] = useState<string | null>(null);
  const [activeTasks, setActiveTasks] = useState<WorkbenchTaskRunRow[]>([]);
  const [activeTasksTotal, setActiveTasksTotal] = useState(0);
  const [activeTasksLoading, setActiveTasksLoading] = useState(false);
  const [activeTasksError, setActiveTasksError] = useState<string | null>(null);
  const [taskRuns, setTaskRuns] = useState<WorkbenchTaskRunRow[]>([]);
  const [taskRunsTotal, setTaskRunsTotal] = useState(0);
  const [taskRunsLoading, setTaskRunsLoading] = useState(false);
  const [taskRunsError, setTaskRunsError] = useState<string | null>(null);
  const [availableKeywordDraft, setAvailableKeywordDraft] = useState(availableKeyword);
  const [activeTasksKeywordDraft, setActiveTasksKeywordDraft] = useState(activeTasksKeyword);
  const [historyKeywordDraft, setHistoryKeywordDraft] = useState(historyKeyword);
  const [openedRunDetail, setOpenedRunDetail] = useState<WorkbenchRunDetail | null>(null);
  const [openedRunLoading, setOpenedRunLoading] = useState(false);
  const [creatingWorkflowId, setCreatingWorkflowId] = useState<string | null>(null);
  const [workflowDrawer, setWorkflowDrawer] = useState<WorkbenchAvailableWorkflowRow | null>(null);
  const hasWorkbenchPermission = user?.role !== "business" || permissions.includes("workbench");
  const hasSchedulePermission = user?.role !== "business" || permissions.includes("workbench_schedules");
  const visibleWorkbenchTabs = useMemo(() => workbenchTabs.filter((tab) => {
    if (tab.key === "schedules") {
      return hasSchedulePermission;
    }
    return hasWorkbenchPermission;
  }), [hasSchedulePermission, hasWorkbenchPermission]);
  const activeWorkbenchTabMeta = visibleWorkbenchTabs.find((tab) => tab.key === activeWorkbenchTab)
    ?? visibleWorkbenchTabs[0]
    ?? workbenchTabs[0];
  const moduleDescRef = useRef<HTMLDivElement>(null);
  useFlipText(moduleDescRef, activeWorkbenchTab);
  const { launchableWorkflows, blockedWorkflows } = useMemo(() => ({
    launchableWorkflows: availableWorkflows.filter((workflow) => workflow.canLaunch),
    blockedWorkflows: availableWorkflows.filter((workflow) => !workflow.canLaunch),
  }), [availableWorkflows]);
  const workbenchSegmentedOptions = visibleWorkbenchTabs.map((tab) => {
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
  const taskCenterSegmentedOptions = taskCenterTabs.map((tab) => {
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

  useEffect(() => {
    setAvailableKeywordDraft(availableKeyword);
  }, [availableKeyword]);

  useEffect(() => {
    setActiveTasksKeywordDraft(activeTasksKeyword);
  }, [activeTasksKeyword]);

  useEffect(() => {
    setHistoryKeywordDraft(historyKeyword);
  }, [historyKeyword]);

  useEffect(() => {
    if (runId || visibleWorkbenchTabs.length === 0 || visibleWorkbenchTabs.some((tab) => tab.key === activeWorkbenchTab)) {
      return;
    }
    navigateWorkbenchTab(visibleWorkbenchTabs[0].key);
    // activeWorkbenchTab 发生变化时把无权限页签导向第一个可见页签。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeWorkbenchTab, runId, visibleWorkbenchTabs]);

  // 批量更新查询参数，避免连续两次 setSearchParams 时后一次覆盖前一次导致筛选失效。
  function updateSearchParams(updates: Record<string, string | null>, replace?: boolean) {
    const shouldReplace = replace ?? Object.keys(updates).every(
      (key) => key !== "page" && key !== "activePage" && key !== "historyPage",
    );
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      Object.entries(updates).forEach(([key, value]) => {
        if (!value) {
          next.delete(key);
        } else {
          next.set(key, value);
        }
      });
      return next;
    }, { replace: shouldReplace });
  }

  function navigateWorkbenchTab(tab: WorkbenchTab) {
    if (tab === "overview") {
      navigate(paths.workbench.root);
      return;
    }
    if (tab === "create") {
      navigate(paths.workbench.create);
      return;
    }
    if (tab === "schedules") {
      navigate(paths.workbench.schedules);
      return;
    }
    navigate(paths.workbench.tasks);
  }

  function navigateTaskCenterTab(tab: TaskCenterTab) {
    updateSearchParams({ taskTab: tab === "active" ? null : tab });
  }

  function navigateTaskCenterWithTab(tab: TaskCenterTab, activeState?: string) {
    const next = new URLSearchParams();
    if (tab === "history") {
      next.set("taskTab", "history");
    }
    if (activeState) {
      next.set("activeState", activeState);
    }
    navigate({ pathname: paths.workbench.tasks, search: next.toString() ? `?${next.toString()}` : "" });
  }

  // 仅当业务工作台 surface 处于激活态、并且已有有效 tenantId / token 时，才发起概览请求。
  // 系统管理员入口没有 tenantId，业务工作台暂不为系统管理员渲染。
  const loadSummary = useCallback(async () => {
    if (!tenantId || !token || !hasWorkbenchPermission) {
      setSummary(null);
      setSummaryError(null);
      setSummaryLoading(false);
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
  }, [hasWorkbenchPermission, tenantId, token]);

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

  const loadActiveTasks = useCallback(async (page: number, keyword: string, state: ActiveTaskStateFilter, source: TaskSourceFilter) => {
    if (!tenantId || !token) {
      setActiveTasks([]);
      setActiveTasksTotal(0);
      return;
    }

    setActiveTasksLoading(true);
    setActiveTasksError(null);
    try {
      const data = await workbenchApi.listActiveRuns(
        tenantId,
        token,
        keyword,
        page,
        TASK_RUN_PAGE_SIZE,
        "updatedAt,desc",
        state === "all" ? "" : state,
        source === "all" ? "" : source,
      );
      setActiveTasks(data.items);
      setActiveTasksTotal(data.total);
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

  const loadTaskRuns = useCallback(async (page: number, keyword: string, source: TaskSourceFilter) => {
    if (!tenantId || !token) {
      setTaskRuns([]);
      setTaskRunsTotal(0);
      return;
    }

    setTaskRunsLoading(true);
    setTaskRunsError(null);
    try {
      const data = await workbenchApi.listRuns(
        tenantId,
        token,
        keyword,
        page,
        TASK_RUN_PAGE_SIZE,
        "updatedAt,desc",
        source === "all" ? "" : source,
      );
      setTaskRuns(data.items);
      setTaskRunsTotal(data.total);
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
    void loadSummary();
  }, [loadSummary]);

  useEffect(() => {
    if (activeWorkbenchTab !== "create") {
      return;
    }
    void loadAvailableWorkflows(availablePage, availableKeyword);
  }, [activeWorkbenchTab, availableKeyword, availablePage, loadAvailableWorkflows]);

  useEffect(() => {
    if (activeWorkbenchTab !== "tasks" || taskCenterTab !== "active") {
      return;
    }
    void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
  }, [activeWorkbenchTab, taskCenterTab, activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter, loadActiveTasks]);

  useEffect(() => {
    if (activeWorkbenchTab !== "tasks" || taskCenterTab !== "history") {
      return;
    }
    void loadTaskRuns(taskRunsPage, historyKeyword, taskSourceFilter);
  }, [activeWorkbenchTab, taskCenterTab, taskRunsPage, historyKeyword, taskSourceFilter, loadTaskRuns]);

  useEffect(() => {
    if (!runId || !tenantId || !token) {
      setOpenedRunDetail(null);
      return;
    }
    void handleOpenRun(runId);
    // runId 变化时重新加载运行详情
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, tenantId, token]);

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
      setWorkflowDrawer(null);
      messageApi.success(`已发起「${detail.title}」，请先保存后才会进入待办`);
      navigate(paths.workbench.run(detail.id));
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务创建失败";
      console.warn("[workbench] 任务创建失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    } finally {
      setCreatingWorkflowId(null);
    }
  }

  async function handleOpenRun(targetRunId: string) {
    if (!tenantId || !token) {
      return;
    }
    setOpenedRunLoading(true);
    try {
      const detail = await workbenchApi.getRun(tenantId, token, targetRunId);
      setOpenedRunDetail(detail);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "任务详情加载失败";
      console.warn("[workbench] 任务详情加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
      navigate(paths.workbench.tasks, { replace: true });
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
      void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
      void loadTaskRuns(taskRunsPage, historyKeyword, taskSourceFilter);
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
        void loadTaskRuns(1, historyKeyword, taskSourceFilter);
        updateSearchParams({ historyPage: "1", taskTab: "history" });
      } else {
        void loadActiveTasks(1, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
        updateSearchParams({ activePage: "1", taskTab: null });
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
          navigate(paths.workbench.tasks);
        }
      }
      messageApi.success("任务已删除");
      void loadSummary();
      void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
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
      void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "步骤回退失败";
      console.warn("[workbench] 步骤回退失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    }
  }

  async function handleBackFromRun() {
    if (!tenantId || !token || !openedRunDetail) {
      navigate(paths.workbench.tasks);
      return;
    }
    if (!openedRunDetail.saved) {
      try {
        await workbenchApi.deleteRun(tenantId, token, openedRunDetail.id);
      } catch (error) {
        console.warn("[workbench] 未保存任务清理失败", error);
      }
    }
    navigate(paths.workbench.tasks);
    void loadSummary();
    void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
    void loadTaskRuns(taskRunsPage, historyKeyword, taskSourceFilter);
  }

  function handleSubmitKeyword() {
    const trimmed = availableKeywordDraft.trim();
    updateSearchParams({ q: trimmed || null, page: "1" });
  }

  function handleSubmitTaskCenterSearch() {
    if (taskCenterTab === "active") {
      const trimmed = activeTasksKeywordDraft.trim();
      updateSearchParams({ activeQ: trimmed || null, activePage: "1" });
      return;
    }
    const trimmed = historyKeywordDraft.trim();
    updateSearchParams({ historyQ: trimmed || null, historyPage: "1" });
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
        label: "正在运行",
        value: metrics ? String(metrics.runningRunTotal) : "—",
        hint: "当前正在执行的实例",
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

  if (runId) {
    if (openedRunLoading || !openedRunDetail) {
      return (
        <>
          {messageContextHolder}
          <div className="flex min-h-[60vh] items-center justify-center">
            <Loader2 className="h-8 w-8 animate-spin text-[var(--color-primary)]" aria-hidden="true" />
          </div>
        </>
      );
    }

    return (
      <>
        {messageContextHolder}
        <div className="workbench-task-run-host flex flex-col h-[calc(100vh-var(--topbar-height,0px))] overflow-hidden">
          <div className="workbench-task-run-host-inner flex-1 overflow-hidden p-6">
            <TaskRunWorkspace
              run={openedRunDetail}
              tenantId={tenantId!}
              token={token || ""}
              onBack={() => void handleBackFromRun()}
              onSave={() => void handleSaveRun()}
              onDelete={() => void handleDeleteRun(openedRunDetail.id, true)}
              onReload={(updated) => {
                setOpenedRunDetail(updated);
                void loadSummary();
                void loadActiveTasks(activeTasksPage, activeTasksKeyword, activeTasksStateFilter, taskSourceFilter);
                void loadTaskRuns(taskRunsPage, historyKeyword, taskSourceFilter);
              }}
            />
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      {messageContextHolder}
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
              onChange={navigateWorkbenchTab}
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
            />
          </div>
          <div ref={moduleDescRef} className="login-portal-description login-portal-description--business">
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
                          {metricCards.map((metric, index) => (
                            <WorkbenchOverviewStat key={metric.label} icon={metric.icon} label={metric.label} value={metric.value} hint={metric.hint} tone={metric.tone} loading={summaryLoading} index={index} />
                          ))}
                        </section>

                        <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]" aria-label="业务工作台总览">
                          <section className="sys-preview-card">
                            <div className="sys-preview-card-title"><LayoutDashboard size={16} /> 工作台功能入口</div>
                            <div className="grid gap-3 lg:grid-cols-2">
                              <WorkbenchFeatureCard
                                icon={PlayCircle}
                                title="创建任务"
                                description="浏览全部已发布智能体流程，按版本和创建权限发起业务任务。"
                                meta={summary ? `${summary.metrics.availableWorkflowTotal} 个可发起流程` : "加载中..."}
                                onClick={() => navigate(paths.workbench.create)}
                                index={0}
                              />
                              <WorkbenchFeatureCard
                                icon={ListTodo}
                                title="我的待办"
                                description="处理已保存且未完成的任务，可继续推进、回退步骤或删除。"
                                meta={summary ? `${summary.metrics.pendingTodoTotal} 个待办` : "加载中..."}
                                onClick={() => navigateTaskCenterWithTab("active")}
                                index={1}
                              />
                              <WorkbenchFeatureCard
                                icon={History}
                                title="任务记录"
                                description="查看已完成任务与交付结果，仅支持只读查看。"
                                meta={summary ? `${recentRuns.length} 个最近完成` : "加载中..."}
                                onClick={() => navigateTaskCenterWithTab("history")}
                                index={2}
                              />
                              {hasSchedulePermission ? (
                                <WorkbenchFeatureCard
                                  icon={CalendarClock}
                                  title="定时任务"
                                  description="用 cron 或快捷定时自动执行已授权流程，并预置输入节点参数。"
                                  meta="按计划自动运行"
                                  onClick={() => navigate(paths.workbench.schedules)}
                                  index={3}
                                />
                              ) : null}
                            </div>
                          </section>

                          <aside className="sys-preview-card">
                            <div className="sys-preview-card-title"><History size={16} /> 最近完成</div>
                            {recentRuns.length === 0 ? (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已完成任务" />
                            ) : (
                              <div className="space-y-2">
                                {recentRuns.slice(0, 4).map((record, index) => (
                                  <RecentRunListItem key={record.id} record={record} onOpen={() => navigate(paths.workbench.run(record.id))} index={index} />
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
                                value={availableKeywordDraft}
                                onChange={(event) => setAvailableKeywordDraft(event.target.value)}
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
                                  {launchableWorkflows.map((workflow, index) => (
                                    <WorkflowLaunchCard key={workflow.id} workflow={workflow} onOpen={() => setWorkflowDrawer(workflow)} index={index} />
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
                                  {blockedWorkflows.map((workflow, index) => (
                                    <WorkflowLaunchCard key={workflow.id} workflow={workflow} restricted onOpen={() => setWorkflowDrawer(workflow)} index={index} />
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
                                onChange={(page) => updateSearchParams({ page: String(page) })}
                            />
                          </div>
                        ) : null}
                      </section>
                    ) : null}

                    {activeWorkbenchTab === "schedules" ? (
                      <WorkflowSchedulesPanel />
                    ) : null}

                    {activeWorkbenchTab === "tasks" ? (
                      <section className="workbench-task-center sys-fade-in" aria-label="任务中心">
                        <div className="workbench-task-center-head">
                          <div className="system-mgmt-segmented-scroll">
                            <Segmented<TaskCenterTab>
                              aria-label="任务中心视图"
                              value={taskCenterTab}
                              options={taskCenterSegmentedOptions}
                              onChange={navigateTaskCenterTab}
                              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
                            />
                          </div>
                        </div>

                        <TaskCenterFilterBar>
                          <div className="sys-field-input-wrap asset-filter-search workbench-task-center-search">
                            <Search size={18} className="sys-field-prefix" aria-hidden="true" />
                            <input
                              className="sys-field-input"
                              value={taskCenterTab === "active" ? activeTasksKeywordDraft : historyKeywordDraft}
                              onChange={(event) => {
                                if (taskCenterTab === "active") {
                                  setActiveTasksKeywordDraft(event.target.value);
                                  return;
                                }
                                setHistoryKeywordDraft(event.target.value);
                              }}
                              onKeyDown={(event) => {
                                if (event.key === "Enter") handleSubmitTaskCenterSearch();
                              }}
                              placeholder="按任务名称、编号或流程名称"
                              aria-label={taskCenterTab === "active" ? "搜索待办任务" : "搜索任务记录"}
                            />
                          </div>
                          {taskCenterTab === "active" ? (
                            <Select<ActiveTaskStateFilter>
                              className="agent-admin-select workbench-task-center-state-select"
                              classNames={taskCenterSelectClassNames}
                              prefix={<Activity className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                              suffixIcon={taskCenterSelectSuffixIcon}
                              value={activeTasksStateFilter}
                              options={activeTaskStateOptions.map((option) => ({ value: option.value, label: option.label }))}
                              onChange={(value) => {
                                updateSearchParams({
                                  activeState: value === "all" ? null : value,
                                  activePage: "1",
                                });
                              }}
                            />
                          ) : null}
                          <Select<TaskSourceFilter>
                            className="agent-admin-select workbench-task-center-state-select"
                            classNames={taskCenterSelectClassNames}
                            prefix={<CalendarClock className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                            suffixIcon={taskCenterSelectSuffixIcon}
                            value={taskSourceFilter}
                            options={taskSourceOptions.map((option) => ({ value: option.value, label: option.label }))}
                            onChange={(value) => {
                              updateSearchParams({
                                source: value === "all" ? null : value,
                                activePage: "1",
                                historyPage: "1",
                              });
                            }}
                          />
                          <button type="button" className="sys-btn sys-btn--default workbench-task-center-query-btn" onClick={() => handleSubmitTaskCenterSearch()}>
                            <Search size={18} aria-hidden="true" />
                            查询
                          </button>
                        </TaskCenterFilterBar>

                        {taskCenterTab === "active" ? (
                          activeTasksError ? (
                            <RuntimePlaceholder label="待办加载失败" hint={activeTasksError} />
                          ) : activeTasksLoading ? (
                            <div className="workflow-definition-empty-state">
                              <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                              <p>正在加载待办</p>
                            </div>
                          ) : activeTasks.length === 0 ? (
                            <TaskCenterEmptyState
                              icon={activeTasksKeyword || activeTasksStateFilter !== "all" || taskSourceFilter !== "all" ? Search : Inbox}
                              title={
                                activeTasksKeyword || activeTasksStateFilter !== "all" || taskSourceFilter !== "all"
                                  ? "当前暂无匹配的待办任务"
                                  : "暂无已保存的未完成任务"
                              }
                              hint={
                                activeTasksKeyword || activeTasksStateFilter !== "all" || taskSourceFilter !== "all"
                                  ? "可以调整搜索词、状态或来源筛选条件。"
                                  : "发起任务并保存后，会出现在这里继续处理。"
                              }
                            />
                          ) : (
                            <div className="workbench-task-center-list">
                              {activeTasks.map((record, index) => (
                                <ActiveTaskListItem
                                  key={record.id}
                                  record={record}
                                  onOpen={() => navigate(paths.workbench.run(record.id))}
                                  onDelete={() => void handleDeleteRun(record.id)}
                                  index={index}
                                />
                              ))}
                            </div>
                          )
                        ) : taskRunsError ? (
                          <RuntimePlaceholder label="任务记录加载失败" hint={taskRunsError} />
                        ) : taskRunsLoading ? (
                          <div className="workflow-definition-empty-state">
                            <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
                            <p>正在加载任务记录</p>
                          </div>
                        ) : taskRuns.length === 0 ? (
                          <TaskCenterEmptyState
                            icon={historyKeyword || taskSourceFilter !== "all" ? Search : History}
                            title={historyKeyword || taskSourceFilter !== "all" ? "当前暂无匹配的任务记录" : "暂无已完成任务记录"}
                            hint={historyKeyword || taskSourceFilter !== "all" ? "可以调整搜索词或来源筛选后重试。" : "任务全部节点完成后，会归档到这里只读查看。"}
                          />
                        ) : (
                          <div className="workbench-task-center-list">
                            {taskRuns.map((record, index) => (
                              <TaskRunListItem
                                key={record.id}
                                record={record}
                                onOpen={() => navigate(paths.workbench.run(record.id))}
                                index={index}
                              />
                            ))}
                          </div>
                        )}

                        {taskCenterTab === "active" && !activeTasksLoading && !activeTasksError ? (
                          <TaskCenterPagination
                            current={activeTasksPage}
                            total={activeTasksTotal}
                            pageSize={TASK_RUN_PAGE_SIZE}
                            onChange={(page) => updateSearchParams({ activePage: String(page) })}
                          />
                        ) : null}

                        {taskCenterTab === "history" && !taskRunsLoading && !taskRunsError ? (
                          <TaskCenterPagination
                            current={taskRunsPage}
                            total={taskRunsTotal}
                            pageSize={TASK_RUN_PAGE_SIZE}
                            onChange={(page) => updateSearchParams({ historyPage: String(page) })}
                          />
                        ) : null}
                      </section>
                    ) : null}

          </>
        )}
        <WorkflowLaunchDrawer
          workflow={workflowDrawer}
          rootClassName={drawerRootClassName}
          onClose={() => setWorkflowDrawer(null)}
          onLaunch={handleLaunchTask}
          launching={creatingWorkflowId === workflowDrawer?.id}
        />
      </SurfacePageLayout>
    </>
  );
}

function parseActiveTaskStateFilter(value: string | null): ActiveTaskStateFilter {
  if (value && activeTaskStateOptions.some((option) => option.value === value)) {
    return value as ActiveTaskStateFilter;
  }
  return "all";
}

function parseTaskSourceFilter(value: string | null): TaskSourceFilter {
  if (value && taskSourceOptions.some((option) => option.value === value)) {
    return value as TaskSourceFilter;
  }
  return "all";
}

function TaskCenterFilterBar({ children }: { children: ReactNode }) {
  return <div className="asset-filter-bar workbench-task-center-filter">{children}</div>;
}

function TaskCenterPagination({
  current,
  total,
  pageSize,
  onChange,
}: {
  current: number;
  total: number;
  pageSize: number;
  onChange: (page: number) => void;
}) {
  if (total <= 0) {
    return null;
  }
  return (
    <div className="agent-admin-pagination-wrap mt-4 px-0 py-4">
      <Pagination
        className="agent-admin-pagination"
        current={current}
        total={total}
        pageSize={pageSize}
        showSizeChanger={false}
        showTotal={(count, range) => `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`}
        onChange={onChange}
      />
    </div>
  );
}

function TaskCenterEmptyState({
  icon: Icon,
  title,
  hint,
}: {
  icon: LucideIcon;
  title: string;
  hint?: string;
}) {
  return (
    <div className="workflow-definition-empty-state">
      <Icon className="h-8 w-8 shrink-0" aria-hidden="true" />
      <p>{title}</p>
      {hint ? <span>{hint}</span> : null}
    </div>
  );
}

function WorkbenchOverviewStat({
  icon: Icon,
  label,
  value,
  hint,
  tone,
  loading,
  index,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
  hint?: string;
  tone: MetricTone;
  loading: boolean;
  index?: number;
}) {
  return (
    <div className="sys-overview-stat sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
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
  index,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  meta: string;
  onClick: () => void;
  index?: number;
}) {
  return (
    <button type="button" onClick={onClick} className="asset-feature-card sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
      <div className="flex items-center gap-2">
        <span className="asset-feature-card-icon sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <h3 className="asset-feature-card-title text-sm font-semibold">{title}</h3>
      </div>
      <p className="asset-feature-card-detail agent-muted mt-3 text-sm leading-6">{description}</p>
      <span className="asset-feature-card-meta">
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
  index,
}: {
  workflow: WorkbenchAvailableWorkflowRow;
  restricted?: boolean;
  onOpen: () => void;
  index?: number;
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
    <button type="button" onClick={onOpen} className="workflow-launch-card sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
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
      <div className="workbench-launch-drawer sys-drawer-section-enter">
        <div className="workbench-launch-drawer-content">
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
        </div>

        <div className="workbench-launch-drawer-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>
            <X size={14} />
            取消
          </button>
          <div className="sys-drawer-footer-right">
            <button type="button" className="sys-btn sys-btn--primary" onClick={() => onLaunch(workflow)} disabled={!workflow.canLaunch || launching}>
              {launching ? <Loader2 size={16} className="animate-spin" aria-hidden="true" /> : <PlayCircle size={16} aria-hidden="true" />}
              {workflow.canLaunch ? "发起任务" : "无权限发起"}
            </button>
          </div>
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
  index,
}: {
  record: WorkbenchTaskRunRow;
  onOpen: () => void;
  onDelete: () => void;
  index?: number;
}) {
  const Icon = record.state === "failed" ? AlertCircle : record.hasOpenTodo ? UserRoundCheck : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title} · {record.runNumber}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} · {record.currentNodeName} · {formatTriggerSourceLabel(record.triggerSource)}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · {record.progressPercent}% · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <TriggerSourceBadge triggerSource={record.triggerSource} />
        <RunStateLabelBadge label={record.stateLabel} />
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

function RecentRunListItem({ record, onOpen, index }: { record: WorkbenchRecentRunRow; onOpen?: () => void; index?: number }) {
  const Icon = record.state === "completed" || record.stateLabel === "已完成" ? CheckCircle2 : record.state === "paused" || record.stateLabel === "已暂停" ? PauseCircle : Activity;
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
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
        <RunStateLabelBadge label={record.stateLabel} />
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
  index,
}: {
  record: WorkbenchTaskRunRow;
  onOpen: () => void;
  index?: number;
}) {
  const updatedLabel = record.updatedAt ? new Date(record.updatedAt).toLocaleString("zh-CN", { hour12: false }) : "—";
  return (
    <div className="sys-preview-item sys-card-enter" style={index !== undefined ? { animationDelay: `${index * 40}ms` } : undefined}>
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <CheckCircle2 size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{record.title} · {record.runNumber}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{record.workflowName} · {record.currentNodeName} · {formatTriggerSourceLabel(record.triggerSource)}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{record.completedNodeCount}/{record.totalNodeCount} · {record.progressPercent}% · 更新于 {updatedLabel}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <TriggerSourceBadge triggerSource={record.triggerSource} />
        <RunStateLabelBadge label={record.stateLabel} />
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          查看
        </button>
      </div>
    </div>
  );
}

function TriggerSourceBadge({ triggerSource }: { triggerSource?: string }) {
  const scheduled = triggerSource === "schedule";
  return (
    <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${scheduled ? "bg-cyan-100 text-cyan-800 dark:bg-cyan-950/50 dark:text-cyan-300" : "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"}`}>
      {scheduled ? "定时创建" : "手工创建"}
    </span>
  );
}

function formatTriggerSourceLabel(triggerSource?: string) {
  return triggerSource === "schedule" ? "定时创建" : "手工创建";
}
