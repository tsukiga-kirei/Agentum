import { useCallback, useEffect, useRef, useState } from "react";
import {
  Boxes,
  Building2,
  Check,
  ChevronDown,
  Clock,
  Code2,
  DatabaseZap,
  Edit,
  Globe,
  Hash,
  Info,
  KeyRound,
  LayoutDashboard,
  Mail,
  PlusCircle,
  PlayCircle,
  Save,
  ServerCog,
  ShieldCheck,
  ShieldAlert,
  Tag,
  Type,
  User,
  Users,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Segmented, Spin, message, Drawer, Pagination } from "antd";
import { AgentumApiError, organizationApi, systemApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { TenantOrganizationOverview } from "../../types/organization";
import type {
  CreateModelProviderRequest,
  CreateSystemCapabilityRequest,
  CreateTenantRequest,
  CreateTenantCapabilityGrantRequest,
  CapabilityTestResult,
  ModelProviderRow,
  ModelProviderTypeRow,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantRow,
  TenantCapabilityGrantRow,
  TenantModelAssignmentRow,
} from "../../types/system";

/** 自定义下拉选择器，替代原生 select，选项面板完全可控 */
function SysSelect({ options, value, defaultValue, placeholder, icon: Icon, onChange }: {
  options: { value: string; label: string }[];
  value?: string;
  defaultValue?: string;
  placeholder?: string;
  icon?: LucideIcon;
  onChange?: (val: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(value ?? defaultValue ?? "");
  const ref = useRef<HTMLDivElement>(null);

  // 点击外部关闭
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  // 受控模式同步
  useEffect(() => { if (value !== undefined) setSelected(value); }, [value]);

  const selectedLabel = options.find(o => o.value === selected)?.label;

  return (
    <div className="sys-select" ref={ref}>
      <div
        className={`sys-select-trigger ${Icon ? "sys-select-trigger--with-icon" : ""} ${open ? "sys-select-trigger--open" : ""} ${!selectedLabel && placeholder ? "sys-select-trigger--placeholder" : ""}`}
        onClick={() => setOpen(!open)}
      >
        {Icon ? <Icon size={16} className="sys-select-prefix" aria-hidden="true" /> : null}
        <span className="sys-select-value">{selectedLabel || placeholder || "请选择"}</span>
        <ChevronDown size={16} className="sys-select-arrow" />
      </div>
      {open && (
        <div className="sys-select-dropdown">
          {options.map(o => (
            <div
              key={o.value}
              className={`sys-select-option ${selected === o.value ? "sys-select-option--selected" : ""}`}
              onClick={() => { setSelected(o.value); onChange?.(o.value); setOpen(false); }}
            >
              {o.label}
              <Check size={14} className="sys-select-check" />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

type SystemSection = "overview" | "tenants" | "models" | "capabilities";

type AdminPageState = {
  page: number;
  size: number;
  total: number;
  totalPages: number;
};

const defaultPageState: AdminPageState = {
  page: 1,
  size: 10,
  total: 0,
  totalPages: 0,
};

const adminPaginationLocale = {
  items_per_page: "条/页",
  jump_to: "跳至",
  jump_to_confirm: "确定",
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
  prev_5: "向前 5 页",
  next_5: "向后 5 页",
  prev_3: "向前 3 页",
  next_3: "向后 3 页",
  page_size: "每页条数",
};

function formatPaginationTotal(count: number, range: [number, number], pageSize: number): string {
  return count <= pageSize ? `共 ${count} 条` : `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`;
}

const capabilityTypeOptions = [
  { value: "mcp", label: "MCP" },
  { value: "skill", label: "Skill" },
  { value: "prompt_template", label: "提示词模板" },
  { value: "delivery", label: "交付" },
];

function formatModelStatus(status: string): string {
  if (status === "active") return "可用";
  if (status === "draft") return "草稿";
  return status;
}

function formatRisk(level: string): string {
  if (level === "low") return "低";
  if (level === "medium") return "中";
  if (level === "high") return "高";
  return level;
}

function formatCapabilityType(t: string): string {
  const found = capabilityTypeOptions.find((o) => o.value === t);
  return found ? found.label : t;
}

function RiskTag({ level }: { level: string }) {
  const label = `${formatRisk(level)}风险`;
  const cls = level === "high" ? "sys-info-tag--danger" : level === "medium" ? "sys-info-tag--warn" : "sys-info-tag--success";
  return <span className={`sys-info-tag ${cls}`}>{label}</span>;
}

function AdminPagination({
  current,
  pageSize,
  total,
  onChange,
}: {
  current: number;
  pageSize: number;
  total: number;
  onChange: (page: number, size: number) => void;
}) {
  if (total <= 0) return null;

  return (
    <div className="agent-admin-pagination-wrap">
      <Pagination
        className="agent-admin-pagination"
        current={current}
        pageSize={pageSize}
        total={total}
        locale={adminPaginationLocale}
        showSizeChanger={{ className: "agent-admin-select", popupClassName: "agent-select-dropdown agent-admin-select-dropdown" }}
        pageSizeOptions={["10", "20", "50"]}
        showTotal={(count, range) => formatPaginationTotal(count, range, pageSize)}
        onChange={onChange}
        onShowSizeChange={onChange}
      />
    </div>
  );
}

// 系统管理：系统管理员默认入口
export function SystemManagementPage() {
  const token = useAuthStore((s) => s.token);
  const themeMode = useAuthStore((s) => s.themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();
  const drawerRootClassName = themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer";

  const [section, setSection] = useState<SystemSection>("overview");
  const [loading, setLoading] = useState(false);

  const [summary, setSummary] = useState<SystemSummary | null>(null);
  const [previewTenants, setPreviewTenants] = useState<SystemTenantRow[]>([]);
  const [previewCapabilities, setPreviewCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [tenants, setTenants] = useState<SystemTenantRow[]>([]);
  const [tenantPage, setTenantPage] = useState<AdminPageState>(defaultPageState);
  const [modelProviders, setModelProviders] = useState<ModelProviderRow[]>([]);
  const [modelProviderPage, setModelProviderPage] = useState<AdminPageState>(defaultPageState);
  const [modelProviderTypes, setModelProviderTypes] = useState<ModelProviderTypeRow[]>([]);
  const [capabilities, setCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [capabilityPage, setCapabilityPage] = useState<AdminPageState>(defaultPageState);
  const [configCapabilities, setConfigCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [capabilityTestResults, setCapabilityTestResults] = useState<Record<string, CapabilityTestResult>>({});

  // 租户侧边抽屉状态
  const [tenantDrawerOpen, setTenantDrawerOpen] = useState(false);
  const [selectedTenant, setSelectedTenant] = useState<SystemTenantRow | null>(null);
  const [tenantActiveTab, setTenantActiveTab] = useState("default");
  const [tenantCapabilityGrants, setTenantCapabilityGrants] = useState<TenantCapabilityGrantRow[]>([]);
  const [tenantModelAssignments, setTenantModelAssignments] = useState<TenantModelAssignmentRow[]>([]);
  const [tenantOrganizationOverview, setTenantOrganizationOverview] = useState<TenantOrganizationOverview | null>(null);
  const [tenantOrganizationLoading, setTenantOrganizationLoading] = useState(false);
  const [tenantOrganizationError, setTenantOrganizationError] = useState("");

  // 新增租户 Modal（不使用 Ant Form，改用原生 ref）
  const [createTenantModalOpen, setCreateTenantModalOpen] = useState(false);
  const [createTenantTab, setCreateTenantTab] = useState<"basic"|"admin">("basic");
  const ctRef = useRef<Record<string,string>>({});

  // 模型与能力 Modal
  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [capModalOpen, setCapModalOpen] = useState(false);
  const [selectedModelProviderType, setSelectedModelProviderType] = useState("");
  const [selectedCapabilityType, setSelectedCapabilityType] = useState("mcp");
  const [selectedMcpTransport, setSelectedMcpTransport] = useState("stdio");
  const modelRef = useRef<Record<string,string>>({});
  const capRef = useRef<Record<string,string>>({});

  const handleApiError = useCallback(
    (err: unknown, fallback: string) => {
      if (err instanceof AgentumApiError) {
        messageApi.error(err.message);
        return;
      }
      messageApi.error(fallback);
    },
    [messageApi],
  );

  const loadSummary = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await systemApi.summary(token);
      setSummary(data);
      const [tenantPreview, capabilityPreview] = await Promise.all([
        systemApi.listTenants(token, 1, 4),
        systemApi.listCapabilities(token, 1, 4),
      ]);
      setPreviewTenants(tenantPreview.items);
      setPreviewCapabilities(capabilityPreview.items);
    } catch (e) {
      handleApiError(e, "加载系统概览失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError]);

  const loadTenants = useCallback(async (page = tenantPage.page, size = tenantPage.size) => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await systemApi.listTenants(token, page, size);
      setTenants(data.items);
      setTenantPage({ page: data.page, size: data.size, total: data.total, totalPages: data.totalPages });
    } catch (e) {
      handleApiError(e, "加载租户列表失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError, tenantPage.page, tenantPage.size]);

  const loadModels = useCallback(async (page = modelProviderPage.page, size = modelProviderPage.size) => {
    if (!token) return;
    setLoading(true);
    try {
      const [providersPage, types] = await Promise.all([
        systemApi.listModelProviders(token, page, size),
        systemApi.listModelProviderTypes(token),
      ]);
      setModelProviders(providersPage.items);
      setModelProviderPage({ page: providersPage.page, size: providersPage.size, total: providersPage.total, totalPages: providersPage.totalPages });
      setModelProviderTypes(types);
    } catch (e) {
      handleApiError(e, "加载模型供应商失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError, modelProviderPage.page, modelProviderPage.size]);

  const loadCapabilities = useCallback(async (page = capabilityPage.page, size = capabilityPage.size) => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await systemApi.listCapabilities(token, page, size);
      setCapabilities(data.items);
      setCapabilityPage({ page: data.page, size: data.size, total: data.total, totalPages: data.totalPages });
    } catch (e) {
      handleApiError(e, "加载系统能力失败");
    } finally {
      setLoading(false);
    }
  }, [token, handleApiError, capabilityPage.page, capabilityPage.size]);

  const loadConfigCapabilities = useCallback(async () => {
    if (!token) return;
    try {
      // 租户抽屉用于配置可用能力，需要尽量拿到当前阶段的完整能力集合；size 仍受后端统一上限保护。
      const data = await systemApi.listCapabilities(token, 1, 100, "name,asc");
      setConfigCapabilities(data.items);
    } catch (e) {
      handleApiError(e, "加载租户能力配置项失败");
    }
  }, [token, handleApiError]);

  const loadTenantCapabilityGrants = useCallback(async (tenantId: string) => {
    if (!token) return;
    try {
      setTenantCapabilityGrants(await systemApi.listGrants(token, tenantId));
    } catch (e) {
      handleApiError(e, "加载租户能力配置失败");
    }
  }, [token, handleApiError]);

  const loadTenantModelAssignments = useCallback(async (tenantId: string) => {
    if (!token) return;
    try {
      setTenantModelAssignments(await systemApi.listTenantModelAssignments(token, tenantId));
    } catch (e) {
      handleApiError(e, "加载租户模型分配失败");
    }
  }, [token, handleApiError]);

  const loadTenantOrganizationOverview = useCallback(async (tenantId: string) => {
    if (!token) return;
    setTenantOrganizationLoading(true);
    setTenantOrganizationError("");
    try {
      setTenantOrganizationOverview(await organizationApi.overview(tenantId, token));
    } catch (e) {
      if (e instanceof AgentumApiError) {
        setTenantOrganizationError(e.message);
      } else {
        setTenantOrganizationError("加载租户成员与角色失败");
      }
      setTenantOrganizationOverview(null);
    } finally {
      setTenantOrganizationLoading(false);
    }
  }, [token]);

  useEffect(() => {
    if (!token) return;
    if (section === "overview") {
      void loadSummary();
    }
    else if (section === "tenants") void loadTenants();
    else if (section === "models") void loadModels();
    else if (section === "capabilities") void loadCapabilities();
  }, [section, token, loadSummary, loadTenants, loadModels, loadCapabilities]);

  useEffect(() => {
    if (!selectedTenant || !tenantDrawerOpen) return;
    if (tenantActiveTab === "capabilities") {
      void loadConfigCapabilities();
      void loadTenantCapabilityGrants(selectedTenant.id);
    }
    if (tenantActiveTab === "models") {
      void loadModels();
      void loadTenantModelAssignments(selectedTenant.id);
    }
    if (tenantActiveTab === "members") {
      // 系统管理抽屉只做跨租户诊断视图，实际成员维护仍回到租户管理页并由后端复核租户上下文。
      void loadTenantOrganizationOverview(selectedTenant.id);
    }
  }, [selectedTenant, tenantDrawerOpen, tenantActiveTab, loadConfigCapabilities, loadTenantCapabilityGrants, loadModels, loadTenantModelAssignments, loadTenantOrganizationOverview]);

  const patchTenantStatus = async (tenantId: string, status: string) => {
    if (!token) return;
    try {
      await systemApi.updateTenantStatus(tenantId, token, { status });
      messageApi.success("租户状态已更新");
      void loadTenants();
      void loadSummary();
    } catch (e) {
      handleApiError(e, "更新租户状态失败");
    }
  };

  const submitCreateTenant = async () => {
    if (!token) return;
    const d = ctRef.current;
    if (!d.name?.trim()) { setCreateTenantTab("basic"); messageApi.warning("请输入租户名称"); return; }
    if (!d.code?.trim()) { setCreateTenantTab("basic"); messageApi.warning("请输入租户编码"); return; }
    if (!d.admin_username?.trim()) { setCreateTenantTab("admin"); messageApi.warning("请输入管理员账号"); return; }
    if (!d.admin_displayName?.trim()) { setCreateTenantTab("admin"); messageApi.warning("请输入管理员姓名"); return; }
    if (!d.admin_password?.trim()) { setCreateTenantTab("admin"); messageApi.warning("请输入初始密码"); return; }
    try {
      await systemApi.createTenant(token, {
        name: d.name.trim(),
        code: d.code.trim(),
        adminUsername: d.admin_username.trim(),
        adminDisplayName: d.admin_displayName.trim(),
        adminPassword: d.admin_password,
        adminEmail: d.admin_email?.trim() || undefined,
      } as CreateTenantRequest);
      messageApi.success("已创建新租户及管理员");
      setCreateTenantModalOpen(false);
      ctRef.current = {};
      void loadTenants();
      void loadSummary();
    } catch (e) {
      handleApiError(e, "创建租户失败");
    }
  };

  const submitModel = async () => {
    if (!token) return;
    const d = modelRef.current;
    if (!d.name?.trim()) { messageApi.warning("请输入名称"); return; }
    if (!d.providerType?.trim()) { messageApi.warning("请选择供应商类型"); return; }
    try {
      await systemApi.createModelProvider(token, {
        name: d.name, providerType: d.providerType,
        baseUrl: d.baseUrl || undefined, defaultModel: d.defaultModel || undefined,
        status: d.status || "draft",
      } as CreateModelProviderRequest);
      messageApi.success("已注册模型供应商");
      setModelModalOpen(false);
      modelRef.current = {};
      void loadModels();
      void loadSummary();
    } catch (e) {
      handleApiError(e, "注册模型供应商失败");
    }
  };

  const submitCapability = async () => {
    if (!token) return;
    const d = capRef.current;
    if (!d.capabilityType) { messageApi.warning("请选择能力类型"); return; }
    if (!d.name?.trim()) { messageApi.warning("请输入名称"); return; }
    if (!d.code?.trim()) { messageApi.warning("请输入编码"); return; }
    const config: Record<string, unknown> = {};
    if (d.capabilityType === "mcp") {
      config.transport = d.transport || "stdio";
      config.command = d.command || "";
      config.args = d.args || "";
      config.workingDir = d.workingDir || "";
      config.sseUrl = d.sseUrl || "";
    } else if (d.capabilityType === "delivery") {
      config.deliveryChannel = d.deliveryChannel || "";
      config.target = d.target || "";
    } else {
      config.sourcePath = d.sourcePath || "";
      config.manifestPath = d.manifestPath || "";
    }
    try {
      await systemApi.createCapability(token, {
        capabilityType: d.capabilityType, name: d.name, code: d.code,
        version: d.version || "v1", riskLevel: d.riskLevel || "low",
        status: d.status || "draft",
        config,
      } as CreateSystemCapabilityRequest);
      messageApi.success("已注册系统能力");
      setCapModalOpen(false);
      capRef.current = {};
      void loadCapabilities();
      void loadSummary();
    } catch (e) {
      handleApiError(e, "注册系统能力失败");
    }
  };

  const testCapabilityConnection = async (capId: string) => {
    if (!token) return;
    messageApi.loading({ content: "正在测试连通性...", key: `test_conn_${capId}` });
    try {
      const result = await systemApi.testCapability(token, capId);
      setCapabilityTestResults((prev) => ({ ...prev, [capId]: result }));
      const content = result.status === "success" ? result.summary : `测试未通过：${result.summary}`;
      if (result.status === "success") messageApi.success({ content, key: `test_conn_${capId}` });
      else messageApi.warning({ content, key: `test_conn_${capId}` });
    } catch (e) {
      handleApiError(e, "能力测试失败");
    }
  };

  const grantCapabilityToTenant = async (capabilityId: string) => {
    if (!token || !selectedTenant) return;
    try {
      await systemApi.createGrant(token, { tenantId: selectedTenant.id, capabilityId, status: "enabled" } as CreateTenantCapabilityGrantRequest);
      messageApi.success("已启用租户能力");
      void loadTenantCapabilityGrants(selectedTenant.id);
      void loadSummary();
    } catch (e) {
      handleApiError(e, "启用租户能力失败");
    }
  };

  const updateTenantCapabilityGrant = async (grantId: string, status: "enabled" | "disabled") => {
    if (!token || !selectedTenant) return;
    try {
      await systemApi.updateGrantStatus(token, grantId, { status });
      messageApi.success(status === "enabled" ? "已启用租户能力" : "已取消启用");
      void loadTenantCapabilityGrants(selectedTenant.id);
      void loadSummary();
    } catch (e) {
      handleApiError(e, status === "enabled" ? "启用租户能力失败" : "取消启用失败");
    }
  };

  const assignModelToTenant = async (provider: ModelProviderRow) => {
    if (!token || !selectedTenant) return;
    try {
      await systemApi.createTenantModelAssignment(token, {
        tenantId: selectedTenant.id,
        providerId: provider.id,
        defaultModel: provider.defaultModel ?? undefined,
        status: "enabled",
      });
      messageApi.success("已完成租户模型分配");
      void loadTenantModelAssignments(selectedTenant.id);
    } catch (e) {
      handleApiError(e, "分配租户模型失败");
    }
  };

  const navItems: { key: SystemSection; label: string; icon: typeof LayoutDashboard; description: string }[] = [
    { key: "overview", label: "平台概览", icon: LayoutDashboard, description: "全局统计与治理概况" },
    { key: "tenants", label: "租户管理", icon: Building2, description: "隔离边界与全局分配" },
    { key: "models", label: "模型供应商", icon: DatabaseZap, description: "底层算力与路由配置" },
    { key: "capabilities", label: "全局能力", icon: Boxes, description: "系统级插件与通道" },
  ];

  const activeNav = navItems.find((n) => n.key === section) ?? navItems[0];

  const moduleSegmentedOptions = navItems.map((item) => {
    const Icon = item.icon;
    return {
      value: item.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden />
          <span>{item.label}</span>
        </span>
      ),
    };
  });

  return (
    <>
      {messageContextHolder}
      <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
        <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
          {/* 页头 */}
          <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
            <div className="flex min-w-0 gap-4">
              <div className="system-mgmt-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
                <ServerCog className="h-6 w-6" aria-hidden />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">系统管理</h1>
                  <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                    平台底座
                  </span>
                </div>
                <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                  作为系统管理员，您可在此管理多租户生命周期、全局模型算力及底层基础能力。
                </p>
              </div>
            </div>
          </header>

          <div className="system-mgmt-module-switch mb-5">
            <div className="system-mgmt-segmented-scroll">
              <Segmented<SystemSection>
                aria-label="系统管理模块"
                value={section}
                onChange={(key) => setSection(key as SystemSection)}
                options={moduleSegmentedOptions}
                className="login-portal-segmented login-portal-segmented--system_admin system-mgmt-segmented"
              />
            </div>
            <div className="login-portal-description login-portal-description--system_admin">
              <span className="login-portal-description-dot" />
              {activeNav.description}
            </div>
          </div>

          <Spin spinning={loading}>
            {/* ===== 平台概览 ===== */}
            {section === "overview" && (
              <div className="sys-fade-in">
                <div className="sys-overview-stats">
                  {[
                    { icon: Building2, val: summary?.tenantTotal, label: "总租户数", cls: "primary" },
                    { icon: Users, val: summary?.tenantActive, label: "活跃租户", cls: "success" },
                    { icon: DatabaseZap, val: summary?.modelProviderTotal, label: "模型供应商", cls: "info" },
                    { icon: Boxes, val: summary?.systemCapabilityTotal, label: "已注册能力", cls: "cap" },
                  ].map((s) => { const I = s.icon; return (
                    <div key={s.label} className="sys-overview-stat">
                      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${s.cls}`}><I size={20} /></div>
                      <div><div className="sys-overview-stat-value">{s.val ?? "—"}</div><div className="sys-overview-stat-label">{s.label}</div></div>
                    </div>
                  );})}
                </div>
                <div style={{display:"grid",gridTemplateColumns:"repeat(2,1fr)",gap:20}}>
                  <div className="sys-preview-card">
                    <div className="sys-preview-card-title"><Building2 size={16}/> 最近入驻租户</div>
                    {previewTenants.length === 0 ? <Empty description="暂无租户" image={Empty.PRESENTED_IMAGE_SIMPLE}/> : <>
                      {previewTenants.map(t=>(
                        <div key={t.id} className="sys-preview-item" style={{cursor:"pointer"}} onClick={()=>{setSection("tenants");setSelectedTenant(t);setTenantDrawerOpen(true);}}>
                          <div className="sys-preview-item-left">
                            <div className="sys-preview-item-icon sys-card-avatar--tenant"><Building2 size={16}/></div>
                            <div><div className="sys-preview-item-name">{t.name}</div><div className="sys-preview-item-sub">{t.code}</div></div>
                          </div>
                          <div className={`sys-status sys-status--${t.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{t.status==='active'?'运行中':'已停用'}</div>
                        </div>
                      ))}
                      {(summary?.tenantTotal ?? 0)>4&&<button className="sys-btn sys-btn--link" onClick={()=>setSection("tenants")} style={{marginTop:8}}>查看全部 {summary?.tenantTotal ?? 0} 个租户 →</button>}
                    </>}
                  </div>
                  <div className="sys-preview-card">
                    <div className="sys-preview-card-title"><Boxes size={16}/> 核心能力一览</div>
                    {previewCapabilities.length === 0 ? <Empty description="暂无能力" image={Empty.PRESENTED_IMAGE_SIMPLE}/> : <>
                      {previewCapabilities.map(c=>(
                        <div key={c.id} className="sys-preview-item">
                          <div className="sys-preview-item-left">
                            <div className="sys-preview-item-icon sys-card-avatar--cap"><Boxes size={16}/></div>
                            <div><div className="sys-preview-item-name">{c.name}</div><div className="sys-preview-item-sub">{formatCapabilityType(c.capabilityType)} · {c.version}</div></div>
                          </div>
                          <RiskTag level={c.riskLevel}/>
                        </div>
                      ))}
                      {(summary?.systemCapabilityTotal ?? 0)>4&&<button className="sys-btn sys-btn--link" onClick={()=>setSection("capabilities")} style={{marginTop:8}}>查看全部 {summary?.systemCapabilityTotal ?? 0} 项能力 →</button>}
                    </>}
                  </div>
                </div>
              </div>
            )}

            {/* ===== 租户管理 ===== */}
            {section === "tenants" && (
              <div className="sys-fade-in">
                <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:20}}>
                  <p style={{fontSize:14,color:"var(--color-text-tertiary)",margin:0}}>管理多租户生命周期，点击卡片查看详细配置</p>
                  <button className="sys-btn sys-btn--primary" onClick={()=>setCreateTenantModalOpen(true)}><PlusCircle size={15}/> 新增租户</button>
                </div>
                {tenants.length===0?<Empty description="暂无租户记录" style={{marginTop:48}}/>:(
                  <>
                    <div className="sys-card-grid">
                      {tenants.map(t=>(
                        <div key={t.id} className="sys-card" onClick={()=>{setSelectedTenant(t);setTenantActiveTab("default");setTenantDrawerOpen(true);}}>
                        <div className="sys-card-header">
                          <div className="sys-card-avatar sys-card-avatar--tenant"><Building2 size={22}/></div>
                          <div className="sys-card-info"><div className="sys-card-name">{t.name}</div><div className="sys-card-code">{t.code}</div></div>
                          <div className={`sys-status sys-status--${t.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{t.status==='active'?'运行中':'已停用'}</div>
                        </div>
                        <div className="sys-card-stats">
                          <div className="sys-stat-item"><span className="sys-stat-label">状态</span><span className="sys-stat-value">{t.status==='active'?'正常':'暂停'}</span></div>
                        </div>
                        <div className="sys-card-footer">
                          <span className="sys-card-footer-time"><Clock size={12}/> 点击查看配置</span>
                          <div className="sys-card-footer-actions" onClick={e=>e.stopPropagation()}>
                            <button className="sys-btn sys-btn--text sys-btn--sm" onClick={()=>{setSelectedTenant(t);setTenantActiveTab("default");setTenantDrawerOpen(true);}}><Edit size={14}/> 配置</button>
                          </div>
                        </div>
                        </div>
                      ))}
                    </div>
                    <AdminPagination
                      current={tenantPage.page}
                      pageSize={tenantPage.size}
                      total={tenantPage.total}
                      onChange={(page, size) => void loadTenants(page, size)}
                    />
                  </>
                )}
              </div>
            )}

            {/* ===== 模型供应商 ===== */}
            {section === "models" && (
              <div className="sys-fade-in">
                <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:20}}>
                  <p style={{fontSize:14,color:"var(--color-text-tertiary)",margin:0}}>注册和管理底层算力供应商及默认模型路由</p>
                  <button className="sys-btn sys-btn--primary" onClick={()=>{modelRef.current={};setSelectedModelProviderType("");void loadModels();setModelModalOpen(true);}}><PlusCircle size={15}/> 注册供应商</button>
                </div>
                {modelProviders.length===0?<Empty description="暂无模型供应商" style={{marginTop:48}}/>:(
                  <>
                    <div className="sys-card-grid">
                      {modelProviders.map(m=>(
                      <div key={m.id} className="sys-card sys-card--static">
                        <div className="sys-card-header">
                          <div className="sys-card-avatar sys-card-avatar--model"><DatabaseZap size={22}/></div>
                          <div className="sys-card-info"><div className="sys-card-name">{m.name}</div><div className="sys-card-code">{m.providerType}</div></div>
                          <div className={`sys-status sys-status--${m.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{formatModelStatus(m.status)}</div>
                        </div>
                        <div className="sys-card-meta">
                          <div className="sys-meta-item"><span className="sys-meta-label">基址</span><span className="sys-meta-value">{m.baseUrl||"未配置"}</span></div>
                          <div className="sys-meta-item"><span className="sys-meta-label">默认模型</span><span className="sys-meta-value">{m.defaultModel||"未配置"}</span></div>
                        </div>
                      </div>
                      ))}
                    </div>
                    <AdminPagination
                      current={modelProviderPage.page}
                      pageSize={modelProviderPage.size}
                      total={modelProviderPage.total}
                      onChange={(page, size) => void loadModels(page, size)}
                    />
                  </>
                )}
              </div>
            )}

            {/* ===== 全局能力 ===== */}
            {section === "capabilities" && (
              <div className="sys-fade-in">
                <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:20}}>
                  <p style={{fontSize:14,color:"var(--color-text-tertiary)",margin:0}}>管理系统级 MCP、Skill、提示词模板和交付能力</p>
                  <button className="sys-btn sys-btn--primary" onClick={()=>{capRef.current={capabilityType:"mcp",transport:"stdio"};setSelectedCapabilityType("mcp");setSelectedMcpTransport("stdio");setCapModalOpen(true);}}><PlusCircle size={15}/> 注册能力</button>
                </div>
                {capabilities.length===0?<Empty description="暂无全局能力" style={{marginTop:48}}/>:(
                  <>
                    <div className="sys-card-grid">
                      {capabilities.map(c=>(
                      <div key={c.id} className="sys-card sys-card--static">
                        <div className="sys-card-header">
                          <div className="sys-card-avatar sys-card-avatar--cap"><Boxes size={22}/></div>
                          <div className="sys-card-info"><div className="sys-card-name">{c.name}</div><div className="sys-card-code">{c.code}</div></div>
                          <div className={`sys-status sys-status--${c.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{c.status==='active'?'启用':'草稿'}</div>
                        </div>
                        <div className="sys-info-tags">
                          <span className="sys-info-tag sys-info-tag--primary">{formatCapabilityType(c.capabilityType)}</span>
                          <span className="sys-info-tag sys-info-tag--info">版本 {c.version}</span>
                          <RiskTag level={c.riskLevel}/>
                        </div>
                        <div className="sys-card-footer">
                          <span className="sys-card-footer-time"><ShieldCheck size={12}/> {c.code}</span>
                          <div className="sys-card-footer-actions">
                            <button className="sys-btn sys-btn--default sys-btn--sm" onClick={()=>testCapabilityConnection(c.id)}><PlayCircle size={14}/> 测试连通性</button>
                          </div>
                        </div>
                        {capabilityTestResults[c.id] && (
                          <div className="sys-hint" style={{marginTop:12}}>
                            <Info size={14}/>
                            <span>{capabilityTestResults[c.id].summary}</span>
                          </div>
                        )}
                        {capabilityTestResults[c.id]?.tools.length ? (
                          <div className="sys-info-tags" style={{marginTop:8}}>
                            {capabilityTestResults[c.id].tools.map((tool) => (
                              <span key={tool.name} className="sys-info-tag sys-info-tag--info">{tool.name}</span>
                            ))}
                          </div>
                        ) : null}
                      </div>
                      ))}
                    </div>
                    <AdminPagination
                      current={capabilityPage.page}
                      pageSize={capabilityPage.size}
                      total={capabilityPage.total}
                      onChange={(page, size) => void loadCapabilities(page, size)}
                    />
                  </>
                )}
              </div>
            )}
          </Spin>
        </div>
      </div>

      {/* 租户详情侧拉抽屉（AuraOA 风格页签 + 表单行） */}
      <Drawer
        title={selectedTenant?.name ? `${selectedTenant.name} — 配置管理` : "租户配置管理"}
        placement="right"
        width={720}
        onClose={() => setTenantDrawerOpen(false)}
        open={tenantDrawerOpen}
        rootClassName={drawerRootClassName}
      >
        {selectedTenant && (<>
          {/* 自定义页签栏 */}
          <div className="sys-drawer-tabs">
            {[
              { key: "default", label: "基本信息", icon: Info },
              { key: "capabilities", label: "能力配置", icon: Boxes },
              { key: "members", label: "成员 / 角色", icon: Users },
              { key: "models", label: "模型分配", icon: DatabaseZap },
            ].map(tab => {
              const TabIcon = tab.icon;
              return (
                <button key={tab.key} className={`sys-drawer-tab ${tenantActiveTab === tab.key ? "sys-drawer-tab--active" : ""}`} onClick={() => setTenantActiveTab(tab.key)}>
                  <TabIcon size={14} /> {tab.label}
                </button>
              );
            })}
          </div>

          {/* 基本信息 */}
          {tenantActiveTab === "default" && (
            <div className="sys-drawer-section">
              <div className="sys-section-header"><Building2 size={18}/> 基本信息</div>
              <div className="sys-config-group">
                <div className="sys-form-row"><span className="sys-form-label">租户名称</span><span className="sys-form-value">{selectedTenant.name}</span></div>
                <div className="sys-form-row"><span className="sys-form-label">租户编码</span><span className="sys-form-value" style={{fontFamily:"var(--font-mono, monospace)"}}>{selectedTenant.code}</span></div>
                <div className="sys-form-row">
                  <span className="sys-form-label">运行状态</span>
                  <div style={{display:"flex",alignItems:"center",gap:12}}>
                    <div className={`sys-status sys-status--${selectedTenant.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{selectedTenant.status==='active'?'运行中':'已停用'}</div>
                    <button className={`sys-btn sys-btn--sm ${selectedTenant.status==='active'?'sys-btn--danger':'sys-btn--default'}`} onClick={()=>{const ns=selectedTenant.status==='active'?'suspended':'active';void patchTenantStatus(selectedTenant.id,ns);setSelectedTenant({...selectedTenant,status:ns});}}>
                      {selectedTenant.status==='active'?'停用租户':'启用租户'}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* 能力配置 */}
          {tenantActiveTab === "capabilities" && (
            <div className="sys-drawer-section">
              <div className="sys-section-header"><Boxes size={18}/> 全局能力配置</div>
              <div className="sys-hint"><Info size={14}/> 这里配置该租户能使用哪些全局能力；租户管理员后续再按用户、部门和租户自定义角色细分到业务侧模块、页签与动作。</div>
              {configCapabilities.length === 0 ? <Empty description="暂无可配置能力" /> : (
                <div className="sys-config-group">
                  {configCapabilities.map((cap) => {
                    const grant = tenantCapabilityGrants.find((g) => g.capabilityId === cap.id);
                    const granted = grant?.grantStatus === "enabled";
                    return (
                      <div key={cap.id} className="sys-form-row">
                        <span className="sys-form-label">{cap.name}</span>
                        <div style={{display:"flex",alignItems:"center",gap:10}}>
                          <span className="sys-info-tag sys-info-tag--primary">{formatCapabilityType(cap.capabilityType)}</span>
                          <span className={`sys-status sys-status--${granted ? "active" : "inactive"}`}><span className="sys-status-dot"/>{granted ? "已启用" : "未启用"}</span>
                          <button className="sys-btn sys-btn--default sys-btn--sm" onClick={()=> grant ? void updateTenantCapabilityGrant(grant.id, granted ? "disabled" : "enabled") : void grantCapabilityToTenant(cap.id)}>
                            {granted ? "取消启用" : "启用"}
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* 成员 / 角色 */}
          {tenantActiveTab === "members" && (
            <div className="sys-drawer-section">
              <div className="sys-section-header"><Users size={18}/> 成员与角色</div>
              <div className="sys-hint"><Info size={14}/> 租户内的组织与人员，系统管理员可在此协助诊断越权问题。</div>
              <Spin spinning={tenantOrganizationLoading}>
                {tenantOrganizationError ? (
                  <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">
                    {tenantOrganizationError}
                  </div>
                ) : null}
                {tenantOrganizationOverview ? (
                  <div className="sys-config-group">
                    <div className="sys-info-tags" style={{ marginBottom: 12 }}>
                      <span className="sys-info-tag sys-info-tag--primary">{tenantOrganizationOverview.memberships.length} 名成员</span>
                      <span className="sys-info-tag">{tenantOrganizationOverview.departments.length} 个部门</span>
                      <span className="sys-info-tag">{tenantOrganizationOverview.roles.length} 个角色</span>
                    </div>
                    {tenantOrganizationOverview.memberships.length === 0 ? (
                      <Empty description="暂无成员数据" />
                    ) : (
                      tenantOrganizationOverview.memberships.map((membership) => (
                        <div key={membership.id} className="sys-form-row">
                          <span className="sys-form-label">{membership.userDisplayName || "未找到账号"}</span>
                          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", justifyContent: "flex-end" }}>
                            <span className="sys-info-tag">{membership.departmentName || "未分配部门"}</span>
                            {membership.roles.length === 0 ? (
                              <span className="sys-info-tag sys-info-tag--info">未分配角色</span>
                            ) : membership.roles.map((role) => (
                              <span key={role.id} className="sys-info-tag sys-info-tag--info">{role.name}</span>
                            ))}
                            <span className={`sys-status sys-status--${membership.status === "active" ? "active" : "inactive"}`}>
                              <span className="sys-status-dot" />{membership.status === "active" ? "启用" : "停用"}
                            </span>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                ) : !tenantOrganizationLoading && !tenantOrganizationError ? (
                  <Empty description="暂无成员数据" />
                ) : null}
              </Spin>
            </div>
          )}

          {/* 模型分配 */}
          {tenantActiveTab === "models" && (
            <div className="sys-drawer-section">
              <div className="sys-section-header"><DatabaseZap size={18}/> 模型分配</div>
              <div className="sys-hint"><Info size={14}/> 这里把平台级模型供应商分配给单个租户；默认模型可先沿用供应商配置，后续再扩展额度、成本和备用路由。</div>
              {modelProviders.length === 0 ? <Empty description="暂无可分配模型供应商" /> : (
                <div className="sys-config-group">
                  {modelProviders.map((provider) => {
                    const assigned = tenantModelAssignments.find((item) => item.providerId === provider.id);
                    return (
                      <div key={provider.id} className="sys-form-row">
                        <span className="sys-form-label">{provider.name}</span>
                        <div style={{display:"flex",alignItems:"center",gap:10,flexWrap:"wrap",justifyContent:"flex-end"}}>
                          <span className="sys-info-tag sys-info-tag--info">{provider.providerType}</span>
                          <span className="sys-form-value">{assigned?.defaultModel || provider.defaultModel || "未配置默认模型"}</span>
                          <span className={`sys-status sys-status--${assigned ? "active" : "inactive"}`}><span className="sys-status-dot"/>{assigned ? "已分配" : "未分配"}</span>
                          <button className="sys-btn sys-btn--default sys-btn--sm" disabled={Boolean(assigned)} onClick={()=>void assignModelToTenant(provider)}>
                            {assigned ? "已分配" : "分配"}
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* 抽屉底部操作栏 */}
          <div className="sys-drawer-footer">
            <div className="sys-drawer-footer-right">
              <button className="sys-btn sys-btn--default" onClick={()=>setTenantDrawerOpen(false)}><X size={14}/> 取消</button>
              <button className="sys-btn sys-btn--primary" onClick={()=>{messageApi.success("配置已保存");setTenantDrawerOpen(false);}}><Save size={14}/> 保存配置</button>
            </div>
          </div>
        </>)}
      </Drawer>

      {/* 新增租户弹窗（AuraOA 分页签风格） */}
      {createTenantModalOpen && (
        <div className="sys-modal-mask" onClick={()=>setCreateTenantModalOpen(false)}>
          <div className="sys-modal" style={{maxWidth:600}} onClick={e=>e.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">新增租户及管理员</span>
              <button className="sys-modal-close" onClick={()=>setCreateTenantModalOpen(false)}><X size={18}/></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-modal-tabs">
                <button className={`sys-modal-tab ${createTenantTab==="basic"?"sys-modal-tab--active":""}`} onClick={()=>setCreateTenantTab("basic")}>基本信息</button>
                <button className={`sys-modal-tab ${createTenantTab==="admin"?"sys-modal-tab--active":""}`} onClick={()=>setCreateTenantTab("admin")}>管理员设置</button>
              </div>
              {createTenantTab==="basic"&&(
                <div className="sys-drawer-section">
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">租户名称</label><div className="sys-field-input-wrap"><Building2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：某某科技有限公司" maxLength={160} defaultValue={ctRef.current.name||""} onChange={e=>{ctRef.current.name=e.target.value;}}/></div></div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">租户编码</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="英文数字标识，如 mx_tech" maxLength={100} defaultValue={ctRef.current.code||""} onChange={e=>{ctRef.current.code=e.target.value;}}/></div><div className="sys-field-hint">留空则由系统自动生成</div></div>
                  </div>
                </div>
              )}
              {createTenantTab==="admin"&&(
                <div className="sys-drawer-section">
                  <div className="sys-hint"><Info size={14}/> 必须为新租户指定一名初始租户管理员，该管理员登录后可继续在「租户管理」中添加其他成员。</div>
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">管理员账号</label><div className="sys-field-input-wrap"><User size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="登录用用户名" maxLength={50} defaultValue={ctRef.current.admin_username||""} onChange={e=>{ctRef.current.admin_username=e.target.value;}}/></div></div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">管理员姓名</label><div className="sys-field-input-wrap"><Type size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="显示名称" maxLength={50} defaultValue={ctRef.current.admin_displayName||""} onChange={e=>{ctRef.current.admin_displayName=e.target.value;}}/></div></div>
                  </div>
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">初始密码</label><div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="password" placeholder="请妥善保管" maxLength={100} defaultValue={ctRef.current.admin_password||""} onChange={e=>{ctRef.current.admin_password=e.target.value;}}/></div></div>
                    <div className="sys-field"><label className="sys-field-label">联系邮箱</label><div className="sys-field-input-wrap"><Mail size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="可选" maxLength={100} defaultValue={ctRef.current.admin_email||""} onChange={e=>{ctRef.current.admin_email=e.target.value;}}/></div></div>
                  </div>
                </div>
              )}
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={()=>setCreateTenantModalOpen(false)}><X size={14}/> 取消</button>
              <button className="sys-btn sys-btn--primary" onClick={()=>void submitCreateTenant()}><PlusCircle size={14}/> 确认创建</button>
            </div>
          </div>
        </div>
      )}

      {/* 注册模型供应商弹窗 */}
      {modelModalOpen && (
        <div className="sys-modal-mask" onClick={()=>setModelModalOpen(false)}>
          <div className="sys-modal" style={{maxWidth:520}} onClick={e=>e.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">注册模型供应商</span>
              <button className="sys-modal-close" onClick={()=>setModelModalOpen(false)}><X size={18}/></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-field"><label className="sys-field-label sys-field-label--required">名称</label><div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：通义千问" maxLength={160} onChange={e=>{modelRef.current.name=e.target.value;}}/></div></div>
              <div className="sys-field"><label className="sys-field-label sys-field-label--required">供应商类型</label><SysSelect icon={ServerCog} placeholder="请选择数据库字典类型" options={modelProviderTypes.map((type)=>({value:type.code,label:type.name}))} onChange={v=>{modelRef.current.providerType=v;setSelectedModelProviderType(v);}}/></div>
              {selectedModelProviderType && (
                <div className="sys-hint"><ServerCog size={14}/> {modelProviderTypes.find((type)=>type.code===selectedModelProviderType)?.description || "平台内置模型供应商类型"}</div>
              )}
              <div className="sys-field"><label className="sys-field-label">基址 URL</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder={modelProviderTypes.find((type)=>type.code===selectedModelProviderType)?.defaultBaseUrl || "https://api.example.com"} maxLength={500} onChange={e=>{modelRef.current.baseUrl=e.target.value;}}/></div><div className="sys-field-hint">不填写时沿用供应商类型的默认基址</div></div>
              <div className="sys-field"><label className="sys-field-label">默认模型</label><div className="sys-field-input-wrap"><DatabaseZap size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如 qwen-max" maxLength={160} onChange={e=>{modelRef.current.defaultModel=e.target.value;}}/></div></div>
              <div className="sys-field"><label className="sys-field-label">状态</label><SysSelect icon={Check} defaultValue="draft" options={[{value:"draft",label:"草稿"},{value:"active",label:"可用"}]} onChange={v=>{modelRef.current.status=v;}}/></div>
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={()=>setModelModalOpen(false)}><X size={14}/> 取消</button>
              <button className="sys-btn sys-btn--primary" onClick={()=>void submitModel()}><PlusCircle size={14}/> 确认注册</button>
            </div>
          </div>
        </div>
      )}

      {/* 注册系统能力弹窗 */}
      {capModalOpen && (
        <div className="sys-modal-mask" onClick={()=>setCapModalOpen(false)}>
          <div className="sys-modal" style={{maxWidth:520}} onClick={e=>e.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">注册系统能力</span>
              <button className="sys-modal-close" onClick={()=>setCapModalOpen(false)}><X size={18}/></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-field"><label className="sys-field-label sys-field-label--required">能力类型</label><SysSelect icon={Boxes} defaultValue="mcp" options={capabilityTypeOptions} onChange={v=>{capRef.current.capabilityType=v;setSelectedCapabilityType(v);}}/></div>
              <div className="sys-field-row">
                <div className="sys-field"><label className="sys-field-label sys-field-label--required">名称</label><div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：文档解析器" maxLength={160} onChange={e=>{capRef.current.name=e.target.value;}}/></div></div>
                <div className="sys-field"><label className="sys-field-label sys-field-label--required">编码</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如 doc_parser" maxLength={100} onChange={e=>{capRef.current.code=e.target.value;}}/></div></div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field"><label className="sys-field-label">版本</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" defaultValue="v1" maxLength={40} onChange={e=>{capRef.current.version=e.target.value;}}/></div></div>
                <div className="sys-field"><label className="sys-field-label">风险等级</label><SysSelect icon={ShieldAlert} defaultValue="low" options={[{value:"low",label:"低"},{value:"medium",label:"中"},{value:"high",label:"高"}]} onChange={v=>{capRef.current.riskLevel=v;}}/></div>
              </div>
              <div className="sys-field"><label className="sys-field-label">状态</label><SysSelect icon={Check} defaultValue="draft" options={[{value:"draft",label:"草稿"},{value:"active",label:"启用"}]} onChange={v=>{capRef.current.status=v;}}/></div>
              {selectedCapabilityType === "mcp" && (
                <div className="sys-config-group">
                  <div className="sys-field"><label className="sys-field-label">MCP 传输方式</label><SysSelect icon={ServerCog} defaultValue="stdio" options={[{value:"stdio",label:"stdio 命令"},{value:"sse",label:"SSE 地址"}]} onChange={v=>{capRef.current.transport=v;setSelectedMcpTransport(v);}}/></div>
                  {selectedMcpTransport === "sse" ? (
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">SSE 地址</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="https://mcp.example.com/sse" maxLength={500} onChange={e=>{capRef.current.sseUrl=e.target.value;}}/></div></div>
                  ) : (
                    <>
                      <div className="sys-field"><label className="sys-field-label sys-field-label--required">启动命令</label><div className="sys-field-input-wrap"><ServerCog size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="node /opt/mcp/server.js" maxLength={500} onChange={e=>{capRef.current.command=e.target.value;}}/></div></div>
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label">命令参数</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="--readonly --tenant-safe" maxLength={500} onChange={e=>{capRef.current.args=e.target.value;}}/></div></div>
                        <div className="sys-field"><label className="sys-field-label">工作目录</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="/opt/agentum/mcp/file-read" maxLength={500} onChange={e=>{capRef.current.workingDir=e.target.value;}}/></div></div>
                      </div>
                    </>
                  )}
                </div>
              )}
              {(selectedCapabilityType === "skill" || selectedCapabilityType === "prompt_template") && (
                <div className="sys-field-row">
                  <div className="sys-field"><label className="sys-field-label">源码路径</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder={selectedCapabilityType === "skill" ? "capabilities/skills/..." : "capabilities/prompt-templates/..."} maxLength={500} onChange={e=>{capRef.current.sourcePath=e.target.value;}}/></div></div>
                  <div className="sys-field"><label className="sys-field-label">Manifest 路径</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="manifest.yaml / skill.yaml" maxLength={500} onChange={e=>{capRef.current.manifestPath=e.target.value;}}/></div></div>
                </div>
              )}
              {selectedCapabilityType === "delivery" && (
                <div className="sys-field-row">
                  <div className="sys-field"><label className="sys-field-label">交付通道</label><SysSelect icon={Mail} options={[{value:"document",label:"文档生成"},{value:"email",label:"邮件"},{value:"oa",label:"OA 流程"},{value:"webhook",label:"Webhook"}]} onChange={v=>{capRef.current.deliveryChannel=v;}}/></div>
                  <div className="sys-field"><label className="sys-field-label">目标说明</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="通道标识或模板路径" maxLength={500} onChange={e=>{capRef.current.target=e.target.value;}}/></div></div>
                </div>
              )}
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={()=>setCapModalOpen(false)}><X size={14}/> 取消</button>
              <button className="sys-btn sys-btn--primary" onClick={()=>void submitCapability()}><PlusCircle size={14}/> 确认注册</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
