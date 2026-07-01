import type { BootstrapAdminRequest, BootstrapStatusResponse, LoginRequest, LoginResponse, MeResponse, PortalType, SsoProviderOption, SwitchRoleRequest, SwitchRoleResponse, TenantOption } from "../types/auth";
import type { AssetSummary, CreateMyAssetRequest, MyAssetDetail, MyAssetPage, MyAssetRow, ShareableMemberRow, SystemCapabilityAssetPage, UpdateMyAssetAccessRequest, UpdateMyAssetRequest } from "../types/asset";
import type { AuditEvidence, AuditOperationLog, AuditRunSummary, AuditToolCall } from "../types/audit";
import type {
  CreateDepartmentRequest,
  CreateMemberRequest,
  CreatePageGrantRequest,
  CreateResourceGrantRequest,
  CreateTenantRoleRequest,
  CreateTenantOrgRoleRequest,
  MemberImportResult,
  PageResponse,
  PageGrant,
  PrincipalGrantUsage,
  ResourceGrant,
  TenantOrgRole,
  TenantOrganizationOverview,
  TenantResourceOption,
  UpdateMembershipDepartmentRequest,
  UpdateMemberProfileRequest,
  UpdateMembershipRoleRequest,
  UpdateMembershipStatusRequest,
  UpdateDepartmentRequest,
  UpdateDepartmentStatusRequest,
  UpdateRoleStatusRequest,
  UpdateTenantOrgRoleRequest,
  UpdateTenantRoleRequest,
} from "../types/organization";
import type {
  CreateTenantRequest,
  CreateTenantAdminRequest,
  CreateModelProviderRequest,
  CreateSystemCapabilityRequest,
  CreateTenantCapabilityGrantRequest,
  CreateTenantModelAssignmentRequest,
  CapabilityTestResult,
  ModelProviderRow,
  ModelProviderPage,
  ModelProviderTestResult,
  ModelProviderTypeRow,
  SaveTenantSsoProviderRequest,
  SystemCapabilityPage,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantPage,
  SystemTenantRow,
  TenantSsoProviderRow,
  TenantCapabilityGrantRow,
  TenantModelAssignmentRow,
  UpdateTenantCapabilityGrantStatusRequest,
  UpdateModelProviderRequest,
  UpdateSystemCapabilityRequest,
  UpdateTenantModelAssignmentStatusRequest,
  UpdateTenantStatusRequest,
  UpdateTenantAdminProfileRequest,
  UpdateTenantAdminStatusRequest,
} from "../types/system";
import type {
  CreateWorkflowDraftRequest,
  WorkflowDesignerCatalog,
  WorkflowDraftDetail,
  WorkflowEdgeDraft,
  WorkflowNodeDraft,
  WorkflowDraftRow,
  WorkflowPublishResult,
  WorkflowPublishValidationResult,
  WorkflowShareableMemberRow,
  FileDownloadResponse,
  UpdateWorkflowAccessRequest,
  UpdateWorkflowDraftRequest,
  WorkflowVariableDraft,
} from "../types/workflow-contract";
import type {
  WorkbenchAvailableWorkflowPage,
  WorkbenchAvailableWorkflowPreview,
  WorkbenchRunDetail,
  WorkbenchSummary,
  WorkbenchTaskRunPage,
} from "../types/workbench";

type ApiEnvelope<T> = {
  success: boolean;
  data: T | null;
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
  } | null;
  requestId: string;
};

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  token?: string | null;
  skipAuthRefresh?: boolean;
};

function normalizeApiBaseUrl(value: string | undefined): string {
  const baseUrl = value?.trim() ?? "";
  if (!baseUrl || baseUrl === "/") {
    return "";
  }
  const withoutTrailingSlash = baseUrl.replace(/\/+$/, "");
  // 生产同域部署由 Nginx 代理 /api，接口路径本身已经带 /api 前缀，避免配置成 /api 后请求到 /api/api。
  return withoutTrailingSlash === "/api" ? "" : withoutTrailingSlash;
}

export const API_BASE_URL = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

export class AgentumApiError extends Error {
  readonly code: string;
  readonly requestId: string;
  readonly details?: Record<string, unknown>;

  constructor(message: string, code: string, requestId: string, details?: Record<string, unknown>) {
    super(message);
    this.name = "AgentumApiError";
    this.code = code;
    this.requestId = requestId;
    this.details = details;
  }
}

type AuthSessionBridge = {
  getAccessToken: () => string | null;
  onRefreshed: (response: LoginResponse) => void;
  onExpired: () => void;
};

let authSessionBridge: AuthSessionBridge | null = null;
let refreshRequest: Promise<LoginResponse> | null = null;

export function configureAuthSessionBridge(bridge: AuthSessionBridge): void {
  authSessionBridge = bridge;
}

async function refreshAccessToken(): Promise<LoginResponse> {
  if (!refreshRequest) {
    refreshRequest = fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: "POST",
      credentials: "include",
    })
      .then(async (response) => {
        const envelope = (await response.json()) as ApiEnvelope<LoginResponse>;
        if (!response.ok || !envelope.success || !envelope.data) {
          throw new AgentumApiError(
            envelope.error?.message ?? "登录状态已失效，请重新登录",
            envelope.error?.code ?? "AUTH_REFRESH_TOKEN_INVALID",
            envelope.requestId,
          );
        }
        authSessionBridge?.onRefreshed(envelope.data);
        return envelope.data;
      })
      .catch((error) => {
        authSessionBridge?.onExpired();
        throw error;
      })
      .finally(() => {
        refreshRequest = null;
      });
  }
  return refreshRequest;
}

async function resolveAccessTokenAfterUnauthorized(rejectedToken: string): Promise<string> {
  const currentToken = authSessionBridge?.getAccessToken();
  if (currentToken && currentToken !== rejectedToken) {
    return currentToken;
  }
  return (await refreshAccessToken()).token;
}

async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);

  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }

  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }

  let response: Response;

  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      credentials: "include",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch (error) {
    // API client 是前端统一出入口，诊断日志只记录路径和错误摘要，禁止打印 body、Authorization 或 token。
    console.error("[api] 请求异常", { path, message: error instanceof Error ? error.message : "unknown" });
    throw error;
  }

  let envelope: ApiEnvelope<T>;

  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch (error) {
    console.error("[api] 响应解析失败", { path, status: response.status, message: error instanceof Error ? error.message : "unknown" });
    throw new AgentumApiError("后端响应格式不正确，请稍后重试", "SYSTEM_RESPONSE_INVALID", response.headers.get("X-Request-Id") ?? "req_unknown");
  }

  if (!response.ok || !envelope.success) {
    if (response.status === 401 && options.token && !options.skipAuthRefresh && canRefreshSession(path)) {
      const accessToken = await resolveAccessTokenAfterUnauthorized(options.token);
      return apiRequest<T>(path, { ...options, token: accessToken, skipAuthRefresh: true });
    }
    console.warn("[api] 请求失败", { path, status: response.status, code: envelope.error?.code, requestId: envelope.requestId });
    throw new AgentumApiError(
      envelope.error?.message ?? "请求失败，请稍后重试",
      envelope.error?.code ?? "SYSTEM_REQUEST_FAILED",
      envelope.requestId,
      envelope.error?.details,
    );
  }

  if (envelope.data === null) {
    return undefined as T;
  }

  return envelope.data;
}

function canRefreshSession(path: string): boolean {
  return !["/api/auth/bootstrap-status", "/api/auth/bootstrap", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"].includes(path);
}

async function apiFileRequest(path: string, options: RequestOptions = {}): Promise<FileDownloadResponse> {
  const headers = new Headers(options.headers);

  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }

  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      credentials: "include",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch (error) {
    console.error("[api] 文件请求异常", { path, message: error instanceof Error ? error.message : "unknown" });
    throw error;
  }

  if (!response.ok) {
    if (response.status === 401 && options.token && !options.skipAuthRefresh) {
      const accessToken = await resolveAccessTokenAfterUnauthorized(options.token);
      return apiFileRequest(path, { ...options, token: accessToken, skipAuthRefresh: true });
    }
    let message = "文件下载失败，请稍后重试";
    let code = "SYSTEM_FILE_REQUEST_FAILED";
    let requestId = response.headers.get("X-Request-Id") ?? "req_unknown";
    try {
      const envelope = (await response.json()) as ApiEnvelope<unknown>;
      message = envelope.error?.message ?? message;
      code = envelope.error?.code ?? code;
      requestId = envelope.requestId ?? requestId;
    } catch (error) {
      console.warn("[api] 文件错误响应解析失败", { path, status: response.status, message: error instanceof Error ? error.message : "unknown" });
    }
    console.warn("[api] 文件请求失败", { path, status: response.status, code, requestId });
    throw new AgentumApiError(message, code, requestId);
  }

  const blob = await response.blob();
  return {
    blob,
    fileName: resolveFileName(response.headers.get("Content-Disposition")) || "交付文档.docx",
  };
}

async function apiUploadRequest<T>(path: string, formData: FormData, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);

  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      method: options.method ?? "POST",
      credentials: "include",
      headers,
      body: formData,
    });
  } catch (error) {
    console.error("[api] 上传请求异常", { path, message: error instanceof Error ? error.message : "unknown" });
    throw error;
  }

  let envelope: ApiEnvelope<T>;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch (error) {
    console.error("[api] 上传响应解析失败", { path, status: response.status, message: error instanceof Error ? error.message : "unknown" });
    throw new AgentumApiError("后端响应格式不正确，请稍后重试", "SYSTEM_RESPONSE_INVALID", response.headers.get("X-Request-Id") ?? "req_unknown");
  }

  if (!response.ok || !envelope.success) {
    if (response.status === 401 && options.token && !options.skipAuthRefresh && canRefreshSession(path)) {
      const accessToken = await resolveAccessTokenAfterUnauthorized(options.token);
      return apiUploadRequest<T>(path, formData, { ...options, token: accessToken, skipAuthRefresh: true });
    }
    console.warn("[api] 上传请求失败", { path, status: response.status, code: envelope.error?.code, requestId: envelope.requestId });
    throw new AgentumApiError(
      envelope.error?.message ?? "上传失败，请稍后重试",
      envelope.error?.code ?? "SYSTEM_UPLOAD_FAILED",
      envelope.requestId,
      envelope.error?.details,
    );
  }

  if (envelope.data === null) {
    return undefined as T;
  }
  return envelope.data;
}

function resolveFileName(contentDisposition: string | null): string {
  if (!contentDisposition) {
    return "";
  }
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }
  const quotedMatch = contentDisposition.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) {
    return quotedMatch[1];
  }
  const plainMatch = contentDisposition.match(/filename=([^;]+)/i);
  return plainMatch?.[1]?.trim() ?? "";
}

export const authApi = {
  bootstrapStatus: () => apiRequest<BootstrapStatusResponse>("/api/auth/bootstrap-status", { skipAuthRefresh: true }),
  bootstrapAdmin: (request: BootstrapAdminRequest) =>
    apiRequest<void>("/api/auth/bootstrap", { method: "POST", body: request, skipAuthRefresh: true }),
  listTenants: () => apiRequest<TenantOption[]>("/api/public/tenants"),
  listSsoProviders: (tenantId: string) => apiRequest<SsoProviderOption[]>(`/api/public/tenants/${tenantId}/sso-providers`),
  ssoAuthorizeUrl: (tenantId: string, providerId: string, portal: PortalType) => {
    const params = new URLSearchParams({ tenantId, providerId, portal });
    return `${API_BASE_URL}/api/auth/sso/authorize?${params.toString()}`;
  },
  login: (request: LoginRequest) => apiRequest<LoginResponse>("/api/auth/login", { method: "POST", body: request }),
  refresh: () => apiRequest<LoginResponse>("/api/auth/refresh", { method: "POST", skipAuthRefresh: true }),
  me: (token: string) => apiRequest<MeResponse>("/api/auth/me", { token }),
  switchRole: (token: string, request: SwitchRoleRequest) =>
    apiRequest<SwitchRoleResponse>("/api/auth/switch-role", { method: "PUT", token, body: request }),
  logout: (_token?: string) => apiRequest<void>("/api/auth/logout", { method: "POST" }),
};

export const organizationApi = {
  overview: (tenantId: string, token: string) => apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/overview`, { token }),
  listOrgRoles: (tenantId: string, token: string, page = 1, size = 10, sort = "updatedAt,desc") =>
    apiRequest<PageResponse<TenantOrgRole>>(
      `/api/admin/tenants/${tenantId}/organization/org-roles?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`,
      { token }
    ),
  listResourceOptions: (tenantId: string, token: string) =>
    apiRequest<TenantResourceOption[]>(`/api/admin/tenants/${tenantId}/organization/resource-options`, { token }),
  listPageGrants: (tenantId: string, token: string) =>
    apiRequest<PageGrant[]>(`/api/admin/tenants/${tenantId}/organization/page-grants`, { token }),
  createPageGrant: (tenantId: string, token: string, request: CreatePageGrantRequest) =>
    apiRequest<PageGrant>(`/api/admin/tenants/${tenantId}/organization/page-grants`, { method: "POST", token, body: request }),
  updatePageGrant: (tenantId: string, grantGroupId: string, token: string, request: CreatePageGrantRequest) =>
    apiRequest<PageGrant>(`/api/admin/tenants/${tenantId}/organization/page-grants/${grantGroupId}`, { method: "PUT", token, body: request }),
  deletePageGrant: (tenantId: string, grantId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/page-grants/${grantId}`, { method: "DELETE", token }),
  createMember: (tenantId: string, token: string, request: CreateMemberRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/members`, { method: "POST", token, body: request }),
  downloadMemberImportTemplate: (tenantId: string, token: string) =>
    apiFileRequest(`/api/admin/tenants/${tenantId}/organization/members/import-template`, { token }),
  importMembers: (tenantId: string, token: string, file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return apiUploadRequest<MemberImportResult>(`/api/admin/tenants/${tenantId}/organization/members/import`, formData, { token });
  },
  createDepartment: (tenantId: string, token: string, request: CreateDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments`, { method: "POST", token, body: request }),
  updateDepartment: (tenantId: string, departmentId: string, token: string, request: UpdateDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments/${departmentId}`, { method: "PATCH", token, body: request }),
  updateDepartmentStatus: (tenantId: string, departmentId: string, token: string, request: UpdateDepartmentStatusRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments/${departmentId}/status`, { method: "PATCH", token, body: request }),
  principalGrantUsage: (tenantId: string, principalType: "role" | "department" | "user", principalId: string, token: string) =>
    apiRequest<PrincipalGrantUsage>(
      `/api/admin/tenants/${tenantId}/organization/principals/${principalType}/${principalId}/grant-usage`,
      { token }
    ),
  deleteDepartment: (tenantId: string, departmentId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/departments/${departmentId}`, { method: "DELETE", token }),
  createRole: (tenantId: string, token: string, request: CreateTenantRoleRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/roles`, { method: "POST", token, body: request }),
  updateRole: (tenantId: string, roleId: string, token: string, request: UpdateTenantRoleRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/roles/${roleId}`, { method: "PATCH", token, body: request }),
  updateRoleStatus: (tenantId: string, roleId: string, token: string, request: UpdateRoleStatusRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/roles/${roleId}/status`, { method: "PATCH", token, body: request }),
  deleteRole: (tenantId: string, roleId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/roles/${roleId}`, { method: "DELETE", token }),
  listResourceGrants: (tenantId: string, token: string) =>
    apiRequest<ResourceGrant[]>(`/api/admin/tenants/${tenantId}/organization/resource-grants`, { token }),
  createResourceGrant: (tenantId: string, token: string, request: CreateResourceGrantRequest) =>
    apiRequest<ResourceGrant>(`/api/admin/tenants/${tenantId}/organization/resource-grants`, { method: "POST", token, body: request }),
  updateResourceGrant: (tenantId: string, grantGroupId: string, token: string, request: CreateResourceGrantRequest) =>
    apiRequest<ResourceGrant>(`/api/admin/tenants/${tenantId}/organization/resource-grants/${grantGroupId}`, { method: "PUT", token, body: request }),
  deleteResourceGrant: (tenantId: string, grantId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/resource-grants/${grantId}`, { method: "DELETE", token }),
  createOrgRole: (tenantId: string, token: string, request: CreateTenantOrgRoleRequest) =>
    apiRequest<TenantOrgRole>(`/api/admin/tenants/${tenantId}/organization/org-roles`, { method: "POST", token, body: request }),
  updateOrgRole: (tenantId: string, roleId: string, token: string, request: UpdateTenantOrgRoleRequest) =>
    apiRequest<TenantOrgRole>(`/api/admin/tenants/${tenantId}/organization/org-roles/${roleId}`, { method: "PATCH", token, body: request }),
  updateMembershipRole: (tenantId: string, membershipId: string, token: string, request: UpdateMembershipRoleRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/memberships/${membershipId}/role`, {
      method: "PATCH",
      token,
      body: request,
    }),
  updateMemberProfile: (tenantId: string, membershipId: string, token: string, request: UpdateMemberProfileRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/memberships/${membershipId}/profile`, {
      method: "PATCH",
      token,
      body: request,
    }),
  updateMembershipDepartment: (tenantId: string, membershipId: string, token: string, request: UpdateMembershipDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/memberships/${membershipId}/department`, {
      method: "PATCH",
      token,
      body: request,
    }),
  updateMembershipStatus: (tenantId: string, membershipId: string, token: string, request: UpdateMembershipStatusRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/memberships/${membershipId}/status`, {
      method: "PATCH",
      token,
      body: request,
    }),
};

export const systemApi = {
  summary: (token: string) => apiRequest<SystemSummary>("/api/system/summary", { token }),
  listTenants: (token: string, page = 1, size = 10, sort = "createdAt,desc") =>
    apiRequest<SystemTenantPage>(`/api/system/tenants?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`, { token }),
  createTenant: (token: string, body: CreateTenantRequest) =>
    apiRequest<SystemTenantRow>("/api/system/tenants", { method: "POST", token, body }),
  updateTenantStatus: (tenantId: string, token: string, body: UpdateTenantStatusRequest) =>
    apiRequest<SystemTenantRow>(`/api/system/tenants/${tenantId}/status`, { method: "PATCH", token, body }),
  createTenantAdmin: (tenantId: string, token: string, body: CreateTenantAdminRequest) =>
    apiRequest<void>(`/api/system/tenants/${tenantId}/admins`, { method: "POST", token, body }),
  updateTenantAdminProfile: (tenantId: string, membershipId: string, token: string, body: UpdateTenantAdminProfileRequest) =>
    apiRequest<void>(`/api/system/tenants/${tenantId}/admins/${membershipId}/profile`, { method: "PATCH", token, body }),
  updateTenantAdminStatus: (tenantId: string, membershipId: string, token: string, body: UpdateTenantAdminStatusRequest) =>
    apiRequest<void>(`/api/system/tenants/${tenantId}/admins/${membershipId}/status`, { method: "PATCH", token, body }),
  listTenantSsoProviders: (tenantId: string, token: string) =>
    apiRequest<TenantSsoProviderRow[]>(`/api/system/tenants/${tenantId}/sso-providers`, { token }),
  saveTenantSsoProvider: (tenantId: string, token: string, body: SaveTenantSsoProviderRequest) =>
    apiRequest<TenantSsoProviderRow>(`/api/system/tenants/${tenantId}/sso-providers`, { method: "POST", token, body }),
  listModelProviderTypes: (token: string) => apiRequest<ModelProviderTypeRow[]>("/api/system/model-provider-types", { token }),
  listModelProviders: (token: string, page = 1, size = 10, sort = "createdAt,desc") =>
    apiRequest<ModelProviderPage>(`/api/system/model-providers?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`, { token }),
  createModelProvider: (token: string, body: CreateModelProviderRequest) =>
    apiRequest<ModelProviderRow>("/api/system/model-providers", { method: "POST", token, body }),
  updateModelProvider: (token: string, providerId: string, body: UpdateModelProviderRequest) =>
    apiRequest<ModelProviderRow>(`/api/system/model-providers/${providerId}`, { method: "PATCH", token, body }),
  deleteModelProvider: (token: string, providerId: string) =>
    apiRequest<void>(`/api/system/model-providers/${providerId}`, { method: "DELETE", token }),
  testModelProvider: (token: string, providerId: string) =>
    apiRequest<ModelProviderTestResult>(`/api/system/model-providers/${providerId}/test`, { method: "POST", token }),
  listCapabilities: (token: string, page = 1, size = 10, sort = "createdAt,desc") =>
    apiRequest<SystemCapabilityPage>(`/api/system/capabilities?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`, { token }),
  createCapability: (token: string, body: CreateSystemCapabilityRequest) =>
    apiRequest<SystemCapabilityRow>("/api/system/capabilities", { method: "POST", token, body }),
  updateCapability: (token: string, capabilityId: string, body: UpdateSystemCapabilityRequest) =>
    apiRequest<SystemCapabilityRow>(`/api/system/capabilities/${capabilityId}`, { method: "PATCH", token, body }),
  deleteCapability: (token: string, capabilityId: string) =>
    apiRequest<void>(`/api/system/capabilities/${capabilityId}`, { method: "DELETE", token }),
  testCapability: (token: string, capabilityId: string) =>
    apiRequest<CapabilityTestResult>(`/api/system/capabilities/${capabilityId}/test`, { method: "POST", token }),
  listGrants: (token: string, tenantId?: string) => {
    const q = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : "";
    return apiRequest<TenantCapabilityGrantRow[]>(`/api/system/tenant-capability-grants${q}`, { token });
  },
  createGrant: (token: string, body: CreateTenantCapabilityGrantRequest) =>
    apiRequest<TenantCapabilityGrantRow>("/api/system/tenant-capability-grants", { method: "POST", token, body }),
  updateGrantStatus: (token: string, grantId: string, body: UpdateTenantCapabilityGrantStatusRequest) =>
    apiRequest<TenantCapabilityGrantRow>(`/api/system/tenant-capability-grants/${grantId}/status`, { method: "PATCH", token, body }),
  listTenantModelAssignments: (token: string, tenantId: string) =>
    apiRequest<TenantModelAssignmentRow[]>(`/api/system/tenant-model-assignments?tenantId=${encodeURIComponent(tenantId)}`, { token }),
  createTenantModelAssignment: (token: string, body: CreateTenantModelAssignmentRequest) =>
    apiRequest<TenantModelAssignmentRow>("/api/system/tenant-model-assignments", { method: "POST", token, body }),
  updateTenantModelAssignmentStatus: (token: string, assignmentId: string, body: UpdateTenantModelAssignmentStatusRequest) =>
    apiRequest<TenantModelAssignmentRow>(`/api/system/tenant-model-assignments/${assignmentId}/status`, { method: "PATCH", token, body }),
};

export const assetApi = {
  summary: (tenantId: string, token: string) => apiRequest<AssetSummary>(`/api/tenants/${tenantId}/assets/summary`, { token }),
  listShareableMembers: (tenantId: string, token: string) =>
    apiRequest<ShareableMemberRow[]>(`/api/tenants/${tenantId}/assets/shareable-members`, { token }),
  listSystemCapabilities: (tenantId: string, token: string, page = 1, size = 10, sort = "openedAt,desc", assetType = "", keyword = "") => {
    const params = new URLSearchParams({ page: String(page), size: String(size), sort });
    if (assetType) params.set("assetType", assetType);
    if (keyword) params.set("keyword", keyword);
    return apiRequest<SystemCapabilityAssetPage>(
      `/api/tenants/${tenantId}/assets/system-capabilities?${params.toString()}`,
      { token }
    );
  },
  listMine: (tenantId: string, token: string, keyword = "", page = 1, size = 10, sort = "updatedAt,desc", assetType = "", status = "") => {
    const params = new URLSearchParams({ keyword, page: String(page), size: String(size), sort });
    if (assetType) params.set("assetType", assetType);
    if (status) params.set("status", status);
    return apiRequest<MyAssetPage>(`/api/tenants/${tenantId}/assets/mine?${params.toString()}`, { token });
  },
  createMine: (tenantId: string, token: string, body: CreateMyAssetRequest) =>
    apiRequest<MyAssetRow>(`/api/tenants/${tenantId}/assets/mine`, { method: "POST", token, body }),
  getMine: (tenantId: string, token: string, assetId: string) =>
    apiRequest<MyAssetDetail>(`/api/tenants/${tenantId}/assets/mine/${assetId}`, { token }),
  updateMine: (tenantId: string, token: string, assetId: string, body: UpdateMyAssetRequest) =>
    apiRequest<MyAssetDetail>(`/api/tenants/${tenantId}/assets/mine/${assetId}`, { method: "PATCH", token, body }),
  publishMine: (tenantId: string, token: string, assetId: string) =>
    apiRequest<MyAssetDetail>(`/api/tenants/${tenantId}/assets/mine/${assetId}/publish`, { method: "POST", token }),
  revertMineToDraft: (tenantId: string, token: string, assetId: string) =>
    apiRequest<MyAssetDetail>(`/api/tenants/${tenantId}/assets/mine/${assetId}/revert-to-draft`, { method: "POST", token }),
  updateMineAccess: (tenantId: string, token: string, assetId: string, body: UpdateMyAssetAccessRequest) =>
    apiRequest<MyAssetDetail>(`/api/tenants/${tenantId}/assets/mine/${assetId}/access`, { method: "PATCH", token, body }),
  deleteMine: (tenantId: string, token: string, assetId: string) =>
    apiRequest<void>(`/api/tenants/${tenantId}/assets/mine/${assetId}`, { method: "DELETE", token }),
};

export const workbenchApi = {
  // 业务工作台概览：返回真实统计、待办和最近任务运行。
  summary: (tenantId: string, token: string) =>
    apiRequest<WorkbenchSummary>(`/api/tenants/${tenantId}/workbench/summary`, { token }),
  // 已发布工作流：按“有冻结版本且入口未收回”展示全部流程，并由 canLaunch 区分当前账号能否发起。
  listAvailableWorkflows: (
    tenantId: string,
    token: string,
    keyword = "",
    page = 1,
    size = 12,
    sort = "updatedAt,desc",
  ) => {
    const params = new URLSearchParams({ keyword, page: String(page), size: String(size), sort });
    return apiRequest<WorkbenchAvailableWorkflowPage>(
      `/api/tenants/${tenantId}/workbench/available-workflows?${params.toString()}`,
      { token },
    );
  },
  getAvailableWorkflowPreview: (tenantId: string, token: string, workflowId: string) =>
    apiRequest<WorkbenchAvailableWorkflowPreview>(
      `/api/tenants/${tenantId}/workbench/available-workflows/${workflowId}/preview`,
      { token },
    ),
  createRun: (tenantId: string, token: string, workflowId: string, title: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs`, {
      method: "POST",
      token,
      body: { workflowId, title },
    }),
  listActiveRuns: (
    tenantId: string,
    token: string,
    keyword = "",
    page = 1,
    size = 10,
    sort = "updatedAt,desc",
    state = "",
  ) => {
    const params = new URLSearchParams({ keyword, page: String(page), size: String(size), sort });
    if (state) {
      params.set("state", state);
    }
    return apiRequest<WorkbenchTaskRunPage>(`/api/tenants/${tenantId}/workbench/active-runs?${params.toString()}`, { token });
  },
  listRuns: (
    tenantId: string,
    token: string,
    keyword = "",
    page = 1,
    size = 10,
    sort = "updatedAt,desc",
  ) => {
    const params = new URLSearchParams({ keyword, page: String(page), size: String(size), sort });
    return apiRequest<WorkbenchTaskRunPage>(`/api/tenants/${tenantId}/workbench/runs?${params.toString()}`, { token });
  },
  getRun: (tenantId: string, token: string, runId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}`, { token }),
  saveRun: (tenantId: string, token: string, runId: string, title?: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/save`, {
      method: "POST",
      token,
      body: title ? { title } : {},
    }),
  deleteRun: (tenantId: string, token: string, runId: string) =>
    apiRequest<void>(`/api/tenants/${tenantId}/workbench/runs/${runId}`, { method: "DELETE", token }),
  rollbackRun: (tenantId: string, token: string, runId: string, nodeRunId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/rollback`, {
      method: "POST",
      token,
      body: { nodeRunId },
    }),
  completeTodo: (tenantId: string, token: string, todoId: string, comment: string, payload: Record<string, unknown> = {}) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/todos/${todoId}/complete`, {
      method: "POST",
      token,
      body: { action: "complete", comment, payload },
    }),
  advanceStep: (tenantId: string, token: string, runId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/advance`, {
      method: "POST",
      token,
    }),
  interruptRun: (tenantId: string, token: string, runId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/interrupt`, {
      method: "POST",
      token,
    }),
  // 主动「重新执行」：清空节点全部数据后从头重跑整个节点（中断后的整步重做）。
  restartNode: (tenantId: string, token: string, runId: string, nodeRunId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/restart`, {
      method: "POST",
      token,
    }),
  // 被动「恢复进度」：保留已成功子智能体结果，仅重跑失败/未完成部分。
  recoverNode: (tenantId: string, token: string, runId: string, nodeRunId: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/recover`, {
      method: "POST",
      token,
    }),
  downloadDeliveryRecord: (tenantId: string, token: string, recordId: string) =>
    apiFileRequest(`/api/tenants/${tenantId}/delivery-records/${recordId}/download`, { token }),
  followUpNode: (tenantId: string, token: string, runId: string, nodeRunId: string, message: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/follow-up`, {
      method: "POST",
      token,
      body: { message },
    }),
  followUpClusterAgent: (tenantId: string, token: string, runId: string, nodeRunId: string, agentIndex: number, message: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/cluster-agents/${agentIndex}/follow-up`, {
      method: "POST",
      token,
      body: { message },
    }),
  updateFinalAnswer: (tenantId: string, token: string, runId: string, nodeRunId: string, content: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/final-answer`, {
      method: "POST",
      token,
      body: { content },
    }),
  updateClusterAgentFinalAnswer: (tenantId: string, token: string, runId: string, nodeRunId: string, agentIndex: number, content: string) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/runs/${runId}/nodes/${nodeRunId}/cluster-agents/${agentIndex}/final-answer`, {
      method: "POST",
      token,
      body: { content },
    }),
  completeTodoWithPayload: (tenantId: string, token: string, todoId: string, payload: Record<string, unknown>) =>
    apiRequest<WorkbenchRunDetail>(`/api/tenants/${tenantId}/workbench/todos/${todoId}/complete`, {
      method: "POST",
      token,
      body: { action: "complete", comment: "提交表单数据并继续下一步", payload },
    }),
};

export const workflowApi = {
  getDesignerCatalog: (tenantId: string, token: string) =>
    apiRequest<WorkflowDesignerCatalog>(`/api/tenants/${tenantId}/workflows/drafts/designer-catalog`, { token }),
  listShareableMembers: (tenantId: string, token: string) =>
    apiRequest<WorkflowShareableMemberRow[]>(`/api/tenants/${tenantId}/workflows/drafts/shareable-members`, { token }),
  listDrafts: (tenantId: string, token: string, page = 1, size = 10, keyword = "", scope: "all" | "mine" | "shared" = "all", status: "all" | "draft" | "published" | "review" = "all", sort = "updatedAt,desc") => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
      keyword,
      scope,
      status,
      sort,
    });
    return apiRequest<PageResponse<WorkflowDraftRow>>(`/api/tenants/${tenantId}/workflows/drafts?${params.toString()}`, { token });
  },
  createDraft: (tenantId: string, token: string, request: CreateWorkflowDraftRequest) =>
    apiRequest<WorkflowDraftRow>(`/api/tenants/${tenantId}/workflows/drafts`, { method: "POST", token, body: request }),
  copyDraft: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowDraftRow>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/copy`, { method: "POST", token }),
  getDraft: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}`, { token }),
  updateDraft: (tenantId: string, workflowId: string, token: string, request: UpdateWorkflowDraftRequest) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}`, { method: "PUT", token, body: request }),
  updateAccess: (tenantId: string, workflowId: string, token: string, request: UpdateWorkflowAccessRequest) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/access`, { method: "PUT", token, body: request }),
  validateForPublish: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowPublishValidationResult>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/publish-validation`, {
      method: "POST",
      token,
    }),
  publish: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowPublishResult>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/publish`, {
      method: "POST",
      token,
    }),
  recallLaunch: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/recall-launch`, {
      method: "POST",
      token,
    }),
  restoreLaunch: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/restore-launch`, {
      method: "POST",
      token,
    }),
  deleteDraft: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<void>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}`, { method: "DELETE", token }),
  saveGraph: (
    tenantId: string,
    workflowId: string,
    token: string,
    nodes: WorkflowNodeDraft[],
    edges: WorkflowEdgeDraft[],
    variables: WorkflowVariableDraft[],
  ) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}/graph`, {
      method: "PUT",
      token,
      body: { nodes, edges, variables },
    }),
};

export const auditApi = {
  listRuns: (tenantId: string, token: string, page: number, size: number, sort: string, keyword: string, state: string) => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
      sort,
      keyword,
      state,
    });
    return apiRequest<PageResponse<AuditRunSummary>>(`/api/tenants/${tenantId}/audit/runs?${params.toString()}`, { token });
  },
  getEvidence: (tenantId: string, runId: string, token: string) =>
    apiRequest<AuditEvidence>(`/api/tenants/${tenantId}/audit/runs/${runId}/evidence`, { token }),
  listToolCalls: (tenantId: string, token: string, page: number, size: number, sort: string, toolType: string, status: string, keyword: string) => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
      sort,
      toolType,
      status,
      keyword,
    });
    return apiRequest<PageResponse<AuditToolCall>>(`/api/tenants/${tenantId}/audit/tools?${params.toString()}`, { token });
  },
  listOperations: (tenantId: string, token: string, page: number, size: number, sort: string, actionType: string, operatorId?: string) => {
    const filterParams: Record<string, string> = {
      page: String(page),
      size: String(size),
      sort,
      actionType,
    };
    if (operatorId) {
      filterParams.operatorId = operatorId;
    }
    const params = new URLSearchParams(filterParams);
    return apiRequest<PageResponse<AuditOperationLog>>(`/api/tenants/${tenantId}/audit/operations?${params.toString()}`, { token });
  }
};
