import type { LoginRequest, LoginResponse, MeResponse, SwitchRoleRequest, SwitchRoleResponse, TenantOption } from "../types/auth";
import type {
  CreateDepartmentRequest,
  CreateMemberRequest,
  CreatePageGrantRequest,
  CreateResourceGrantRequest,
  CreateTenantRoleRequest,
  CreateTenantOrgRoleRequest,
  PageResponse,
  PageGrant,
  ResourceGrant,
  TenantOrgRole,
  TenantOrganizationOverview,
  TenantResourceOption,
  UpdateMembershipDepartmentRequest,
  UpdateMembershipRoleRequest,
  UpdateMembershipStatusRequest,
  UpdateDepartmentRequest,
  UpdateTenantOrgRoleRequest,
  UpdateTenantRoleRequest,
} from "../types/organization";
import type {
  CreateTenantRequest,
  CreateModelProviderRequest,
  CreateSystemCapabilityRequest,
  CreateTenantCapabilityGrantRequest,
  CreateTenantModelAssignmentRequest,
  CapabilityTestResult,
  ModelProviderRow,
  ModelProviderPage,
  ModelProviderTypeRow,
  SystemCapabilityPage,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantPage,
  SystemTenantRow,
  TenantCapabilityGrantRow,
  TenantModelAssignmentRow,
  UpdateTenantCapabilityGrantStatusRequest,
  UpdateTenantStatusRequest,
} from "../types/system";
import type {
  CreateWorkflowDraftRequest,
  WorkflowDraftDetail,
  WorkflowEdgeDraft,
  WorkflowNodeDraft,
  WorkflowDraftRow,
  WorkflowPublishResult,
  WorkflowPublishValidationResult,
  WorkflowVariableDraft,
} from "../types/workflow-contract";

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
};

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export class AgentumApiError extends Error {
  readonly code: string;
  readonly requestId: string;

  constructor(message: string, code: string, requestId: string) {
    super(message);
    this.name = "AgentumApiError";
    this.code = code;
    this.requestId = requestId;
  }
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
    console.warn("[api] 请求失败", { path, status: response.status, code: envelope.error?.code, requestId: envelope.requestId });
    throw new AgentumApiError(envelope.error?.message ?? "请求失败，请稍后重试", envelope.error?.code ?? "SYSTEM_REQUEST_FAILED", envelope.requestId);
  }

  if (envelope.data === null) {
    return undefined as T;
  }

  return envelope.data;
}

export const authApi = {
  listTenants: () => apiRequest<TenantOption[]>("/api/public/tenants"),
  login: (request: LoginRequest) => apiRequest<LoginResponse>("/api/auth/login", { method: "POST", body: request }),
  me: (token: string) => apiRequest<MeResponse>("/api/auth/me", { token }),
  switchRole: (token: string, request: SwitchRoleRequest) =>
    apiRequest<SwitchRoleResponse>("/api/auth/switch-role", { method: "PUT", token, body: request }),
  logout: (token: string) => apiRequest<void>("/api/auth/logout", { method: "POST", token }),
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
  deletePageGrant: (tenantId: string, grantId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/page-grants/${grantId}`, { method: "DELETE", token }),
  createMember: (tenantId: string, token: string, request: CreateMemberRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/members`, { method: "POST", token, body: request }),
  createDepartment: (tenantId: string, token: string, request: CreateDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments`, { method: "POST", token, body: request }),
  updateDepartment: (tenantId: string, departmentId: string, token: string, request: UpdateDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments/${departmentId}`, { method: "PATCH", token, body: request }),
  deleteDepartment: (tenantId: string, departmentId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/departments/${departmentId}`, { method: "DELETE", token }),
  createRole: (tenantId: string, token: string, request: CreateTenantRoleRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/roles`, { method: "POST", token, body: request }),
  updateRole: (tenantId: string, roleId: string, token: string, request: UpdateTenantRoleRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/roles/${roleId}`, { method: "PATCH", token, body: request }),
  deleteRole: (tenantId: string, roleId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/roles/${roleId}`, { method: "DELETE", token }),
  listResourceGrants: (tenantId: string, token: string) =>
    apiRequest<ResourceGrant[]>(`/api/admin/tenants/${tenantId}/organization/resource-grants`, { token }),
  createResourceGrant: (tenantId: string, token: string, request: CreateResourceGrantRequest) =>
    apiRequest<ResourceGrant>(`/api/admin/tenants/${tenantId}/organization/resource-grants`, { method: "POST", token, body: request }),
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
  listModelProviderTypes: (token: string) => apiRequest<ModelProviderTypeRow[]>("/api/system/model-provider-types", { token }),
  listModelProviders: (token: string, page = 1, size = 10, sort = "createdAt,desc") =>
    apiRequest<ModelProviderPage>(`/api/system/model-providers?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`, { token }),
  createModelProvider: (token: string, body: CreateModelProviderRequest) =>
    apiRequest<ModelProviderRow>("/api/system/model-providers", { method: "POST", token, body }),
  listCapabilities: (token: string, page = 1, size = 10, sort = "createdAt,desc") =>
    apiRequest<SystemCapabilityPage>(`/api/system/capabilities?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`, { token }),
  createCapability: (token: string, body: CreateSystemCapabilityRequest) =>
    apiRequest<SystemCapabilityRow>("/api/system/capabilities", { method: "POST", token, body }),
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
};

export const workflowApi = {
  listDrafts: (tenantId: string, token: string, page = 1, size = 10, keyword = "", sort = "updatedAt,desc") => {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
      keyword,
      sort,
    });
    return apiRequest<PageResponse<WorkflowDraftRow>>(`/api/tenants/${tenantId}/workflows/drafts?${params.toString()}`, { token });
  },
  createDraft: (tenantId: string, token: string, request: CreateWorkflowDraftRequest) =>
    apiRequest<WorkflowDraftRow>(`/api/tenants/${tenantId}/workflows/drafts`, { method: "POST", token, body: request }),
  getDraft: (tenantId: string, workflowId: string, token: string) =>
    apiRequest<WorkflowDraftDetail>(`/api/tenants/${tenantId}/workflows/drafts/${workflowId}`, { token }),
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
