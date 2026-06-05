import type { LoginRequest, LoginResponse, MeResponse, PortalType, SsoProviderOption, SwitchRoleRequest, SwitchRoleResponse, TenantOption } from "../types/auth";
import type { AssetSummary, CreateMyAssetRequest, MyAssetDetail, MyAssetPage, MyAssetRow, ShareableMemberRow, SystemCapabilityAssetPage, UpdateMyAssetAccessRequest, UpdateMyAssetRequest } from "../types/asset";
import type {
  CreateDepartmentRequest,
  CreateMemberRequest,
  CreatePageGrantRequest,
  CreateResourceGrantRequest,
  CreateTenantRoleRequest,
  CreateTenantOrgRoleRequest,
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
  SystemCapabilityPage,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantPage,
  SystemTenantRow,
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
  UpdateWorkflowAccessRequest,
  UpdateWorkflowDraftRequest,
  WorkflowVariableDraft,
} from "../types/workflow-contract";
import type { WorkbenchAvailableWorkflowPage, WorkbenchSummary } from "../types/workbench";

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

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

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
  listSsoProviders: (tenantId: string) => apiRequest<SsoProviderOption[]>(`/api/public/tenants/${tenantId}/sso-providers`),
  ssoAuthorizeUrl: (tenantId: string, providerId: string, portal: PortalType) => {
    const params = new URLSearchParams({ tenantId, providerId, portal });
    return `${API_BASE_URL}/api/auth/sso/authorize?${params.toString()}`;
  },
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
  updatePageGrant: (tenantId: string, grantGroupId: string, token: string, request: CreatePageGrantRequest) =>
    apiRequest<PageGrant>(`/api/admin/tenants/${tenantId}/organization/page-grants/${grantGroupId}`, { method: "PUT", token, body: request }),
  deletePageGrant: (tenantId: string, grantId: string, token: string) =>
    apiRequest<void>(`/api/admin/tenants/${tenantId}/organization/page-grants/${grantId}`, { method: "DELETE", token }),
  createMember: (tenantId: string, token: string, request: CreateMemberRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/members`, { method: "POST", token, body: request }),
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
  // 业务工作台概览：返回真实统计、运行态状态标识与（运行态上线前为空的）待办、运行记录。
  summary: (tenantId: string, token: string) =>
    apiRequest<WorkbenchSummary>(`/api/tenants/${tenantId}/workbench/summary`, { token }),
  // 可发起的已发布工作流：按“有冻结版本且入口未收回”展示，与设计态草稿状态解耦。
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
