import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Boxes,
  BrainCircuit,
  Building2,
  Check,
  CircleAlert,
  ChevronDown,
  Clock,
  Code2,
  Copy,
  DatabaseZap,
  Edit,
  FileText,
  Globe,
  Hash,
  Info,
  KeyRound,
  LayoutDashboard,
  Mail,
  PlusCircle,
  PlayCircle,
  ServerCog,
  Settings2,
  ShieldCheck,
  ShieldAlert,
  Tag,
  Trash2,
  Type,
  User,
  Users,
  WandSparkles,
  X,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Empty, Segmented, Spin, message, Drawer, Pagination } from "antd";
import { SurfacePageLayout } from "../../components/workbench/SurfacePageLayout";
import { SysModalMask } from "../../components/common/SysModalMask";
import { DocumentDeliveryStyleAdminSections } from "../../components/document/DocumentDeliveryStyleAdminSections";
import { AttachmentRecognitionSettingsPanel } from "./AttachmentRecognitionSettingsPanel";
import { paths } from "../../routes/paths";
import { AgentumApiError, API_BASE_URL, organizationApi, systemApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { OrganizationMembership, TenantOrganizationOverview } from "../../types/organization";
import { isValidUsername, usernameRuleMessage } from "../../utils/username";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import { copyTextToClipboard } from "../../utils/clipboard";
import type {
  CreateModelProviderRequest,
  CreateTenantAdminRequest,
  CreateTenantRequest,
  CreateTenantCapabilityGrantRequest,
  CapabilityTestResult,
  ModelProviderRow,
  ModelProviderTypeRow,
  SaveTenantSsoProviderRequest,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantRow,
  TenantCapabilityGrantRow,
  TenantModelAssignmentRow,
  TenantSsoProviderRow,
  UpdateTenantAdminProfileRequest,
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

  // 非受控模式下把初始选中值同步给外层 ref，避免界面已显示选项但提交校验仍认为未填写。
  useEffect(() => {
    const initial = value ?? defaultValue ?? "";
    if (initial) {
      onChange?.(initial);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅在挂载时同步一次初始值
  }, []);

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

type SystemSection = "overview" | "tenants" | "models" | "capabilities" | "settings";

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

const BASIC_SHARED_PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

function generateBasicSharedPassword(): string {
  const randomBytes = new Uint8Array(32);
  crypto.getRandomValues(randomBytes);
  return Array.from(randomBytes, (value) => BASIC_SHARED_PASSWORD_ALPHABET[value % BASIC_SHARED_PASSWORD_ALPHABET.length]).join("");
}

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

type ReachabilityStatus = "online" | "offline" | "stale";

/** 提示词模板为静态配置，无需连通性探测。 */
function capabilitySupportsConnectivityTest(capabilityType: string): boolean {
  return capabilityType !== "prompt_template";
}

function getCapabilityTestDetailTitle(capabilityType: string): string {
  if (capabilityType === "mcp") return "可用工具";
  if (capabilityType === "skill") return "Skill 校验项";
  if (capabilityType === "delivery") return "交付能力";
  return "探测详情";
}

function resolveConnectivityStatus(status?: string | null): ReachabilityStatus {
  if (status === "stale") return "stale";
  return status === "online" ? "online" : "offline";
}

function CardStatusStack({ children }: { children: React.ReactNode }) {
  return <div className="sys-card-status-stack">{children}</div>;
}

function ReachabilityBadge({ status }: { status: ReachabilityStatus }) {
  const online = status === "online";
  const label = status === "online" ? "在线" : status === "stale" ? "待重新测试" : "离线";
  return (
    <div className={`sys-status sys-status--${online ? "active" : "inactive"}`}>
      <span className="sys-status-dot" />
      {label}
    </div>
  );
}

function RiskTag({ level }: { level: string }) {
  const label = `${formatRisk(level)}风险`;
  const cls = level === "high" ? "sys-info-tag--danger" : level === "medium" ? "sys-info-tag--warn" : "sys-info-tag--success";
  return <span className={`sys-info-tag ${cls}`}>{label}</span>;
}

function readConfigString(config: Record<string, unknown>, key: string): string {
  const value = config[key];
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return typeof value === "string" ? value : "";
}

function readConfigBoolean(config: Record<string, unknown>, key: string): boolean {
  const value = config[key];
  if (typeof value === "boolean") return value;
  if (typeof value === "string") return value === "true" || value === "1";
  return false;
}

function buildModelFormValues(provider: ModelProviderRow): Record<string, string> {
  return {
    name: provider.name,
    providerType: provider.providerType,
    baseUrl: provider.baseUrl || "",
    defaultModel: provider.defaultModel || "",
    status: provider.status,
    maxTokens: provider.maxTokens != null ? String(provider.maxTokens) : "",
    reasoningModel: provider.reasoningModel ? "true" : "false",
  };
}

function buildCapabilityFormValues(capability: SystemCapabilityRow): Record<string, string> {
  return {
    capabilityType: capability.capabilityType,
    name: capability.name,
    code: capability.code,
    version: capability.version,
    description: capability.description ?? "",
    riskLevel: capability.riskLevel,
    status: capability.status,
    transport: readConfigString(capability.config, "transport") || "sse",
    sseUrl: readConfigString(capability.config, "sseUrl"),
    sourceType: readConfigString(capability.config, "sourceType") || "builtin",
    deliveryChannel: readConfigString(capability.config, "deliveryChannel") === "document" && readConfigString(capability.config, "documentKind") === "excel"
      ? "excel"
      : readConfigString(capability.config, "deliveryChannel") || "email",
    documentChineseFont: readNestedStyleString(capability.config, "chineseFont") || "宋体",
    documentLatinFont: readNestedStyleString(capability.config, "latinFont") || "Times New Roman",
    documentNumberFont: readNestedStyleString(capability.config, "numberFont") || "Times New Roman",
    documentBodyFontSize: readNestedStyleString(capability.config, "bodyFontSize") || "12",
    documentBodyAlignment: readNestedStyleString(capability.config, "bodyAlignment") || "left",
    documentHeading1FontSize: readNestedStyleString(capability.config, "heading1FontSize") || "16",
    documentHeading2FontSize: readNestedStyleString(capability.config, "heading2FontSize") || "14",
    documentHeading3FontSize: readNestedStyleString(capability.config, "heading3FontSize") || "13",
    documentHeading4FontSize: readNestedStyleString(capability.config, "heading4FontSize") || "0",
    documentHeading5FontSize: readNestedStyleString(capability.config, "heading5FontSize") || "0",
    documentHeading1ChineseFont: readNestedStyleString(capability.config, "heading1ChineseFont"),
    documentHeading1LatinFont: readNestedStyleString(capability.config, "heading1LatinFont"),
    documentHeading1NumberFont: readNestedStyleString(capability.config, "heading1NumberFont"),
    documentHeading2ChineseFont: readNestedStyleString(capability.config, "heading2ChineseFont"),
    documentHeading2LatinFont: readNestedStyleString(capability.config, "heading2LatinFont"),
    documentHeading2NumberFont: readNestedStyleString(capability.config, "heading2NumberFont"),
    documentHeading3ChineseFont: readNestedStyleString(capability.config, "heading3ChineseFont"),
    documentHeading3LatinFont: readNestedStyleString(capability.config, "heading3LatinFont"),
    documentHeading3NumberFont: readNestedStyleString(capability.config, "heading3NumberFont"),
    documentHeading4ChineseFont: readNestedStyleString(capability.config, "heading4ChineseFont"),
    documentHeading4LatinFont: readNestedStyleString(capability.config, "heading4LatinFont"),
    documentHeading4NumberFont: readNestedStyleString(capability.config, "heading4NumberFont"),
    documentHeading5ChineseFont: readNestedStyleString(capability.config, "heading5ChineseFont"),
    documentHeading5LatinFont: readNestedStyleString(capability.config, "heading5LatinFont"),
    documentHeading5NumberFont: readNestedStyleString(capability.config, "heading5NumberFont"),
    documentHeading1Bold: readNestedStyleString(capability.config, "heading1Bold") || "true",
    documentHeading2Bold: readNestedStyleString(capability.config, "heading2Bold") || "true",
    documentHeading3Bold: readNestedStyleString(capability.config, "heading3Bold") || "true",
    documentHeading4Bold: readNestedStyleString(capability.config, "heading4Bold") || "true",
    documentHeading5Bold: readNestedStyleString(capability.config, "heading5Bold") || "true",
    documentTableChineseFont: readNestedStyleString(capability.config, "tableChineseFont"),
    documentTableLatinFont: readNestedStyleString(capability.config, "tableLatinFont"),
    documentTableNumberFont: readNestedStyleString(capability.config, "tableNumberFont"),
    documentTableFontSize: readNestedStyleString(capability.config, "tableFontSize") || "0",
    documentTableCellAlignment: readNestedStyleString(capability.config, "tableCellAlignment") || "left",
    documentTableCellVerticalAlignment: readNestedStyleString(capability.config, "tableCellVerticalAlignment") || "center",
    documentTableCellPaddingVerticalPt: readNestedStyleString(capability.config, "tableCellPaddingVerticalPt") || "1.5",
    documentTableHeaderBold: readNestedStyleString(capability.config, "tableHeaderBold") || "false",
    documentTableBorders: readNestedStyleString(capability.config, "tableBorders") || "true",
    documentTableBorderWidthPt: readNestedStyleString(capability.config, "tableBorderWidthPt") || "0.5",
    documentTableLineSpacingMode: readNestedStyleString(capability.config, "tableLineSpacingMode") || "multiple",
    documentTableLineSpacing: readNestedStyleString(capability.config, "tableLineSpacing") || "1",
    documentTableLineSpacingPt: readNestedStyleString(capability.config, "tableLineSpacingPt") || "12",
    documentLineSpacingMode: readNestedStyleString(capability.config, "lineSpacingMode") || "multiple",
    documentLineSpacing: readNestedStyleString(capability.config, "lineSpacing") || "1.5",
    documentLineSpacingPt: readNestedStyleString(capability.config, "lineSpacingPt") || "18",
    documentFirstLineIndentMode: readNestedStyleString(capability.config, "firstLineIndentMode") || "chars",
    documentFirstLineIndentChars: readNestedStyleString(capability.config, "firstLineIndentChars") || "2",
    documentFirstLineIndentCm: readNestedStyleString(capability.config, "firstLineIndentCm") || "0.75",
    documentParagraphSpacingUnit: readNestedStyleString(capability.config, "paragraphSpacingUnit") || "pt",
    documentParagraphSpacingBefore: readNestedStyleString(capability.config, "paragraphSpacingBefore") || "0",
    documentParagraphSpacingAfter: readNestedStyleString(capability.config, "paragraphSpacingAfter") || "6",
    documentMarginTopCm: readNestedStyleString(capability.config, "marginTopCm") || "2.54",
    documentMarginBottomCm: readNestedStyleString(capability.config, "marginBottomCm") || "2.54",
    documentMarginLeftCm: readNestedStyleString(capability.config, "marginLeftCm") || "3.18",
    documentMarginRightCm: readNestedStyleString(capability.config, "marginRightCm") || "3.18",
    documentTitleCentered: readNestedStyleString(capability.config, "titleCentered") || "false",
    documentHeadingFirstLineIndent: readNestedStyleString(capability.config, "headingFirstLineIndent") || "false",
    documentMaxFileSizeMb: readConfigString(capability.config, "maxFileSizeMb") || "20",
    documentRetentionPolicy: readConfigString(capability.config, "retentionPolicy") || "days",
    documentRetentionDays: readConfigString(capability.config, "retentionDays") || "180",
    implementationKey: readConfigString(capability.config, "implementationKey"),
    protocol: readConfigString(capability.config, "protocol") || "http",
    endpointUrl: readConfigString(capability.config, "endpointUrl"),
    smtpHost: readConfigString(capability.config, "smtpHost"),
    smtpPort: readConfigString(capability.config, "smtpPort"),
    smtpUsername: readConfigString(capability.config, "smtpUsername"),
    fromAddress: readConfigString(capability.config, "fromAddress"),
    useTls: readConfigBoolean(capability.config, "useTls") ? "true" : "false",
    smtpPasswordConfigured: readConfigBoolean(capability.config, "smtpPasswordConfigured") ? "true" : "false",
    sourcePath: readConfigString(capability.config, "sourcePath"),
    manifestPath: readConfigString(capability.config, "manifestPath"),
    promptContent: readConfigString(capability.config, "promptContent"),
  };
}

function readNestedStyleString(config: Record<string, unknown>, key: string): string {
  const style = config.defaultStyle;
  if (style && typeof style === "object" && !Array.isArray(style)) {
    return readConfigString(style as Record<string, unknown>, key);
  }
  return readConfigString(config, key);
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
  const navigate = useNavigate();
  const location = useLocation();
  const section = useMemo<SystemSection>(() => {
    if (location.pathname.startsWith(paths.system.tenants)) {
      return "tenants";
    }
    if (location.pathname.startsWith(paths.system.models)) {
      return "models";
    }
    if (location.pathname.startsWith(paths.system.capabilities)) {
      return "capabilities";
    }
    if (location.pathname.startsWith(paths.system.settings)) {
      return "settings";
    }
    return "overview";
  }, [location.pathname]);

  function navigateSection(next: SystemSection) {
    if (next === "overview") navigate(paths.system.overview);
    else if (next === "tenants") navigate(paths.system.tenants);
    else if (next === "models") navigate(paths.system.models);
    else if (next === "capabilities") navigate(paths.system.capabilities);
    else navigate(paths.system.settings);
  }

  const token = useAuthStore((s) => s.token);
  const themeMode = useAuthStore((s) => s.themeMode);
  const fetchSsoProviders = useAuthStore((s) => s.fetchSsoProviders);
  const [messageApi, messageContextHolder] = message.useMessage();
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode);

  const [loading, setLoading] = useState(false);

  const [summary, setSummary] = useState<SystemSummary | null>(null);
  const [previewTenants, setPreviewTenants] = useState<SystemTenantRow[]>([]);
  const [previewModelProviders, setPreviewModelProviders] = useState<ModelProviderRow[]>([]);
  const [previewCapabilities, setPreviewCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [tenants, setTenants] = useState<SystemTenantRow[]>([]);
  const [tenantPage, setTenantPage] = useState<AdminPageState>(defaultPageState);
  const [modelProviders, setModelProviders] = useState<ModelProviderRow[]>([]);
  const [modelProviderPage, setModelProviderPage] = useState<AdminPageState>(defaultPageState);
  const [modelProviderTypes, setModelProviderTypes] = useState<ModelProviderTypeRow[]>([]);
  const [capabilities, setCapabilities] = useState<SystemCapabilityRow[]>([]);
  const [capabilityPage, setCapabilityPage] = useState<AdminPageState>(defaultPageState);
  const [configCapabilities, setConfigCapabilities] = useState<SystemCapabilityRow[]>([]);

  // 租户侧边抽屉状态
  const [tenantDrawerOpen, setTenantDrawerOpen] = useState(false);
  const [selectedTenant, setSelectedTenant] = useState<SystemTenantRow | null>(null);
  const [tenantActiveTab, setTenantActiveTab] = useState("default");
  const [tenantCapabilityGrants, setTenantCapabilityGrants] = useState<TenantCapabilityGrantRow[]>([]);
  const [tenantModelAssignments, setTenantModelAssignments] = useState<TenantModelAssignmentRow[]>([]);
  const [tenantSsoProviders, setTenantSsoProviders] = useState<TenantSsoProviderRow[]>([]);
  const [tenantSsoType, setTenantSsoType] = useState<"oidc" | "basic">("oidc");
  const [tenantSsoEnabled, setTenantSsoEnabled] = useState(false);
  const [basicSharedPasswordDraft, setBasicSharedPasswordDraft] = useState("");
  const tenantSsoRef = useRef<Record<string, string>>({});
  const [tenantOrganizationOverview, setTenantOrganizationOverview] = useState<TenantOrganizationOverview | null>(null);
  const [tenantOrganizationLoading, setTenantOrganizationLoading] = useState(false);
  const [tenantOrganizationError, setTenantOrganizationError] = useState("");

  const basicRedirectionEndpoint = useMemo(() => {
    const apiOrigin = API_BASE_URL || window.location.origin;
    return new URL("/api/auth/sso/basic-redirection", apiOrigin).toString();
  }, []);
  const basicUsernameFormat = selectedTenant ? `${selectedTenant.code}/<OA loginid>` : "tenantCode/<OA loginid>";

  useEffect(() => {
    setBasicSharedPasswordDraft("");
  }, [selectedTenant?.id]);

  // 新增租户 Modal（不使用 Ant Form，改用原生 ref）
  const [createTenantModalOpen, setCreateTenantModalOpen] = useState(false);
  const [createTenantTab, setCreateTenantTab] = useState<"basic"|"admin">("basic");
  const ctRef = useRef<Record<string,string>>({});
  const [tenantAdminModalOpen, setTenantAdminModalOpen] = useState(false);
  const [editingTenantAdmin, setEditingTenantAdmin] = useState<OrganizationMembership | null>(null);
  const [tenantAdminSubmitting, setTenantAdminSubmitting] = useState(false);
  const tenantAdminRef = useRef<Record<string,string>>({});

  // 模型与能力 Modal
  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [capModalOpen, setCapModalOpen] = useState(false);
  const [editingModelProvider, setEditingModelProvider] = useState<ModelProviderRow | null>(null);
  const [editingCapability, setEditingCapability] = useState<SystemCapabilityRow | null>(null);
  const [selectedModelProviderType, setSelectedModelProviderType] = useState("");
  const [selectedCapabilityType, setSelectedCapabilityType] = useState("mcp");
  const [selectedMcpTransport, setSelectedMcpTransport] = useState("sse");
  const [selectedDeliverySourceType, setSelectedDeliverySourceType] = useState("builtin");
  const [selectedDeliveryChannel, setSelectedDeliveryChannel] = useState("email");
  const [selectedDocumentRetentionPolicy, setSelectedDocumentRetentionPolicy] = useState("days");
  const modelRef = useRef<Record<string,string>>({});
  const capRef = useRef<Record<string,string>>({});
  const [modelFormKey, setModelFormKey] = useState(0);
  const [capFormKey, setCapFormKey] = useState(0);
  const [capabilityTestResult, setCapabilityTestResult] = useState<CapabilityTestResult | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<
    | { type: "model"; target: ModelProviderRow }
    | { type: "capability"; target: SystemCapabilityRow }
    | null
  >(null);
  const [deleteSubmitting, setDeleteSubmitting] = useState(false);

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
      const [tenantPreview, modelPreview, capabilityPreview] = await Promise.all([
        systemApi.listTenants(token, 1, 4),
        systemApi.listModelProviders(token, 1, 4),
        systemApi.listCapabilities(token, 1, 4),
      ]);
      setPreviewTenants(tenantPreview.items);
      setPreviewModelProviders(modelPreview.items);
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

  const loadTenantSsoProviders = useCallback(async (tenantId: string) => {
    if (!token) return;
    try {
      const providers = await systemApi.listTenantSsoProviders(tenantId, token);
      setTenantSsoProviders(providers);
      const enabledProvider = providers.find((provider) => provider.status === "enabled") ?? providers[0];
      const nextType = enabledProvider?.providerType ?? "oidc";
      setTenantSsoType(nextType);
      setTenantSsoEnabled(enabledProvider?.status === "enabled");
      tenantSsoRef.current = enabledProvider ? {
        name: enabledProvider.name,
        issuer: enabledProvider.issuer ?? "",
        clientId: enabledProvider.clientId ?? "",
        authorizationEndpoint: enabledProvider.authorizationEndpoint ?? "",
        tokenEndpoint: enabledProvider.tokenEndpoint ?? "",
        jwksUri: enabledProvider.jwksUri ?? "",
        allowedIpRanges: enabledProvider.allowedIpRanges ?? "",
        allowedDomains: enabledProvider.allowedDomains ?? "",
      } : {};
    } catch (e) {
      handleApiError(e, "加载租户企业认证配置失败");
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
    if (tenantActiveTab === "auth") {
      void loadTenantSsoProviders(selectedTenant.id);
    }
    if (tenantActiveTab === "members") {
      // 系统管理抽屉只做跨租户诊断视图，实际成员维护仍回到租户管理页并由后端复核租户上下文。
      void loadTenantOrganizationOverview(selectedTenant.id);
    }
  }, [selectedTenant, tenantDrawerOpen, tenantActiveTab, loadConfigCapabilities, loadTenantCapabilityGrants, loadModels, loadTenantModelAssignments, loadTenantSsoProviders, loadTenantOrganizationOverview]);

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
    if (!isValidUsername(d.admin_username)) { setCreateTenantTab("admin"); messageApi.warning(usernameRuleMessage); return; }
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

  const findMemberAccount = (membership: OrganizationMembership) =>
    tenantOrganizationOverview?.members.find((member) => member.id === membership.userId);

  const openTenantAdminModal = (membership: OrganizationMembership | null) => {
    setEditingTenantAdmin(membership);
    const account = membership ? findMemberAccount(membership) : null;
    tenantAdminRef.current = membership
      ? {
          username: account?.username ?? "",
          displayName: account?.displayName ?? membership.userDisplayName,
          email: account?.email ?? "",
        }
      : {};
    setTenantAdminModalOpen(true);
  };

  const submitTenantAdmin = async () => {
    if (!token || !selectedTenant || tenantAdminSubmitting) return;
    const d = tenantAdminRef.current;
    if (!d.username?.trim()) { messageApi.warning("请输入管理员账号"); return; }
    if (!isValidUsername(d.username)) { messageApi.warning(usernameRuleMessage); return; }
    if (!d.displayName?.trim()) { messageApi.warning("请输入管理员姓名"); return; }
    if (!editingTenantAdmin && !d.password?.trim()) { messageApi.warning("请输入初始密码"); return; }
    setTenantAdminSubmitting(true);
    try {
      if (editingTenantAdmin) {
        await systemApi.updateTenantAdminProfile(selectedTenant.id, editingTenantAdmin.id, token, {
          username: d.username.trim(),
          displayName: d.displayName.trim(),
          email: d.email?.trim() || undefined,
        } as UpdateTenantAdminProfileRequest);
        messageApi.success("租户管理员信息已更新");
      } else {
        await systemApi.createTenantAdmin(selectedTenant.id, token, {
          username: d.username.trim(),
          displayName: d.displayName.trim(),
          password: d.password,
          email: d.email?.trim() || undefined,
          departmentId: d.departmentId || undefined,
        } as CreateTenantAdminRequest);
        messageApi.success("租户管理员已新增");
      }
      setTenantAdminModalOpen(false);
      setEditingTenantAdmin(null);
      tenantAdminRef.current = {};
      void loadTenantOrganizationOverview(selectedTenant.id);
    } catch (e) {
      handleApiError(e, editingTenantAdmin ? "更新租户管理员失败" : "新增租户管理员失败");
    } finally {
      setTenantAdminSubmitting(false);
    }
  };

  const updateTenantAdminStatus = async (membership: OrganizationMembership, status: "active" | "disabled") => {
    if (!token || !selectedTenant) return;
    try {
      await systemApi.updateTenantAdminStatus(selectedTenant.id, membership.id, token, { status });
      messageApi.success(status === "active" ? "租户管理员已启用" : "租户管理员已停用");
      void loadTenantOrganizationOverview(selectedTenant.id);
    } catch (e) {
      handleApiError(e, status === "active" ? "启用租户管理员失败" : "停用租户管理员失败");
    }
  };

  const openModelModal = (provider: ModelProviderRow | null) => {
    setEditingModelProvider(provider);
    modelRef.current = provider ? buildModelFormValues(provider) : { maxTokens: "8192", reasoningModel: "false" };
    setSelectedModelProviderType(provider?.providerType ?? "");
    setModelFormKey((key) => key + 1);
    setModelModalOpen(true);
  };

  const openCapabilityModal = (capability: SystemCapabilityRow | null) => {
    setEditingCapability(capability);
    const formValues = capability ? buildCapabilityFormValues(capability) : { capabilityType: "mcp", transport: "sse", documentRetentionPolicy: "days", documentRetentionDays: "180" };
    capRef.current = formValues;
    setSelectedCapabilityType(capability?.capabilityType ?? "mcp");
    setSelectedMcpTransport(capability ? (readConfigString(capability.config, "transport") || "sse") : "sse");
    setSelectedDeliverySourceType(capability ? (readConfigString(capability.config, "sourceType") || "builtin") : "builtin");
    setSelectedDeliveryChannel(formValues.deliveryChannel || "email");
    setSelectedDocumentRetentionPolicy(formValues.documentRetentionPolicy || "days");
    setCapFormKey((key) => key + 1);
    setCapModalOpen(true);
  };

  const confirmDeleteModelProvider = (provider: ModelProviderRow) => {
    setDeleteConfirm({ type: "model", target: provider });
  };

  const confirmDeleteCapability = (capability: SystemCapabilityRow) => {
    setDeleteConfirm({ type: "capability", target: capability });
  };

  const executeDelete = async () => {
    if (!token || !deleteConfirm || deleteSubmitting) return;
    setDeleteSubmitting(true);
    try {
      if (deleteConfirm.type === "model") {
        const provider = deleteConfirm.target;
        await systemApi.deleteModelProvider(token, provider.id);
        messageApi.success("已删除模型供应商");
        if (editingModelProvider?.id === provider.id) {
          setModelModalOpen(false);
          setEditingModelProvider(null);
          modelRef.current = {};
        }
        void loadModels();
      } else {
        const capability = deleteConfirm.target;
        await systemApi.deleteCapability(token, capability.id);
        messageApi.success("已删除系统能力");
        if (editingCapability?.id === capability.id) {
          setCapModalOpen(false);
          setEditingCapability(null);
          capRef.current = {};
        }
        void loadCapabilities();
      }
      void loadSummary();
      setDeleteConfirm(null);
    } catch (e) {
      handleApiError(
        e,
        deleteConfirm.type === "model" ? "删除模型供应商失败" : "删除系统能力失败",
      );
    } finally {
      setDeleteSubmitting(false);
    }
  };

  const submitModel = async () => {
    if (!token) return;
    const d = modelRef.current;
    if (!d.name?.trim()) { messageApi.warning("请输入名称"); return; }
    if (!d.providerType?.trim()) { messageApi.warning("请选择供应商类型"); return; }
    if (!d.defaultModel?.trim()) { messageApi.warning("请输入默认模型"); return; }
    const maxTokens = Number.parseInt(d.maxTokens?.trim() || "", 10);
    if (!Number.isFinite(maxTokens) || maxTokens < 256 || maxTokens > 131072) {
      messageApi.warning("最大输出 Token 需在 256～131072 之间");
      return;
    }
    try {
      const request: CreateModelProviderRequest = {
        name: d.name.trim(),
        providerType: d.providerType.trim(),
        baseUrl: d.baseUrl?.trim() || undefined,
        defaultModel: d.defaultModel.trim(),
        apiKey: d.apiKey?.trim() || undefined,
        status: d.status?.trim() || "draft",
        maxTokens,
        reasoningModel: d.reasoningModel === "true",
      };
      if (editingModelProvider) {
        const updated = await systemApi.updateModelProvider(token, editingModelProvider.id, request);
        setEditingModelProvider(updated);
        messageApi.success("已更新模型供应商");
      } else {
        await systemApi.createModelProvider(token, request);
        messageApi.success("已注册模型供应商");
      }
      setModelModalOpen(false);
      setEditingModelProvider(null);
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
    if (!d.capabilityType?.trim()) { messageApi.warning("请选择能力类型"); return; }
    if (!d.name?.trim()) { messageApi.warning("请输入名称"); return; }
    const capabilityType = d.capabilityType.trim();
    const config: Record<string, unknown> = {};
    if (capabilityType === "mcp") {
      const transport = selectedMcpTransport || "sse";
      config.transport = transport;
      if (transport === "streamable_http") {
        config.endpointUrl = d.endpointUrl?.trim() || "";
        if (!config.endpointUrl) {
          messageApi.warning("请输入 HTTP 端点地址");
          return;
        }
      } else {
        config.sseUrl = d.sseUrl?.trim() || "";
        if (!config.sseUrl) {
          messageApi.warning("请输入 SSE 地址");
          return;
        }
      }
    } else if (capabilityType === "delivery") {
      config.sourceType = d.sourceType?.trim() || "builtin";
      if (config.sourceType === "custom") {
        config.implementationKey = d.implementationKey?.trim() || "";
        config.manifestPath = d.manifestPath?.trim() || "";
        config.protocol = d.protocol?.trim() || "http";
        config.endpointUrl = d.endpointUrl?.trim() || "";
        if (!config.implementationKey) {
          messageApi.warning("请输入自定义交付实现标识");
          return;
        }
        if (!config.manifestPath) {
          messageApi.warning("请输入 Manifest 路径");
          return;
        }
      } else {
        const deliveryChannel = d.deliveryChannel?.trim() || selectedDeliveryChannel || "email";
        config.deliveryChannel = deliveryChannel;
        if (deliveryChannel === "excel") {
          config.documentKind = "excel";
          config.maxFileSizeMb = d.documentMaxFileSizeMb?.trim() || "20";
          config.retentionPolicy = d.documentRetentionPolicy?.trim() || "days";
          config.retentionDays = d.documentRetentionDays?.trim() || "180";
        } else if (deliveryChannel === "document") {
          config.documentKind = "word";
          config.allowNodeStyleOverride = true;
          config.maxFileSizeMb = d.documentMaxFileSizeMb?.trim() || "20";
          config.retentionPolicy = d.documentRetentionPolicy?.trim() || "days";
          config.retentionDays = d.documentRetentionDays?.trim() || "180";
          config.defaultStyle = {
            chineseFont: d.documentChineseFont?.trim() || "宋体",
            latinFont: d.documentLatinFont?.trim() || "Times New Roman",
            numberFont: d.documentNumberFont?.trim() || "Times New Roman",
            bodyFontSize: d.documentBodyFontSize?.trim() || "12",
            bodyAlignment: d.documentBodyAlignment?.trim() || "left",
            heading1FontSize: d.documentHeading1FontSize?.trim() || "16",
            heading2FontSize: d.documentHeading2FontSize?.trim() || "14",
            heading3FontSize: d.documentHeading3FontSize?.trim() || "13",
            ...(d.documentHeading4FontSize?.trim() && d.documentHeading4FontSize.trim() !== "0"
              ? { heading4FontSize: d.documentHeading4FontSize.trim() }
              : {}),
            ...(d.documentHeading5FontSize?.trim() && d.documentHeading5FontSize.trim() !== "0"
              ? { heading5FontSize: d.documentHeading5FontSize.trim() }
              : {}),
            heading1Bold: d.documentHeading1Bold !== "false",
            heading2Bold: d.documentHeading2Bold !== "false",
            heading3Bold: d.documentHeading3Bold !== "false",
            heading4Bold: d.documentHeading4Bold !== "false",
            heading5Bold: d.documentHeading5Bold !== "false",
            ...(d.documentHeading1ChineseFont?.trim() ? { heading1ChineseFont: d.documentHeading1ChineseFont.trim() } : {}),
            ...(d.documentHeading1LatinFont?.trim() ? { heading1LatinFont: d.documentHeading1LatinFont.trim() } : {}),
            ...(d.documentHeading1NumberFont?.trim() ? { heading1NumberFont: d.documentHeading1NumberFont.trim() } : {}),
            ...(d.documentHeading2ChineseFont?.trim() ? { heading2ChineseFont: d.documentHeading2ChineseFont.trim() } : {}),
            ...(d.documentHeading2LatinFont?.trim() ? { heading2LatinFont: d.documentHeading2LatinFont.trim() } : {}),
            ...(d.documentHeading2NumberFont?.trim() ? { heading2NumberFont: d.documentHeading2NumberFont.trim() } : {}),
            ...(d.documentHeading3ChineseFont?.trim() ? { heading3ChineseFont: d.documentHeading3ChineseFont.trim() } : {}),
            ...(d.documentHeading3LatinFont?.trim() ? { heading3LatinFont: d.documentHeading3LatinFont.trim() } : {}),
            ...(d.documentHeading3NumberFont?.trim() ? { heading3NumberFont: d.documentHeading3NumberFont.trim() } : {}),
            ...(d.documentHeading4ChineseFont?.trim() ? { heading4ChineseFont: d.documentHeading4ChineseFont.trim() } : {}),
            ...(d.documentHeading4LatinFont?.trim() ? { heading4LatinFont: d.documentHeading4LatinFont.trim() } : {}),
            ...(d.documentHeading4NumberFont?.trim() ? { heading4NumberFont: d.documentHeading4NumberFont.trim() } : {}),
            ...(d.documentHeading5ChineseFont?.trim() ? { heading5ChineseFont: d.documentHeading5ChineseFont.trim() } : {}),
            ...(d.documentHeading5LatinFont?.trim() ? { heading5LatinFont: d.documentHeading5LatinFont.trim() } : {}),
            ...(d.documentHeading5NumberFont?.trim() ? { heading5NumberFont: d.documentHeading5NumberFont.trim() } : {}),
            ...(d.documentTableChineseFont?.trim() ? { tableChineseFont: d.documentTableChineseFont.trim() } : {}),
            ...(d.documentTableLatinFont?.trim() ? { tableLatinFont: d.documentTableLatinFont.trim() } : {}),
            ...(d.documentTableNumberFont?.trim() ? { tableNumberFont: d.documentTableNumberFont.trim() } : {}),
            ...(d.documentTableFontSize?.trim() && d.documentTableFontSize.trim() !== "0"
              ? { tableFontSize: d.documentTableFontSize.trim() }
              : {}),
            tableCellAlignment: d.documentTableCellAlignment?.trim() || "left",
            tableCellVerticalAlignment: d.documentTableCellVerticalAlignment?.trim() || "center",
            tableCellPaddingVerticalPt: d.documentTableCellPaddingVerticalPt?.trim() || "1.5",
            tableHeaderBold: d.documentTableHeaderBold === "true",
            tableBorders: d.documentTableBorders !== "false",
            tableBorderWidthPt: d.documentTableBorderWidthPt?.trim() || "0.5",
            tableLineSpacingMode: d.documentTableLineSpacingMode?.trim() || "multiple",
            tableLineSpacing: d.documentTableLineSpacing?.trim() || "1",
            ...(d.documentTableLineSpacingMode === "exact"
              ? { tableLineSpacingPt: d.documentTableLineSpacingPt?.trim() || "12" }
              : {}),
            lineSpacingMode: d.documentLineSpacingMode?.trim() || "multiple",
            lineSpacing: d.documentLineSpacing?.trim() || "1.5",
            ...(d.documentLineSpacingMode === "exact"
              ? { lineSpacingPt: d.documentLineSpacingPt?.trim() || "18" }
              : {}),
            firstLineIndentMode: d.documentFirstLineIndentMode?.trim() || "chars",
            firstLineIndentChars: d.documentFirstLineIndentChars?.trim() || "2",
            firstLineIndentCm: d.documentFirstLineIndentCm?.trim() || "0.75",
            paragraphSpacingUnit: d.documentParagraphSpacingUnit?.trim() || "pt",
            paragraphSpacingBefore: d.documentParagraphSpacingBefore?.trim() || "0",
            paragraphSpacingAfter: d.documentParagraphSpacingAfter?.trim() || "6",
            marginTopCm: d.documentMarginTopCm?.trim() || "2.54",
            marginBottomCm: d.documentMarginBottomCm?.trim() || "2.54",
            marginLeftCm: d.documentMarginLeftCm?.trim() || "3.18",
            marginRightCm: d.documentMarginRightCm?.trim() || "3.18",
            titleCentered: d.documentTitleCentered === "true",
            headingFirstLineIndent: d.documentHeadingFirstLineIndent === "true",
          };
        } else {
          config.smtpHost = d.smtpHost?.trim() || "";
          config.smtpPort = d.smtpPort?.trim() || "";
          config.smtpUsername = d.smtpUsername?.trim() || "";
          config.smtpPassword = d.smtpPassword?.trim() || "";
          config.fromAddress = d.fromAddress?.trim() || "";
          config.useTls = d.useTls === "true";
          if (!config.smtpHost) {
            messageApi.warning("请输入 SMTP 主机");
            return;
          }
          if (!config.smtpPort) {
            messageApi.warning("请输入 SMTP 端口");
            return;
          }
          if (!config.fromAddress) {
            messageApi.warning("请输入发件邮箱");
            return;
          }
        }
      }
    } else if (capabilityType === "skill") {
      config.sourcePath = d.sourcePath?.trim() || "";
      if (!config.sourcePath) {
        messageApi.warning("请输入 Skill 路径");
        return;
      }
    } else if (capabilityType === "prompt_template") {
      config.promptContent = d.promptContent?.trim() || "";
      if (!config.promptContent) {
        messageApi.warning("请输入提示词内容");
        return;
      }
    } else {
      config.sourcePath = d.sourcePath?.trim() || "";
      config.manifestPath = d.manifestPath?.trim() || "";
    }
    try {
      const baseRequest = {
        capabilityType,
        name: d.name.trim(),
        version: d.version?.trim() || "v1",
        description: d.description?.trim() || "",
        riskLevel: d.riskLevel?.trim() || "low",
        status: d.status?.trim() || "draft",
        config,
      };
      if (editingCapability) {
        const updated = await systemApi.updateCapability(token, editingCapability.id, baseRequest);
        setEditingCapability(updated);
        messageApi.success("已更新系统能力");
      } else {
        await systemApi.createCapability(token, baseRequest);
        messageApi.success("已注册系统能力");
      }
      setCapModalOpen(false);
      setEditingCapability(null);
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
      setCapabilityTestResult(result);
      const mergeCapabilityConnectivity = (items: SystemCapabilityRow[]) =>
        items.map((item) => (item.id === capId ? { ...item, connectivityStatus: result.connectivityStatus } : item));
      setCapabilities(mergeCapabilityConnectivity);
      setPreviewCapabilities(mergeCapabilityConnectivity);
      setConfigCapabilities(mergeCapabilityConnectivity);
      setEditingCapability((prev) => (prev?.id === capId ? { ...prev, connectivityStatus: result.connectivityStatus } : prev));
      const content = result.status === "success" ? result.summary : `测试未通过：${result.summary}`;
      if (result.status === "success") messageApi.success({ content, key: `test_conn_${capId}` });
      else messageApi.warning({ content, key: `test_conn_${capId}` });
    } catch (e) {
      handleApiError(e, "能力测试失败");
    }
  };

  const testModelProviderConnection = async (provider?: ModelProviderRow | null) => {
    if (!token) return;
    const d = modelRef.current;
    if (!provider && !d.providerType?.trim()) {
      messageApi.warning("请选择模型供应商");
      return;
    }
    if (!provider && !d.defaultModel?.trim()) {
      messageApi.warning("请输入默认模型");
      return;
    }
    const messageKey = `test_model_${provider?.id ?? editingModelProvider?.id ?? "draft"}`;
    messageApi.loading({ content: "正在测试模型供应商连接...", key: messageKey });
    try {
      const result = provider
        ? await systemApi.testModelProvider(token, provider.id)
        : await systemApi.testModelProviderDraft(token, {
            providerId: editingModelProvider?.id,
            providerType: d.providerType!.trim(),
            baseUrl: d.baseUrl?.trim() || undefined,
            defaultModel: d.defaultModel!.trim(),
            apiKey: d.apiKey?.trim() || undefined,
          });
      if (!provider) {
        const modelPreview = result.availableModels.length > 0 ? `；模型：${result.availableModels.slice(0, 3).join("、")}` : "";
        const content = `${result.summary}${modelPreview}（当前表单尚未保存）`;
        if (result.status === "success") messageApi.success({ content, key: messageKey });
        else messageApi.warning({ content, key: messageKey });
        return;
      }
      const mergeProviderConnectivity = (items: ModelProviderRow[]) =>
        items.map((item) => (item.id === provider.id ? { ...item, connectivityStatus: result.connectivityStatus } : item));
      setModelProviders(mergeProviderConnectivity);
      setPreviewModelProviders(mergeProviderConnectivity);
      setEditingModelProvider((prev) => (prev?.id === provider.id ? { ...prev, connectivityStatus: result.connectivityStatus } : prev));
      const modelPreview = result.availableModels.length > 0 ? `；模型：${result.availableModels.slice(0, 3).join("、")}` : "";
      const content = `${result.summary}${modelPreview}`;
      if (result.status === "success") {
        messageApi.success({ content, key: messageKey });
      } else {
        messageApi.warning({ content, key: messageKey });
      }
    } catch (e) {
      handleApiError(e, "模型供应商测试失败");
    }
  };

  const testMcpDraftConnection = async () => {
    if (!token) return;
    const transport = selectedMcpTransport === "streamable_http" ? "streamable_http" : "sse";
    const endpointUrl = transport === "streamable_http"
      ? capRef.current.endpointUrl?.trim()
      : capRef.current.sseUrl?.trim();
    if (!endpointUrl) {
      messageApi.warning(transport === "streamable_http" ? "请输入 HTTP 端点地址" : "请输入 SSE 地址");
      return;
    }
    const messageKey = `test_mcp_draft_${editingCapability?.id ?? "new"}`;
    messageApi.loading({ content: "正在测试当前 MCP 配置...", key: messageKey });
    try {
      const result = await systemApi.testMcpDraft(token, {
        capabilityId: editingCapability?.id,
        transport,
        endpointUrl,
      });
      setCapabilityTestResult(result);
      const content = `${result.summary}（当前表单尚未保存）`;
      if (result.status === "success") messageApi.success({ content, key: messageKey });
      else messageApi.warning({ content, key: messageKey });
    } catch (e) {
      handleApiError(e, "MCP 配置测试失败");
    }
  };

  const grantCapabilityToTenant = async (capability: SystemCapabilityRow) => {
    if (!token || !selectedTenant) return;
    if (capability.status !== "active") {
      messageApi.warning("全局能力仍是草稿，请先在全局能力中将状态改为启用");
      return;
    }
    try {
      await systemApi.createGrant(token, { tenantId: selectedTenant.id, capabilityId: capability.id, status: "enabled" } as CreateTenantCapabilityGrantRequest);
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
    if (provider.status !== "active") {
      messageApi.warning("模型供应商仍是草稿，请先在模型供应商中将状态改为可用");
      return;
    }
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

  const updateTenantModelAssignmentStatus = async (assignmentId: string, status: "enabled" | "disabled") => {
    if (!token || !selectedTenant) return;
    try {
      await systemApi.updateTenantModelAssignmentStatus(token, assignmentId, { status });
      messageApi.success(status === "enabled" ? "已启用租户模型" : "已取消模型分配");
      void loadTenantModelAssignments(selectedTenant.id);
    } catch (e) {
      handleApiError(e, status === "enabled" ? "启用租户模型失败" : "取消模型分配失败");
    }
  };

  const submitTenantSsoProvider = async () => {
    if (!token || !selectedTenant) return;
    const d = tenantSsoRef.current;
    const body: SaveTenantSsoProviderRequest = {
      providerType: tenantSsoType,
      status: tenantSsoEnabled ? "enabled" : "disabled",
    };
    if (tenantSsoType === "oidc") {
      body.issuer = d.issuer?.trim();
      body.clientId = d.clientId?.trim();
      body.clientSecret = d.clientSecret?.trim();
      body.authorizationEndpoint = d.authorizationEndpoint?.trim();
      body.tokenEndpoint = d.tokenEndpoint?.trim();
      body.jwksUri = d.jwksUri?.trim();
    } else {
      body.basicPassword = basicSharedPasswordDraft.trim();
      body.allowedIpRanges = d.allowedIpRanges?.trim();
      body.allowedDomains = d.allowedDomains?.trim();
    }
    try {
      await systemApi.saveTenantSsoProvider(selectedTenant.id, token, body);
      messageApi.success(tenantSsoEnabled ? "租户企业认证配置已启用" : "租户企业认证已关闭");
      setBasicSharedPasswordDraft("");
      void loadTenantSsoProviders(selectedTenant.id);
      void fetchSsoProviders(selectedTenant.id);
    } catch (e) {
      handleApiError(e, "保存租户企业认证配置失败");
    }
  };

  const copyTenantSsoValue = async (value: string, successMessage: string) => {
    try {
      await copyTextToClipboard(value);
      messageApi.success(successMessage);
    } catch (error) {
      // SSO 字段可能包含共享密码，诊断日志只记录失败原因，禁止输出待复制的原始内容。
      console.warn("[system] 企业认证字段复制失败", error);
      messageApi.error("复制失败，请手动选择内容复制");
    }
  };

  const createBasicSharedPasswordDraft = () => {
    try {
      const password = generateBasicSharedPassword();
      setBasicSharedPasswordDraft(password);
      tenantSsoRef.current.basicPassword = password;
      messageApi.success("已生成新的共享密码，请复制到 OA 并保存企业认证配置");
    } catch {
      messageApi.error("当前浏览器无法安全生成随机密码，请手动填写高强度密码");
    }
  };

  const navItems: { key: SystemSection; label: string; icon: typeof LayoutDashboard; description: string }[] = [
    { key: "overview", label: "平台概览", icon: LayoutDashboard, description: "全局统计与治理概况" },
    { key: "tenants", label: "租户管理", icon: Building2, description: "隔离边界与全局分配" },
    { key: "models", label: "模型供应商", icon: DatabaseZap, description: "底层算力与路由配置" },
    { key: "capabilities", label: "全局能力", icon: Boxes, description: "系统级插件与通道" },
    { key: "settings", label: "系统配置", icon: Settings2, description: "附件识别与平台运行参数" },
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

  const deleteConfirmTitle = deleteConfirm?.type === "model" ? "删除模型供应商" : "删除系统能力";
  const deleteConfirmMessage = deleteConfirm
    ? deleteConfirm.type === "model"
      ? `确定删除「${deleteConfirm.target.name}」吗？相关租户的模型分配将一并移除。`
      : `确定删除「${deleteConfirm.target.name}」吗？相关租户能力授权将一并移除。`
    : "";

  return (
    <>
      {messageContextHolder}
      {deleteConfirm ? (
        <SysModalMask
          onClose={() => !deleteSubmitting && setDeleteConfirm(null)}
          className="agent-delete-confirm-mask"
        >
          <div
            className="sys-modal agent-delete-confirm-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="system-delete-confirm-title"
          >
            <div className="agent-delete-confirm-body">
              <div className="agent-delete-confirm-icon">
                <CircleAlert size={28} aria-hidden="true" />
              </div>
              <div className="agent-delete-confirm-content">
                <h2 id="system-delete-confirm-title">{deleteConfirmTitle}</h2>
                <p>{deleteConfirmMessage}</p>
              </div>
            </div>
            <div className="agent-delete-confirm-footer">
              <button
                type="button"
                className="sys-btn sys-btn--default"
                disabled={deleteSubmitting}
                onClick={() => setDeleteConfirm(null)}
              >
                取消
              </button>
              <button
                type="button"
                className="sys-btn sys-btn--danger"
                disabled={deleteSubmitting}
                onClick={() => void executeDelete()}
              >
                删除
              </button>
            </div>
          </div>
        </SysModalMask>
      ) : null}
      {capabilityTestResult ? (() => {
        const testedCapability = capabilities.find((c) => c.id === capabilityTestResult.capabilityId)
          ?? previewCapabilities.find((c) => c.id === capabilityTestResult.capabilityId);
        const testedCapabilityType = testedCapability?.capabilityType
          ?? (capabilityTestResult.capabilityId == null ? "mcp" : "");
        const detailTitle = getCapabilityTestDetailTitle(testedCapabilityType);
        const reachability = resolveConnectivityStatus(capabilityTestResult.connectivityStatus);
        return (
        <SysModalMask onClose={() => setCapabilityTestResult(null)}>
          <div className="sys-modal" style={{ maxWidth: 640 }}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">连通性测试结果</span>
              <button className="sys-modal-close" onClick={() => setCapabilityTestResult(null)}><X size={18}/></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-config-group">
                <div className="sys-form-row">
                  <span className="sys-form-label">连通状态</span>
                  <ReachabilityBadge status={reachability} />
                </div>
                <div className="sys-form-row">
                  <span className="sys-form-label">结果摘要</span>
                  <span className="sys-form-value">{capabilityTestResult.summary}</span>
                </div>
              </div>
              {capabilityTestResult.tools.length > 0 ? (
                <>
                  <div className="sys-section-header" style={{ marginTop: 18 }}><Boxes size={18}/> {detailTitle}</div>
                  <div className="sys-config-group">
                    {capabilityTestResult.tools.map((tool) => (
                      <div key={tool.name} className="sys-preview-item">
                        <div className="sys-preview-item-left" style={{ minWidth: 0 }}>
                          <div className="sys-preview-item-icon sys-card-avatar--cap"><Boxes size={16}/></div>
                          <div style={{ minWidth: 0 }}>
                            <div className="sys-preview-item-name">{tool.name}</div>
                            <div className="sys-preview-item-sub">{tool.description || "未提供说明"}</div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </>
              ) : null}
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={() => setCapabilityTestResult(null)}><X size={14}/> 关闭</button>
            </div>
          </div>
        </SysModalMask>
        );
      })() : null}
      <SurfacePageLayout
        markClassName="system-mgmt-page-mark"
        icon={ServerCog}
        title="系统管理"
        badge="平台底座"
        description="作为系统管理员，您可在此管理多租户生命周期、全局模型算力及底层基础能力。"
      >
          <div className="system-mgmt-module-switch mb-5">
            <div className="system-mgmt-segmented-scroll">
              <Segmented<SystemSection>
                aria-label="系统管理模块"
                value={section}
                onChange={(key) => navigateSection(key as SystemSection)}
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
                  ].map((s, index) => { const I = s.icon; return (
                    <div key={s.label} className="sys-overview-stat sys-card-enter" style={{ animationDelay: `${index * 40}ms` }}>
                      <div className={`sys-overview-stat-icon sys-overview-stat-icon--${s.cls}`}><I size={20} /></div>
                      <div><div className="sys-overview-stat-value">{s.val ?? "—"}</div><div className="sys-overview-stat-label">{s.label}</div></div>
                    </div>
                  );})}
                </div>
                <div className="sys-preview-grid">
                  <div className={`sys-preview-card sys-card-enter ${previewTenants.length === 0 ? "sys-preview-card--empty" : ""}`} style={{ animationDelay: "160ms" }}>
                    <div className="sys-preview-card-title"><Building2 size={16}/> 最近入驻租户</div>
                    {previewTenants.length === 0 ? (
                      <div className="sys-preview-card-body--empty">
                        <Empty description="暂无租户" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      </div>
                    ) : (<>
                      {previewTenants.map(t=>(
                        <div key={t.id} className="sys-preview-item" style={{cursor:"pointer"}} onClick={()=>{navigateSection("tenants");setSelectedTenant(t);setTenantDrawerOpen(true);}}>
                          <div className="sys-preview-item-left">
                            <div className="sys-preview-item-icon sys-card-avatar--tenant"><Building2 size={16}/></div>
                            <div><div className="sys-preview-item-name">{t.name}</div><div className="sys-preview-item-sub">{t.code}</div></div>
                          </div>
                          <div className={`sys-status sys-status--${t.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{t.status==='active'?'运行中':'已停用'}</div>
                        </div>
                      ))}
                      {(summary?.tenantTotal ?? 0)>4&&<button className="sys-btn sys-btn--link" onClick={()=>navigateSection("tenants")} style={{marginTop:8}}>查看全部 {summary?.tenantTotal ?? 0} 个租户 →</button>}
                    </>)}
                  </div>
                  <div className={`sys-preview-card sys-card-enter ${previewModelProviders.length === 0 ? "sys-preview-card--empty" : ""}`} style={{ animationDelay: "200ms" }}>
                    <div className="sys-preview-card-title"><DatabaseZap size={16}/> 模型提供商一览</div>
                    {previewModelProviders.length === 0 ? (
                      <div className="sys-preview-card-body--empty">
                        <Empty description="暂无模型供应商" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      </div>
                    ) : (<>
                      {previewModelProviders.map(m=>(
                        <div key={m.id} className="sys-preview-item" style={{cursor:"pointer"}} onClick={()=>{
                          navigateSection("models");
                          openModelModal(m);
                        }}>
                          <div className="sys-preview-item-left">
                            <div className="sys-preview-item-icon sys-card-avatar--model"><DatabaseZap size={16}/></div>
                            <div><div className="sys-preview-item-name">{m.name}</div><div className="sys-preview-item-sub">{m.providerType} · {m.defaultModel || "未配置默认模型"}</div></div>
                          </div>
                          <CardStatusStack>
                            <div className={`sys-status sys-status--${m.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{formatModelStatus(m.status)}</div>
                            <ReachabilityBadge status={resolveConnectivityStatus(m.connectivityStatus)} />
                          </CardStatusStack>
                        </div>
                      ))}
                      {(summary?.modelProviderTotal ?? 0)>4&&<button className="sys-btn sys-btn--link" onClick={()=>navigateSection("models")} style={{marginTop:8}}>查看全部 {summary?.modelProviderTotal ?? 0} 个供应商 →</button>}
                    </>)}
                  </div>
                  <div className={`sys-preview-card sys-card-enter ${previewCapabilities.length === 0 ? "sys-preview-card--empty" : ""}`} style={{ animationDelay: "240ms" }}>
                    <div className="sys-preview-card-title"><Boxes size={16}/> 核心能力一览</div>
                    {previewCapabilities.length === 0 ? (
                      <div className="sys-preview-card-body--empty">
                        <Empty description="暂无能力" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      </div>
                    ) : (<>
                      {previewCapabilities.map(c=>(
                        <div key={c.id} className="sys-preview-item" style={{cursor:"pointer"}} onClick={()=>{
                          navigateSection("capabilities");
                          openCapabilityModal(c);
                        }}>
                          <div className="sys-preview-item-left">
                            <div className="sys-preview-item-icon sys-card-avatar--cap"><Boxes size={16}/></div>
                            <div><div className="sys-preview-item-name">{c.name}</div><div className="sys-preview-item-sub">{formatCapabilityType(c.capabilityType)} · {c.version}</div></div>
                          </div>
                          <RiskTag level={c.riskLevel}/>
                        </div>
                      ))}
                      {(summary?.systemCapabilityTotal ?? 0)>4&&<button className="sys-btn sys-btn--link" onClick={()=>navigateSection("capabilities")} style={{marginTop:8}}>查看全部 {summary?.systemCapabilityTotal ?? 0} 项能力 →</button>}
                    </>)}
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
                      {tenants.map((t, index)=>(
                        <div key={t.id} className="sys-card sys-card-enter" onClick={()=>{setSelectedTenant(t);setTenantActiveTab("default");setTenantDrawerOpen(true);}} style={{ animationDelay: `${index * 40}ms` }}>
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
                  <button className="sys-btn sys-btn--primary" onClick={()=>{void loadModels();openModelModal(null);}}><PlusCircle size={15}/> 注册供应商</button>
                </div>
                {modelProviders.length===0?<Empty description="暂无模型供应商" style={{marginTop:48}}/>:(
                  <>
                    <div className="sys-card-grid">
                      {modelProviders.map((m, index)=>(
                      <div key={m.id} className="sys-card sys-card-enter" onClick={()=>openModelModal(m)} style={{ animationDelay: `${index * 40}ms` }}>
                        <div className="sys-card-header">
                          <div className="sys-card-avatar sys-card-avatar--model"><DatabaseZap size={22}/></div>
                          <div className="sys-card-info"><div className="sys-card-name">{m.name}</div><div className="sys-card-code">{m.providerType}</div></div>
                          <CardStatusStack>
                            <div className={`sys-status sys-status--${m.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{formatModelStatus(m.status)}</div>
                            <ReachabilityBadge status={resolveConnectivityStatus(m.connectivityStatus)} />
                          </CardStatusStack>
                        </div>
                        <div className="sys-card-meta">
                          <div className="sys-meta-item"><span className="sys-meta-label">基址</span><span className="sys-meta-value">{m.baseUrl||"未配置"}</span></div>
                          <div className="sys-meta-item"><span className="sys-meta-label">默认模型</span><span className="sys-meta-value">{m.defaultModel||"未配置"}</span></div>
                          <div className="sys-meta-item"><span className="sys-meta-label">API Key</span><span className="sys-meta-value">{m.apiKeyConfigured ? "已配置" : "未配置"}</span></div>
                        </div>
                        <div className="sys-card-footer">
                          <span className="sys-card-footer-time"><ShieldCheck size={12}/> {m.providerType}</span>
                          <div className="sys-card-footer-actions" onClick={e=>e.stopPropagation()}>
                            <button className="sys-btn sys-btn--default sys-btn--sm" onClick={()=>void testModelProviderConnection(m)}><PlayCircle size={14}/> 测试连接</button>
                            <button className="sys-btn sys-btn--text sys-btn--sm" onClick={()=>openModelModal(m)}><Edit size={14}/> 编辑</button>
                            <button className="sys-btn sys-btn--text sys-btn--sm sys-btn--danger" onClick={()=>confirmDeleteModelProvider(m)}><Trash2 size={14}/> 删除</button>
                          </div>
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
                  <p style={{fontSize:14,color:"var(--color-text-tertiary)",margin:0}}>管理系统级 MCP、Skill、提示词模板和邮箱交付能力</p>
                  <button className="sys-btn sys-btn--primary" onClick={()=>openCapabilityModal(null)}><PlusCircle size={15}/> 注册能力</button>
                </div>
                {capabilities.length===0?<Empty description="暂无全局能力" style={{marginTop:48}}/>:(
                  <>
                    <div className="sys-card-grid">
                      {capabilities.map((c, index)=>(
                      <div key={c.id} className="sys-card sys-card-enter" onClick={()=>openCapabilityModal(c)} style={{ animationDelay: `${index * 40}ms` }}>
                        <div className="sys-card-header">
                          <div className="sys-card-avatar sys-card-avatar--cap"><Boxes size={22}/></div>
                          <div className="sys-card-info"><div className="sys-card-name">{c.name}</div><div className="sys-card-code">{c.code}</div></div>
                          <CardStatusStack>
                            <div className={`sys-status sys-status--${c.status==='active'?'active':'inactive'}`}><span className="sys-status-dot"/>{c.status==='active'?'启用':'草稿'}</div>
                            {capabilitySupportsConnectivityTest(c.capabilityType) ? (
                              <ReachabilityBadge status={resolveConnectivityStatus(c.connectivityStatus)} />
                            ) : (
                              <span className="sys-card-status-placeholder" aria-hidden="true" />
                            )}
                          </CardStatusStack>
                        </div>
                        <div className="sys-info-tags">
                          <span className="sys-info-tag sys-info-tag--primary">{formatCapabilityType(c.capabilityType)}</span>
                          <span className="sys-info-tag sys-info-tag--info">版本 {c.version}</span>
                          <RiskTag level={c.riskLevel}/>
                        </div>
                        <div className="sys-card-footer">
                          <span className="sys-card-footer-time"><ShieldCheck size={12}/> {c.code}</span>
                          <div className="sys-card-footer-actions" onClick={e=>e.stopPropagation()}>
                            {capabilitySupportsConnectivityTest(c.capabilityType) ? (
                              <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={()=>void testCapabilityConnection(c.id)}><PlayCircle size={14}/> 测试连通性</button>
                            ) : (
                              <button type="button" className="sys-btn sys-btn--default sys-btn--sm sys-card-footer-action-placeholder" tabIndex={-1} disabled aria-hidden="true"><PlayCircle size={14}/> 测试连通性</button>
                            )}
                            <button type="button" className="sys-btn sys-btn--text sys-btn--sm" onClick={()=>openCapabilityModal(c)}><Edit size={14}/> 编辑</button>
                            <button type="button" className="sys-btn sys-btn--text sys-btn--sm sys-btn--danger" onClick={()=>confirmDeleteCapability(c)}><Trash2 size={14}/> 删除</button>
                          </div>
                        </div>
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
            {section === "settings" ? <AttachmentRecognitionSettingsPanel /> : null}
          </Spin>
      </SurfacePageLayout>

      {/* 租户详情侧拉抽屉（页签 + 表单行） */}
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
              { key: "auth", label: "企业认证", icon: ShieldCheck },
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
            <div className="sys-drawer-section sys-drawer-section-enter">
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

          {/* 企业认证 */}
          {tenantActiveTab === "auth" && (
            <div className="sys-drawer-section sys-drawer-section-enter" key={`tenant-sso-${tenantSsoType}-${tenantSsoProviders.map((item) => item.id).join("-")}`}>
              <div className="sys-section-header"><ShieldCheck size={18}/> 企业认证</div>
              <div className="sys-config-group">
                <div className="sys-form-row">
                  <span className="sys-form-label">启用状态</span>
                  <div style={{display:"flex",alignItems:"center",gap:10}}>
                    <span className={`sys-status sys-status--${tenantSsoEnabled ? "active" : "inactive"}`}><span className="sys-status-dot"/>{tenantSsoEnabled ? "已启用" : "未启用"}</span>
                    <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => setTenantSsoEnabled(!tenantSsoEnabled)}>
                      {tenantSsoEnabled ? "关闭企业认证" : "开启企业认证"}
                    </button>
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">认证方式</label>
                  <SysSelect
                    icon={ShieldCheck}
                    value={tenantSsoType}
                    options={[{ value: "oidc", label: "OAuth2 / OIDC" }, { value: "basic", label: "Basic" }]}
                    onChange={(value) => {
                      const nextType = value as "oidc" | "basic";
                      setTenantSsoType(nextType);
                      setBasicSharedPasswordDraft("");
                      const provider = tenantSsoProviders.find((item) => item.providerType === nextType);
                      setTenantSsoEnabled(provider?.status === "enabled");
                      tenantSsoRef.current = provider ? {
                        issuer: provider.issuer ?? "",
                        clientId: provider.clientId ?? "",
                        authorizationEndpoint: provider.authorizationEndpoint ?? "",
                        tokenEndpoint: provider.tokenEndpoint ?? "",
                        jwksUri: provider.jwksUri ?? "",
                        allowedIpRanges: provider.allowedIpRanges ?? "",
                        allowedDomains: provider.allowedDomains ?? "",
                      } : {};
                    }}
                  />
                </div>

                {tenantSsoType === "oidc" ? (
                  <>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">Issuer</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="https://idp.example.com" defaultValue={tenantSsoRef.current.issuer || ""} onChange={(event) => { tenantSsoRef.current.issuer = event.target.value; }} /></div></div>
                    <div className="sys-field-row">
                      <div className="sys-field"><label className="sys-field-label sys-field-label--required">Client ID</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" defaultValue={tenantSsoRef.current.clientId || ""} onChange={(event) => { tenantSsoRef.current.clientId = event.target.value; }} /></div></div>
                      <div className="sys-field"><label className="sys-field-label">{tenantSsoProviders.find((item) => item.providerType === "oidc")?.clientSecretConfigured ? "Client Secret（留空保留原值）" : "Client Secret"}</label><div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="password" onChange={(event) => { tenantSsoRef.current.clientSecret = event.target.value; }} /></div></div>
                    </div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">授权地址</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" defaultValue={tenantSsoRef.current.authorizationEndpoint || ""} onChange={(event) => { tenantSsoRef.current.authorizationEndpoint = event.target.value; }} /></div></div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">Token 地址</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" defaultValue={tenantSsoRef.current.tokenEndpoint || ""} onChange={(event) => { tenantSsoRef.current.tokenEndpoint = event.target.value; }} /></div></div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">JWKS 地址</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" defaultValue={tenantSsoRef.current.jwksUri || ""} onChange={(event) => { tenantSsoRef.current.jwksUri = event.target.value; }} /></div></div>
                  </>
                ) : (
                  <>
                    <div className="sys-field">
                      <label className="sys-field-label">OA 服务端换址接口</label>
                      <div className="sys-copy-field">
                        <div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" value={basicRedirectionEndpoint} readOnly /></div>
                        <button type="button" className="sys-btn sys-btn--default sys-btn--sm sys-copy-field-button" aria-label="复制 OA 服务端换址接口" onClick={() => void copyTenantSsoValue(basicRedirectionEndpoint, "已复制 OA 服务端换址接口")}><Copy size={14}/> 复制</button>
                      </div>
                    </div>
                    <div className="sys-field">
                      <label className="sys-field-label">Basic 用户名格式</label>
                      <div className="sys-copy-field">
                        <div className="sys-field-input-wrap"><User size={16} className="sys-field-prefix"/><input className="sys-field-input" value={basicUsernameFormat} readOnly /></div>
                        <button type="button" className="sys-btn sys-btn--default sys-btn--sm sys-copy-field-button" aria-label="复制 Basic 用户名格式" onClick={() => void copyTenantSsoValue(basicUsernameFormat, "已复制 Basic 用户名格式")}><Copy size={14}/> 复制</button>
                      </div>
                    </div>
                    <div className="sys-field">
                      <label className="sys-field-label">共享密码</label>
                      <div className="sys-info-tags tenant-sso-password-status">
                        <span className={`sys-info-tag ${tenantSsoProviders.find((item) => item.providerType === "basic")?.basicPasswordConfigured ? "sys-info-tag--success" : "sys-info-tag--warn"}`}>
                          {tenantSsoProviders.find((item) => item.providerType === "basic")?.basicPasswordConfigured ? "已配置" : "尚未配置"}
                        </span>
                        <span className="sys-field-hint">已保存的密码不会回显；下方只用于首次设置或更换密码。</span>
                      </div>
                      <div className="sys-copy-field">
                        <div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="text" value={basicSharedPasswordDraft} placeholder={tenantSsoProviders.find((item) => item.providerType === "basic")?.basicPasswordConfigured ? "如需更换，请输入或生成新密码" : "请输入或生成共享密码"} autoComplete="new-password" onChange={(event) => { setBasicSharedPasswordDraft(event.target.value); tenantSsoRef.current.basicPassword = event.target.value; }} /></div>
                        <button type="button" className="sys-btn sys-btn--default sys-btn--sm sys-copy-field-button" disabled={!basicSharedPasswordDraft} aria-label="复制新共享密码" onClick={() => void copyTenantSsoValue(basicSharedPasswordDraft, "已复制新共享密码")}><Copy size={14}/> 复制</button>
                      </div>
                      <button type="button" className="sys-btn sys-btn--text sys-btn--sm tenant-sso-generate-password" onClick={createBasicSharedPasswordDraft}><WandSparkles size={14}/> 生成高强度密码</button>
                      <div className="sys-field-hint">请先将新密码复制到 OA 服务端，再保存企业认证；保存成功后明文会从页面清除。服务端取得 302 Location 后，应将该地址重定向给浏览器。</div>
                    </div>
                    <div className="sys-field-row">
                      <div className="sys-field"><label className="sys-field-label">允许 IP</label><div className="sys-field-input-wrap"><ServerCog size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="逗号分隔，留空不限制" defaultValue={tenantSsoRef.current.allowedIpRanges || ""} onChange={(event) => { tenantSsoRef.current.allowedIpRanges = event.target.value; }} /></div></div>
                      <div className="sys-field"><label className="sys-field-label">允许域名</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如 oa.example.com，留空不限制" defaultValue={tenantSsoRef.current.allowedDomains || ""} onChange={(event) => { tenantSsoRef.current.allowedDomains = event.target.value; }} /></div></div>
                    </div>
                  </>
                )}
                <div className="tenant-org-actionbar tenant-admin-actionbar">
                  <div className="sys-info-tags tenant-admin-tags">
                    <span className="sys-info-tag sys-info-tag--primary">{tenantSsoType === "basic" ? "Basic" : "OAuth2 / OIDC"}</span>
                    <span className="sys-info-tag">{tenantSsoEnabled ? "保存后登录页展示企业入口" : "保存后关闭企业入口"}</span>
                  </div>
                  <button type="button" className="sys-btn sys-btn--primary sys-btn--sm" onClick={() => void submitTenantSsoProvider()}><ShieldCheck size={14}/> 保存企业认证</button>
                </div>
              </div>
            </div>
          )}

          {/* 能力配置 */}
          {tenantActiveTab === "capabilities" && (
            <div className="sys-drawer-section sys-drawer-section-enter">
              <div className="sys-section-header"><Boxes size={18}/> 全局能力配置</div>
              {configCapabilities.length === 0 ? <Empty description="暂无可配置能力" /> : (
                <div className="sys-config-group">
                  {configCapabilities.map((cap) => {
                    const grant = tenantCapabilityGrants.find((g) => g.capabilityId === cap.id);
                    const granted = grant?.grantStatus === "enabled";
                    const activeCapability = cap.status === "active";
                    return (
                      <div key={cap.id} className="sys-form-row">
                        <span className="sys-form-label">{cap.name}</span>
                        <div style={{display:"flex",alignItems:"center",gap:10}}>
                          <span className="sys-info-tag sys-info-tag--primary">{formatCapabilityType(cap.capabilityType)}</span>
                          <span className={`sys-status sys-status--${activeCapability ? "active" : "inactive"}`}><span className="sys-status-dot"/>{activeCapability ? "全局已启用" : "草稿不可分配"}</span>
                          <span className={`sys-status sys-status--${granted ? "active" : "inactive"}`}><span className="sys-status-dot"/>{granted ? "租户已启用" : "租户未启用"}</span>
                          <button className="sys-btn sys-btn--default sys-btn--sm" disabled={!activeCapability && !granted} onClick={()=> grant ? void updateTenantCapabilityGrant(grant.id, granted ? "disabled" : "enabled") : void grantCapabilityToTenant(cap)}>
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
            <div className="sys-drawer-section sys-drawer-section-enter">
              <div className="sys-section-header"><Users size={18}/> 租户管理员</div>

              <Spin spinning={tenantOrganizationLoading}>
                {tenantOrganizationError ? (
                  <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">
                    {tenantOrganizationError}
                  </div>
                ) : null}
                {tenantOrganizationOverview ? (
                  <div className="sys-config-group">
                    <div className="tenant-org-actionbar tenant-admin-actionbar">
                      <div className="sys-info-tags tenant-admin-tags">
                        <span className="sys-info-tag sys-info-tag--primary">{tenantOrganizationOverview.memberships.filter((item) => item.tenantAdmin).length} 名租户管理员</span>
                      <span className="sys-info-tag">{tenantOrganizationOverview.departments.length} 个部门</span>
                      </div>
                      <button type="button" className="sys-btn sys-btn--primary sys-btn--sm" onClick={() => openTenantAdminModal(null)}><PlusCircle size={14}/> 新增管理员</button>
                    </div>
                    {tenantOrganizationOverview.memberships.filter((item) => item.tenantAdmin).length === 0 ? (
                      <Empty description="暂无租户管理员" />
                    ) : (
                      tenantOrganizationOverview.memberships.filter((item) => item.tenantAdmin).map((membership) => (
                        <div key={membership.id} className="sys-form-row">
                          <span className="sys-form-label">{membership.userDisplayName || "未找到账号"}</span>
                          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", justifyContent: "flex-end" }}>
                            <span className="sys-info-tag">{membership.departmentName || "未分配部门"}</span>
                            <span className="sys-info-tag sys-info-tag--primary">租户管理员</span>
                            <span className={`sys-status sys-status--${membership.status === "active" ? "active" : "inactive"}`}>
                              <span className="sys-status-dot" />{membership.status === "active" ? "启用" : "停用"}
                            </span>
                            <button type="button" className="sys-btn sys-btn--text sys-btn--sm" onClick={() => openTenantAdminModal(membership)}><Edit size={14}/> 编辑</button>
                            <button
                              type="button"
                              className={`sys-btn sys-btn--text sys-btn--sm ${membership.status === "active" ? "sys-btn--danger" : ""}`}
                              onClick={() => void updateTenantAdminStatus(membership, membership.status === "active" ? "disabled" : "active")}
                            >
                              {membership.status === "active" ? "停用" : "启用"}
                            </button>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                ) : !tenantOrganizationLoading && !tenantOrganizationError ? (
                  <Empty description="暂无租户管理员" />
                ) : null}
              </Spin>
            </div>
          )}

          {/* 模型分配 */}
          {tenantActiveTab === "models" && (
            <div className="sys-drawer-section sys-drawer-section-enter">
              <div className="sys-section-header"><DatabaseZap size={18}/> 模型分配</div>

              {modelProviders.length === 0 ? <Empty description="暂无可分配模型供应商" /> : (
                <div className="sys-config-group">
                  {modelProviders.map((provider) => {
                    const assigned = tenantModelAssignments.find((item) => item.providerId === provider.id);
                    const enabled = assigned?.assignmentStatus === "enabled";
                    const activeProvider = provider.status === "active";
                    return (
                      <div key={provider.id} className="sys-form-row">
                        <span className="sys-form-label">{provider.name}</span>
                        <div style={{display:"flex",alignItems:"center",gap:10,flexWrap:"wrap",justifyContent:"flex-end"}}>
                          <span className="sys-info-tag sys-info-tag--info">{provider.providerType}</span>
                          <span className="sys-form-value">{assigned?.defaultModel || provider.defaultModel || "未配置默认模型"}</span>
                          <span className={`sys-status sys-status--${activeProvider ? "active" : "inactive"}`}><span className="sys-status-dot"/>{activeProvider ? "全局已启用" : "草稿不可分配"}</span>
                          <span className={`sys-status sys-status--${enabled ? "active" : "inactive"}`}><span className="sys-status-dot"/>{enabled ? "租户已分配" : "租户未分配"}</span>
                          <button className="sys-btn sys-btn--default sys-btn--sm" disabled={!activeProvider && !enabled} onClick={()=> assigned ? void updateTenantModelAssignmentStatus(assigned.id, enabled ? "disabled" : "enabled") : void assignModelToTenant(provider)}>
                            {enabled ? "取消分配" : "分配"}
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {tenantAdminModalOpen && (
            <SysModalMask
              onClose={() => !tenantAdminSubmitting && setTenantAdminModalOpen(false)}
              className="sys-modal-mask--over-drawer"
            >
              <div className="sys-modal" style={{ maxWidth: 600 }}>
                <div className="sys-modal-header">
                  <span className="sys-modal-title">{editingTenantAdmin ? "编辑租户管理员" : "新增租户管理员"}</span>
                  <button type="button" className="sys-modal-close" disabled={tenantAdminSubmitting} onClick={() => setTenantAdminModalOpen(false)}><X size={18}/></button>
                </div>
                <div className="sys-modal-body">
                  <div className="sys-drawer-section sys-drawer-section-enter">
                    <div className="sys-field-row">
                      <div className="sys-field"><label className="sys-field-label sys-field-label--required">管理员账号</label><div className="sys-field-input-wrap"><User size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：tenant_admin" maxLength={50} defaultValue={tenantAdminRef.current.username || ""} onChange={(event) => { tenantAdminRef.current.username = event.target.value; }} /></div><div className="sys-field-hint">{usernameRuleMessage}</div></div>
                      <div className="sys-field"><label className="sys-field-label sys-field-label--required">管理员姓名</label><div className="sys-field-input-wrap"><Type size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="显示名称" maxLength={50} defaultValue={tenantAdminRef.current.displayName || ""} onChange={(event) => { tenantAdminRef.current.displayName = event.target.value; }} /></div></div>
                    </div>
                    {!editingTenantAdmin ? (
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label sys-field-label--required">初始密码</label><div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="password" placeholder="请妥善保管" maxLength={100} onChange={(event) => { tenantAdminRef.current.password = event.target.value; }} /></div></div>
                        <div className="sys-field"><label className="sys-field-label">所属部门</label><SysSelect icon={Users} placeholder="未分配部门" options={(tenantOrganizationOverview?.departments ?? []).filter((department) => department.status === "active").map((department) => ({ value: department.id, label: department.name }))} onChange={(value) => { tenantAdminRef.current.departmentId = value; }} /></div>
                      </div>
                    ) : null}
                    <div className="sys-field"><label className="sys-field-label">联系邮箱</label><div className="sys-field-input-wrap"><Mail size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="可选" maxLength={100} defaultValue={tenantAdminRef.current.email || ""} onChange={(event) => { tenantAdminRef.current.email = event.target.value; }} /></div></div>
                  </div>
                </div>
                <div className="sys-modal-footer">
                  <button type="button" className="sys-btn sys-btn--default" disabled={tenantAdminSubmitting} onClick={() => setTenantAdminModalOpen(false)}><X size={14}/> 取消</button>
                  <button type="button" className="sys-btn sys-btn--primary" disabled={tenantAdminSubmitting} onClick={() => void submitTenantAdmin()}><PlusCircle size={14}/> {editingTenantAdmin ? "保存修改" : "确认新增"}</button>
                </div>
              </div>
            </SysModalMask>
          )}

          {/* 抽屉底部操作栏 */}
          <div className="sys-drawer-footer">
            <button className="sys-btn sys-btn--default" onClick={()=>setTenantDrawerOpen(false)}><X size={14}/> 关闭</button>
          </div>
        </>)}
      </Drawer>

      {/* 新增租户弹窗（分页签风格） */}
      {createTenantModalOpen && (
        <SysModalMask onClose={() => setCreateTenantModalOpen(false)}>
          <div className="sys-modal" style={{maxWidth:600}}>
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
                <div className="sys-drawer-section sys-drawer-section-enter">
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">租户名称</label><div className="sys-field-input-wrap"><Building2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：某某科技有限公司" maxLength={160} defaultValue={ctRef.current.name||""} onChange={e=>{ctRef.current.name=e.target.value;}}/></div></div>
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">租户编码</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="英文数字标识，如 mx_tech" maxLength={100} defaultValue={ctRef.current.code||""} onChange={e=>{ctRef.current.code=e.target.value;}}/></div><div className="sys-field-hint">留空则由系统自动生成</div></div>
                  </div>
                </div>
              )}
              {createTenantTab==="admin"&&(
                <div className="sys-drawer-section sys-drawer-section-enter">
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">管理员账号</label><div className="sys-field-input-wrap"><User size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：tenant_admin" maxLength={50} defaultValue={ctRef.current.admin_username||""} onChange={e=>{ctRef.current.admin_username=e.target.value;}}/></div><div className="sys-field-hint">{usernameRuleMessage}</div></div>
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
        </SysModalMask>
      )}

      {/* 注册模型供应商抽屉 */}
      <Drawer
        title={editingModelProvider ? "编辑模型供应商" : "注册模型供应商"}
        placement="right"
        width={560}
        onClose={()=>{setModelModalOpen(false);setEditingModelProvider(null);}}
        open={modelModalOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section sys-drawer-section-enter" key={modelFormKey}>
          <div className="sys-field"><label className="sys-field-label sys-field-label--required">名称</label><div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：通义千问" maxLength={160} defaultValue={modelRef.current.name || ""} onChange={e=>{modelRef.current.name=e.target.value;}}/></div></div>
          <div className="sys-field"><label className="sys-field-label sys-field-label--required">模型供应商</label><SysSelect icon={ServerCog} placeholder="请选择模型供应商" defaultValue={modelRef.current.providerType || ""} options={modelProviderTypes.map((type)=>({value:type.code,label:type.name}))} onChange={v=>{modelRef.current.providerType=v;setSelectedModelProviderType(v);}}/></div>
          {selectedModelProviderType && (() => {
            const desc = modelProviderTypes.find((type) => type.code === selectedModelProviderType)?.description;
            if (!desc || desc.includes("兼容 OpenAI Chat Completions")) return null;
            return <div className="sys-field-hint">{desc}</div>;
          })()}
          <div className="sys-field"><label className="sys-field-label">基址 URL</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder={modelProviderTypes.find((type)=>type.code===selectedModelProviderType)?.defaultBaseUrl || "https://api.example.com/v1"} maxLength={500} defaultValue={modelRef.current.baseUrl || ""} onChange={e=>{modelRef.current.baseUrl=e.target.value;}}/></div><div className="sys-field-hint">不填写时沿用供应商类型的默认基址</div></div>
          <div className="sys-field"><label className="sys-field-label sys-field-label--required">默认模型</label><div className="sys-field-input-wrap"><DatabaseZap size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如 qwen-max" maxLength={160} defaultValue={modelRef.current.defaultModel || ""} onChange={e=>{modelRef.current.defaultModel=e.target.value;}}/></div></div>
          <label className="workflow-toggle-row">
            <span><strong className="block text-sm">标记为推理模型</strong><small className="mt-1 block text-xs font-normal text-[var(--color-text-tertiary)]">标记后，流程设计可配置并展示独立的深度推理过程</small></span>
            <input type="checkbox" defaultChecked={modelRef.current.reasoningModel === "true"} onChange={e=>{modelRef.current.reasoningModel=String(e.target.checked);}}/>
          </label>
          <div className="sys-field"><label className="sys-field-label sys-field-label--required">最大输出 Token</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" type="number" min={256} max={131072} step={256} placeholder="例如 8192" defaultValue={modelRef.current.maxTokens || ""} onChange={e=>{modelRef.current.maxTokens=e.target.value;}}/></div><div className="sys-field-hint">控制单次模型回复上限；长报告建议 8192 或以上，不再由后端写死默认值。</div></div>
          <div className="sys-field"><label className="sys-field-label">API Key</label><div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="password" placeholder={editingModelProvider?.apiKeyConfigured ? "已配置，留空则保持不变" : "部分供应商需要填写"} maxLength={2000} onChange={e=>{modelRef.current.apiKey=e.target.value;}}/></div><div className="sys-field-hint">密钥由后端加密保存，不会在列表、日志或响应中回显；后续可替换为凭证托管。</div></div>
          <div className="sys-field"><label className="sys-field-label">状态</label><SysSelect icon={Check} placeholder="请选择状态" defaultValue={modelRef.current.status || ""} options={[{value:"draft",label:"草稿"},{value:"active",label:"可用"}]} onChange={v=>{modelRef.current.status=v;}}/></div>
          {editingModelProvider ? (
            <div className="sys-field">
              <label className="sys-field-label">连通状态</label>
              <div className="sys-field-static">
                <ReachabilityBadge status={resolveConnectivityStatus(editingModelProvider.connectivityStatus)} />
              </div>
              <div className="sys-field-hint">这里显示已保存配置的状态；下方按钮会测试当前尚未保存的表单内容</div>
            </div>
          ) : null}
        </div>
        <div className="sys-drawer-footer">
          <button className="sys-btn sys-btn--default" onClick={()=>{setModelModalOpen(false);setEditingModelProvider(null);}}><X size={14}/> 取消</button>
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={()=>void testModelProviderConnection()}><PlayCircle size={14}/> 测试当前配置</button>
            <button className="sys-btn sys-btn--primary" onClick={()=>void submitModel()}><PlusCircle size={14}/> {editingModelProvider ? "保存修改" : "确认注册"}</button>
          </div>
        </div>
      </Drawer>

      {/* 注册系统能力抽屉 */}
      <Drawer
        title={editingCapability ? "编辑系统能力" : "注册系统能力"}
        placement="right"
        width={560}
        onClose={()=>{setCapModalOpen(false);setEditingCapability(null);}}
        open={capModalOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section sys-drawer-section-enter" key={capFormKey}>
          <div className="sys-field"><label className="sys-field-label sys-field-label--required">能力类型</label><SysSelect icon={Boxes} placeholder="请选择能力类型" defaultValue={capRef.current.capabilityType || ""} options={capabilityTypeOptions} onChange={v=>{capRef.current.capabilityType=v;setSelectedCapabilityType(v);if(v==="delivery"&&!capRef.current.sourceType){capRef.current.sourceType="builtin";capRef.current.deliveryChannel=capRef.current.deliveryChannel||"email";setSelectedDeliverySourceType("builtin");setSelectedDeliveryChannel(capRef.current.deliveryChannel);}}}/></div>
          <div className="sys-field-row">
            <div className="sys-field"><label className="sys-field-label sys-field-label--required">名称</label><div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如：文档解析器" maxLength={160} defaultValue={capRef.current.name || ""} onChange={e=>{capRef.current.name=e.target.value;}}/></div></div>
            <div className="sys-field">
              <label className="sys-field-label">编码</label>
              <div className="sys-field-input-wrap">
                <Code2 size={16} className="sys-field-prefix"/>
                <input className="sys-field-input" disabled placeholder={editingCapability ? "" : "保存后自动生成"} value={editingCapability ? (capRef.current.code || "") : ""} readOnly />
              </div>
              <div className="sys-field-hint">{editingCapability ? "编码创建后不可修改" : "根据名称自动生成，保证全局唯一"}</div>
            </div>
          </div>
          <div className="sys-field-row">
            <div className="sys-field"><label className="sys-field-label">版本</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="例如 v1" maxLength={40} defaultValue={capRef.current.version || ""} onChange={e=>{capRef.current.version=e.target.value;}}/></div></div>
            <div className="sys-field"><label className="sys-field-label">风险等级</label><SysSelect icon={ShieldAlert} placeholder="请选择风险等级" defaultValue={capRef.current.riskLevel || ""} options={[{value:"low",label:"低"},{value:"medium",label:"中"},{value:"high",label:"高"}]} onChange={v=>{capRef.current.riskLevel=v;}}/></div>
          </div>
          <div className="sys-field"><label className="sys-field-label">状态</label><SysSelect icon={Check} placeholder="请选择状态" defaultValue={capRef.current.status || ""} options={[{value:"draft",label:"草稿"},{value:"active",label:"启用"}]} onChange={v=>{capRef.current.status=v;}}/></div>
          <div className="sys-field">
            <label className="sys-field-label">说明</label>
            <textarea className="sys-field-textarea" placeholder="说明这项能力的业务用途、输入约束和后续接入方向" maxLength={1000} defaultValue={capRef.current.description || ""} onChange={e=>{capRef.current.description=e.target.value;}} />
          </div>
          {editingCapability && capabilitySupportsConnectivityTest(editingCapability.capabilityType) ? (
            <div className="sys-field">
              <label className="sys-field-label">连通状态</label>
              <div className="sys-field-static">
                <ReachabilityBadge status={resolveConnectivityStatus(editingCapability.connectivityStatus)} />
              </div>
              <div className="sys-field-hint">这里显示已保存配置的状态；下方按钮会测试当前尚未保存的表单内容</div>
            </div>
          ) : null}
          {selectedCapabilityType === "mcp" && (
            <>
              <div className="sys-field">
                <label className="sys-field-label">传输类型</label>
                <SysSelect
                  icon={ServerCog}
                  placeholder="请选择传输类型"
                  value={selectedMcpTransport}
                  defaultValue={selectedMcpTransport}
                  options={[
                    { value: "sse", label: "SSE" },
                    { value: "streamable_http", label: "Streamable HTTP" },
                  ]}
                  onChange={(v) => {
                    setSelectedMcpTransport(v);
                  }}
                />
              </div>
              {selectedMcpTransport === "streamable_http" ? (
                <div className="sys-field" key="streamable_http">
                  <label className="sys-field-label sys-field-label--required">HTTP 端点</label>
                  <div className="sys-field-input-wrap">
                    <Globe size={16} className="sys-field-prefix" />
                    <input
                      className="sys-field-input"
                      placeholder="http://localhost:18080/mcp"
                      maxLength={500}
                      defaultValue={capRef.current.endpointUrl || ""}
                      onChange={(e) => {
                        capRef.current.endpointUrl = e.target.value;
                      }}
                    />
                  </div>
                </div>
              ) : (
                <div className="sys-field" key="sse">
                  <label className="sys-field-label sys-field-label--required">SSE 地址</label>
                  <div className="sys-field-input-wrap">
                    <Globe size={16} className="sys-field-prefix" />
                    <input
                      className="sys-field-input"
                      placeholder="http://localhost:18080/sse"
                      maxLength={500}
                      defaultValue={capRef.current.sseUrl || ""}
                      onChange={(e) => {
                        capRef.current.sseUrl = e.target.value;
                      }}
                    />
                  </div>
                </div>
              )}
            </>
          )}
          {selectedCapabilityType === "skill" && (
            <div className="sys-field">
              <label className="sys-field-label sys-field-label--required">Skill 路径</label>
              <div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="capabilities/skills/agentum-connectivity-check/SKILL.md" maxLength={500} defaultValue={capRef.current.sourcePath || ""} onChange={e=>{capRef.current.sourcePath=e.target.value;}}/></div>
              <div className="sys-field-hint">指向 SKILL.md；同目录下的 skill.yaml 会自动用于发布元数据校验与连通性测试</div>
            </div>
          )}
          {selectedCapabilityType === "prompt_template" && (
            <div className="sys-field">
              <label className="sys-field-label sys-field-label--required">提示词内容</label>
              <textarea className="sys-field-textarea min-h-[220px]" placeholder="直接输入提示词正文，支持后续在工作流节点中引用" maxLength={10000} defaultValue={capRef.current.promptContent || ""} onChange={e=>{capRef.current.promptContent=e.target.value;}} />
            </div>
          )}
          {selectedCapabilityType === "delivery" && (
            <>
              <div className="sys-field"><label className="sys-field-label">交付来源</label><SysSelect icon={Mail} placeholder="请选择交付来源" defaultValue={capRef.current.sourceType || "builtin"} options={[{value:"builtin",label:"系统内置"},{value:"custom",label:"自定义适配器"}]} onChange={v=>{capRef.current.sourceType=v;setSelectedDeliverySourceType(v);}}/></div>
              {selectedDeliverySourceType === "custom" ? (
                <>
                  <div className="sys-field-row">
                    <div className="sys-field"><label className="sys-field-label sys-field-label--required">实现标识</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="custom-oa-delivery" maxLength={120} defaultValue={capRef.current.implementationKey || ""} onChange={e=>{capRef.current.implementationKey=e.target.value;}}/></div></div>
                    <div className="sys-field"><label className="sys-field-label">协议</label><SysSelect icon={ServerCog} placeholder="请选择协议" defaultValue={capRef.current.protocol || "http"} options={[{value:"http",label:"HTTP 适配器"}]} onChange={v=>{capRef.current.protocol=v;}}/></div>
                  </div>
                  <div className="sys-field"><label className="sys-field-label sys-field-label--required">Manifest 路径</label><div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="capabilities/delivery/custom-oa-delivery/manifest.yaml" maxLength={500} defaultValue={capRef.current.manifestPath || ""} onChange={e=>{capRef.current.manifestPath=e.target.value;}}/></div></div>
                  <div className="sys-field"><label className="sys-field-label">调用入口</label><div className="sys-field-input-wrap"><Globe size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="http://localhost:19090/delivery" maxLength={500} defaultValue={capRef.current.endpointUrl || ""} onChange={e=>{capRef.current.endpointUrl=e.target.value;}}/></div><div className="sys-field-hint">后续运行网关会基于 Manifest 协议和此入口调用外部适配器。</div></div>
                </>
              ) : (
                <>
                  <div className="sys-field">
                    <label className="sys-field-label">系统内置类型</label>
                    <SysSelect icon={FileText} placeholder="请选择系统内置交付类型" defaultValue={capRef.current.deliveryChannel || "email"} options={[{value:"email",label:"邮件交付"},{value:"document",label:"Word 文档交付"},{value:"excel",label:"Excel 工作簿交付"}]} onChange={v=>{capRef.current.deliveryChannel=v;setSelectedDeliveryChannel(v);}}/>
                  </div>
                  {selectedDeliveryChannel === "document" ? (
                    <>
                      <DocumentDeliveryStyleAdminSections
                        values={capRef.current}
                        onChange={(key, value) => {
                          capRef.current[key] = value;
                        }}
                        SelectField={({ label, icon, defaultValue, value, placeholder, options, onChange }) => (
                          <div className="sys-field">
                            <label className="sys-field-label">{label}</label>
                            <SysSelect
                              icon={icon}
                              placeholder={placeholder}
                              defaultValue={value ?? defaultValue}
                              options={options}
                              onChange={onChange}
                            />
                          </div>
                        )}
                      />
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label">最大文件 MB</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" type="number" min={1} max={200} placeholder="20" defaultValue={capRef.current.documentMaxFileSizeMb || "20"} onChange={e=>{capRef.current.documentMaxFileSizeMb=e.target.value;}}/></div><div className="sys-field-hint">运行时生成 docx 后会按此大小拦截，超过限制则交付失败。</div></div>
                        <div className="sys-field"><label className="sys-field-label">文件保存时间</label><SysSelect icon={Clock} defaultValue={capRef.current.documentRetentionPolicy || "days"} options={[{value:"permanent",label:"永久"},{value:"days",label:"按天保存"}]} onChange={v=>{capRef.current.documentRetentionPolicy=v;setSelectedDocumentRetentionPolicy(v);}}/>{selectedDocumentRetentionPolicy === "days" ? <><div className="sys-field-input-wrap mt-3"><Clock size={16} className="sys-field-prefix"/><input className="sys-field-input" type="number" min={1} max={3650} placeholder="180" defaultValue={capRef.current.documentRetentionDays || "180"} onChange={e=>{capRef.current.documentRetentionDays=e.target.value;}}/></div><div className="sys-field-hint">到期后由对象清理任务删除文档。</div></> : <div className="sys-field-hint">永久保存，不写入过期时间。</div>}</div>
                      </div>
                    </>
                  ) : selectedDeliveryChannel === "excel" ? (
                    <div className="sys-field-row">
                      <div className="sys-field"><label className="sys-field-label">最大文件 MB</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" type="number" min={1} max={200} placeholder="20" defaultValue={capRef.current.documentMaxFileSizeMb || "20"} onChange={e=>{capRef.current.documentMaxFileSizeMb=e.target.value;}}/></div><div className="sys-field-hint">运行时生成 xlsx 后会按此大小拦截，超过限制则交付失败。</div></div>
                      <div className="sys-field"><label className="sys-field-label">文件保存时间</label><SysSelect icon={Clock} defaultValue={capRef.current.documentRetentionPolicy || "days"} options={[{value:"permanent",label:"永久"},{value:"days",label:"按天保存"}]} onChange={v=>{capRef.current.documentRetentionPolicy=v;setSelectedDocumentRetentionPolicy(v);}}/>{selectedDocumentRetentionPolicy === "days" ? <><div className="sys-field-input-wrap mt-3"><Clock size={16} className="sys-field-prefix"/><input className="sys-field-input" type="number" min={1} max={3650} placeholder="180" defaultValue={capRef.current.documentRetentionDays || "180"} onChange={e=>{capRef.current.documentRetentionDays=e.target.value;}}/></div><div className="sys-field-hint">到期后由对象清理任务删除文件。</div></> : <div className="sys-field-hint">永久保存，不写入过期时间。</div>}</div>
                    </div>
                  ) : (
                    <>
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label sys-field-label--required">SMTP 主机</label><div className="sys-field-input-wrap"><ServerCog size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="localhost" maxLength={200} defaultValue={capRef.current.smtpHost || ""} onChange={e=>{capRef.current.smtpHost=e.target.value;}}/></div></div>
                        <div className="sys-field"><label className="sys-field-label sys-field-label--required">SMTP 端口</label><div className="sys-field-input-wrap"><Hash size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="1025" maxLength={10} defaultValue={capRef.current.smtpPort || ""} onChange={e=>{capRef.current.smtpPort=e.target.value;}}/></div></div>
                      </div>
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label">SMTP 账号</label><div className="sys-field-input-wrap"><User size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="可选" maxLength={200} defaultValue={capRef.current.smtpUsername || ""} onChange={e=>{capRef.current.smtpUsername=e.target.value;}}/></div></div>
                        <div className="sys-field"><label className="sys-field-label">SMTP 密码</label><div className="sys-field-input-wrap"><KeyRound size={16} className="sys-field-prefix"/><input className="sys-field-input" type="password" placeholder={capRef.current.smtpPasswordConfigured === "true" ? "已配置，留空则保持不变" : "可选"} maxLength={1000} onChange={e=>{capRef.current.smtpPassword=e.target.value;}}/></div></div>
                      </div>
                      <div className="sys-field-row">
                        <div className="sys-field"><label className="sys-field-label sys-field-label--required">发件邮箱</label><div className="sys-field-input-wrap"><Mail size={16} className="sys-field-prefix"/><input className="sys-field-input" placeholder="agentum@example.test" maxLength={320} defaultValue={capRef.current.fromAddress || ""} onChange={e=>{capRef.current.fromAddress=e.target.value;}}/></div></div>
                        <div className="sys-field"><label className="sys-field-label">TLS</label><SysSelect icon={ShieldCheck} placeholder="请选择 TLS 设置" defaultValue={capRef.current.useTls || "false"} options={[{value:"false",label:"关闭"},{value:"true",label:"启用"}]} onChange={v=>{capRef.current.useTls=v;}}/></div>
                      </div>
                    </>
                  )}
                </>
              )}
            </>
          )}
        </div>
        <div className="sys-drawer-footer">
          <button className="sys-btn sys-btn--default" onClick={()=>{setCapModalOpen(false);setEditingCapability(null);}}><X size={14}/> 取消</button>
          <div className="sys-drawer-footer-right">
            {selectedCapabilityType === "mcp" ? (
              <button className="sys-btn sys-btn--default" onClick={()=>void testMcpDraftConnection()}><PlayCircle size={14}/> 测试当前配置</button>
            ) : null}
            <button className="sys-btn sys-btn--primary" onClick={()=>void submitCapability()}><PlusCircle size={14}/> {editingCapability ? "保存修改" : "确认注册"}</button>
          </div>
        </div>
      </Drawer>
    </>
  );
}
