import { useEffect, useRef, useState } from "react";
import {
  Activity,
  ArrowRight,
  Archive,
  CheckCircle2,
  CircleAlert,
  ClipboardList,
  GitBranch,
  History,
  LayoutDashboard,
  Library,
  ListTodo,
  Lock,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  PauseCircle,
  PlayCircle,
  Settings,
  ShieldCheck,
  User,
  UserRoundCheck,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Segmented, message } from "antd";
import { TenantManagementPage } from "../admin/TenantManagementPage";
import { SystemManagementPage } from "../admin/SystemManagementPage";
import { AssetsPage } from "../assets/AssetsPage";
import { WorkflowDraftsPage } from "../designer/WorkflowDraftsPage";
import { ThemeToggle } from "../../components/ThemeToggle";
import { RoleSwitcher } from "../../components/RoleSwitcher";
import { AgentumMark } from "../../components/brand/AgentumMark";
import { useAuthStore } from "../../stores/authStore";

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

type Metric = {
  label: string;
  value: string;
  tone: "primary" | "success" | "info" | "cap";
  icon: LucideIcon;
};

type TodoItem = {
  title: string;
  workflow: string;
  owner: string;
  deadline: string;
  status: string;
  action: string;
};

type WorkflowTemplate = {
  title: string;
  description: string;
  nodes: string;
  tag: string;
  startLabel: string;
  agent: string;
  flow: string[];
  capabilityAssets: string[];
  canCreate: boolean;
  permissionScope: string;
  blockedReason?: string;
};

type RunRecord = {
  name: string;
  state: string;
  node: string;
  owner: string;
  updatedAt: string;
  progress: string;
};

type WorkbenchTabMeta = {
  key: WorkbenchTab;
  label: string;
  icon: LucideIcon;
  description: string;
};

// 工作台数据当前用于撑起业务信息层级，后续由待办、运行记录和资产统计 API 替换。
const metrics: Metric[] = [
  {
    label: "待处理事项",
    value: "8",
    tone: "primary",
    icon: UserRoundCheck,
  },
  {
    label: "今日运行",
    value: "24",
    tone: "info",
    icon: Activity,
  },
  {
    label: "已发布流程",
    value: "12",
    tone: "success",
    icon: GitBranch,
  },
  {
    label: "能力资产",
    value: "36",
    tone: "cap",
    icon: Library,
  },
];

// 待办项模拟了三类暂停点：用户输入、人工审核和交付确认，便于验证业务区不暴露设计态编排也能完成处理。
const todoItems: TodoItem[] = [
  {
    title: "确认合同风险分析结论",
    workflow: "合同审查与交付流程",
    owner: "法务组",
    deadline: "今天 18:00",
    status: "等待人工审核",
    action: "审核结论",
  },
  {
    title: "补充项目立项背景材料",
    workflow: "立项材料生成流程",
    owner: "项目经理",
    deadline: "明天 10:00",
    status: "等待用户输入",
    action: "补充资料",
  },
  {
    title: "复核月报交付邮件",
    workflow: "经营月报汇总流程",
    owner: "运营组",
    deadline: "明天 16:00",
    status: "等待交付确认",
    action: "确认交付",
  },
];

// 模板卡片先展示 MVP 场景，后续接入工作流模板库后应带上模板版本、运行入口和后端权限校验结果。
const workflowTemplates: WorkflowTemplate[] = [
  {
    title: "需求分析闭环",
    description: "输入需求材料，自动拆解要点，人工确认后生成评审文档。",
    nodes: "7 个节点",
    tag: "需求",
    startLabel: "发起需求分析",
    agent: "需求拆解智能体",
    flow: ["资料输入", "智能体追问", "人工确认", "文档交付"],
    capabilityAssets: ["需求分析 Skill", "评审文档模板", "Word 交付"],
    canCreate: true,
    permissionScope: "产品与项目组可创建",
  },
  {
    title: "合同审查交付",
    description: "识别风险条款，汇总修改建议，审核通过后生成交付记录。",
    nodes: "8 个节点",
    tag: "法务",
    startLabel: "发起合同审查",
    agent: "合同风险智能体",
    flow: ["合同上传", "条款识别", "法务审核", "交付归档"],
    capabilityAssets: ["合同审查 Skill", "条款抽取 MCP", "邮件交付"],
    canCreate: true,
    permissionScope: "法务与采购组可创建",
  },
  {
    title: "经营报告组装",
    description: "并行获取数据摘要，合并分析结论，输出报告草稿。",
    nodes: "9 个节点",
    tag: "经营",
    startLabel: "发起报告流程",
    agent: "经营分析智能体集群",
    flow: ["选择周期", "并行取数", "章节生成", "报告组装"],
    capabilityAssets: ["数据摘要 Skill", "经营数据库 MCP", "PDF 交付"],
    canCreate: false,
    permissionScope: "经营管理组可创建",
    blockedReason: "当前角色只能查看流程，未获得经营报告创建权限",
  },
  {
    title: "授信报告生成",
    description: "工商、司法、财务和行业数据并行核验，组装授信报告初稿。",
    nodes: "11 个节点",
    tag: "授信",
    startLabel: "发起授信报告",
    agent: "授信分析智能体集群",
    flow: ["企业名称输入", "外部数据核验", "章节并行生成", "报告审核"],
    capabilityAssets: ["风险识别 Skill", "工商司法 MCP", "报告组装模板"],
    canCreate: false,
    permissionScope: "风控部门可创建",
    blockedReason: "当前用户未被分配该智能体流程的创建范围",
  },
];

// 运行态摘要暂时展示最近运行位置，后续应来自 WorkflowRun 和 NodeRun 的聚合结果。
const runRecords: RunRecord[] = [
  {
    name: "客户续约风险评估",
    state: "运行中",
    node: "并行数据获取",
    owner: "客户成功组",
    updatedAt: "10 分钟前",
    progress: "4/7",
  },
  {
    name: "采购合同审查",
    state: "已暂停",
    node: "人工审核",
    owner: "采购部",
    updatedAt: "32 分钟前",
    progress: "5/8",
  },
  {
    name: "周报生成与发送",
    state: "已完成",
    node: "邮件交付",
    owner: "运营组",
    updatedAt: "昨天 17:40",
    progress: "7/7",
  },
  {
    name: "供应商准入评估",
    state: "已暂停",
    node: "补充资料",
    owner: "采购部",
    updatedAt: "今天 09:20",
    progress: "2/6",
  },
];

// 运行状态对应的颜色标记
const stateColors: Record<string, string> = {
  "运行中": "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300",
  "已暂停": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "已完成": "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  "等待人工审核": "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  "等待用户输入": "bg-indigo-100 text-indigo-800 dark:bg-indigo-900/40 dark:text-indigo-300",
  "等待交付确认": "bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300",
};

const workbenchTabs: WorkbenchTabMeta[] = [
  { key: "overview", label: "总览", icon: LayoutDashboard, description: "查看今日待办、可创建流程和运行态概况" },
  { key: "create", label: "创建任务", icon: PlayCircle, description: "浏览全部开放智能体流程，有权限的流程可创建任务" },
  { key: "tasks", label: "任务中心", icon: ListTodo, description: "合并查看待办、运行中、暂停和历史完成任务" },
];

export function WorkbenchShell() {
  // 菜单来自后端（通过 authStore.menus），不再前端硬编码 visibleFor。
  // 切换角色后后端返回新的 menus，前端自动更新导航。
  const menus = useAuthStore((s) => s.menus);
  const themeMode = useAuthStore((s) => s.themeMode);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
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

  useEffect(() => () => {
    if (sidebarTransitionTimer.current !== null) {
      window.clearTimeout(sidebarTransitionTimer.current);
    }
  }, []);

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

  function handleCreateTask(template: WorkflowTemplate) {
    if (!template.canCreate) {
      messageApi.warning(template.blockedReason ?? "当前账号暂无创建该任务的权限");
      return;
    }

    // 任务运行 API 尚未接入，当前只在前端保留创建动作入口，后续替换为创建 WorkflowRun 后进入业务运行详情。
    messageApi.success(`${template.title} 创建入口已就绪，后续将进入任务填写页`);
  }

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

              {activeWorkbenchTab === "overview" ? (
                <>
                  <section className="sys-overview-stats" aria-label="业务工作台概览">
                    {metrics.map((metric) => (
                      <WorkbenchOverviewStat key={metric.label} icon={metric.icon} label={metric.label} value={metric.value} tone={metric.tone} />
                    ))}
                  </section>

                  <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]" aria-label="业务工作台总览">
                    <section className="sys-preview-card">
                      <div className="sys-preview-card-title"><LayoutDashboard size={16} /> 工作台功能入口</div>
                      <div className="grid gap-3 md:grid-cols-2">
                        <WorkbenchFeatureCard
                          icon={PlayCircle}
                          title="创建任务"
                          description="查看全部开放智能体流程，按创建权限发起业务任务。"
                          meta={`${workflowTemplates.filter((template) => template.canCreate).length} 个可创建流程`}
                          onClick={() => setActiveWorkbenchTab("create")}
                        />
                        <WorkbenchFeatureCard
                          icon={ListTodo}
                          title="我的待办"
                          description="处理需要我补充资料、确认结果、人工审核或交付确认的暂停点。"
                          meta={`${todoItems.length} 个待办`}
                          onClick={() => setActiveWorkbenchTab("tasks")}
                        />
                        <WorkbenchFeatureCard
                          icon={PauseCircle}
                          title="暂停续办"
                          description="从正在进行和已暂停任务中恢复上下文，继续推进下一步。"
                          meta={`${runRecords.filter((record) => record.state !== "已完成").length} 个可继续任务`}
                          onClick={() => setActiveWorkbenchTab("tasks")}
                        />
                        <WorkbenchFeatureCard
                          icon={Archive}
                          title="历史完成"
                          description="查看已完成任务与交付结果，后续可进入运行详情追溯过程。"
                          meta={`${runRecords.filter((record) => record.state === "已完成").length} 个完成任务`}
                          onClick={() => setActiveWorkbenchTab("tasks")}
                        />
                      </div>
                    </section>

                    <aside className="sys-preview-card">
                      <div className="sys-preview-card-title"><History size={16} /> 最近任务</div>
                      <div className="space-y-2">
                        {runRecords.slice(0, 4).map((record) => (
                          <WorkbenchPreviewItem
                            key={record.name}
                            title={record.name}
                            description={`${record.node} · ${record.owner}`}
                            meta={`${record.progress} · ${record.updatedAt}`}
                            badge={record.state}
                            icon={record.state === "已完成" ? CheckCircle2 : record.state === "已暂停" ? PauseCircle : Activity}
                          />
                        ))}
                      </div>
                    </aside>
                  </section>
                </>
              ) : null}

              {activeWorkbenchTab === "create" ? (
                <section className="sys-preview-card" aria-labelledby="create-task-title">
                  <div id="create-task-title" className="sys-preview-card-title"><ClipboardList size={16} /> 开放智能体流程</div>
                  <div className="grid gap-3 md:grid-cols-2">
                    {workflowTemplates.map((template) => (
                      <WorkbenchFlowCard key={template.title} template={template} onCreate={() => handleCreateTask(template)} />
                    ))}
                  </div>
                </section>
              ) : null}

              {activeWorkbenchTab === "tasks" ? (
                <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]" aria-label="任务中心">
                  <section className="sys-preview-card">
                    <div className="sys-preview-card-title"><UserRoundCheck size={16} /> 我的待办</div>
                    <div className="space-y-2">
                      {todoItems.map((item) => (
                        <WorkbenchPreviewItem
                          key={item.title}
                          title={item.title}
                          description={`${item.workflow} · ${item.owner}`}
                          meta={`${item.action} · ${item.deadline}`}
                          badge={item.status}
                          icon={UserRoundCheck}
                          actionLabel="处理"
                        />
                      ))}
                    </div>
                  </section>

                  <section className="sys-preview-card">
                    <div className="sys-preview-card-title"><History size={16} /> 任务记录</div>
                    <div className="space-y-2">
                      {runRecords.map((record) => (
                        <WorkbenchPreviewItem
                          key={record.name}
                          title={record.name}
                          description={`${record.node} · ${record.owner}`}
                          meta={`${record.progress} · 更新于 ${record.updatedAt}`}
                          badge={record.state}
                          icon={record.state === "已完成" ? CheckCircle2 : record.state === "已暂停" ? PauseCircle : Activity}
                          actionLabel={record.state === "已完成" ? "查看" : "继续"}
                        />
                      ))}
                    </div>
                  </section>
                </section>
              ) : null}
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

function WorkbenchOverviewStat({ icon: Icon, label, value, tone }: { icon: LucideIcon; label: string; value: string; tone: "primary" | "success" | "info" | "cap" }) {
  return (
    <div className="sys-overview-stat">
      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${tone}`}>
        <Icon size={20} aria-hidden="true" />
      </div>
      <div>
        <div className="sys-overview-stat-value">{value}</div>
        <div className="sys-overview-stat-label">{label}</div>
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

function WorkbenchFlowCard({ template, onCreate }: { template: WorkflowTemplate; onCreate: () => void }) {
  return (
    <button type="button" onClick={onCreate} className="workflow-feature-card">
      <span className="workflow-feature-card-head">
        <span className="workflow-feature-card-icon">
          {template.canCreate ? <PlayCircle size={16} aria-hidden="true" /> : <Lock size={16} aria-hidden="true" />}
        </span>
        <span className="min-w-0">
          <span className="workflow-feature-card-title block">{template.title}</span>
          <span className="mt-1 block text-[11px] text-[var(--color-text-tertiary)]">{template.agent} · {template.nodes}</span>
        </span>
      </span>
      <span className="workflow-feature-card-description">{template.description}</span>
      <span className="mt-3 flex flex-wrap gap-1.5">
        {template.flow.map((step) => (
          <span key={step} className="rounded border border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-2 py-1 text-[11px] text-[var(--color-text-secondary)]">
            {step}
          </span>
        ))}
      </span>
      <span className="mt-3 flex flex-wrap gap-1.5">
        {template.capabilityAssets.map((asset) => (
          <span key={asset} className="rounded bg-sky-50 px-2 py-1 text-[11px] text-sky-700 dark:bg-sky-950/30 dark:text-sky-300">
            {asset}
          </span>
        ))}
      </span>
      {template.blockedReason ? (
        <span className="mt-3 flex items-center gap-1.5 text-xs text-[var(--color-warning)]">
          <CircleAlert className="h-3.5 w-3.5" aria-hidden="true" />
          {template.blockedReason}
        </span>
      ) : null}
      <span className="workflow-feature-card-meta">
        {template.canCreate ? template.startLabel : "无权限创建"}
        <ArrowRight size={14} aria-hidden="true" />
      </span>
    </button>
  );
}

function WorkbenchPreviewItem({
  title,
  description,
  meta,
  badge,
  icon: Icon,
  actionLabel,
}: {
  title: string;
  description: string;
  meta: string;
  badge: string;
  icon: LucideIcon;
  actionLabel?: string;
}) {
  return (
    <div className="sys-preview-item">
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <Icon size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{title}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{description}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{meta}</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${stateColors[badge] ?? "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
          {badge}
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
