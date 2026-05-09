import type { AuthUser, LoginRequest, LoginResponse, TenantOption } from "../types/auth";
import type { CreateDepartmentRequest, CreateMemberRequest, TenantOrganizationOverview } from "../types/organization";
import type {
  CreateModelProviderRequest,
  CreateSystemCapabilityRequest,
  CreateTenantCapabilityGrantRequest,
  ModelProviderRow,
  SystemCapabilityRow,
  SystemSummary,
  SystemTenantRow,
  TenantCapabilityGrantRow,
  UpdateTenantStatusRequest,
} from "../types/system";

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
  me: (token: string) => apiRequest<AuthUser>("/api/auth/me", { token }),
  logout: (token: string) => apiRequest<void>("/api/auth/logout", { method: "POST", token }),
};

export const organizationApi = {
  overview: (tenantId: string, token: string) => apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/overview`, { token }),
  createMember: (tenantId: string, token: string, request: CreateMemberRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/members`, { method: "POST", token, body: request }),
  createDepartment: (tenantId: string, token: string, request: CreateDepartmentRequest) =>
    apiRequest<TenantOrganizationOverview>(`/api/admin/tenants/${tenantId}/organization/departments`, { method: "POST", token, body: request }),
};

export const systemApi = {
  summary: (token: string) => apiRequest<SystemSummary>("/api/system/summary", { token }),
  listTenants: (token: string) => apiRequest<SystemTenantRow[]>("/api/system/tenants", { token }),
  updateTenantStatus: (tenantId: string, token: string, body: UpdateTenantStatusRequest) =>
    apiRequest<SystemTenantRow>(`/api/system/tenants/${tenantId}/status`, { method: "PATCH", token, body }),
  listModelProviders: (token: string) => apiRequest<ModelProviderRow[]>("/api/system/model-providers", { token }),
  createModelProvider: (token: string, body: CreateModelProviderRequest) =>
    apiRequest<ModelProviderRow>("/api/system/model-providers", { method: "POST", token, body }),
  listCapabilities: (token: string) => apiRequest<SystemCapabilityRow[]>("/api/system/capabilities", { token }),
  createCapability: (token: string, body: CreateSystemCapabilityRequest) =>
    apiRequest<SystemCapabilityRow>("/api/system/capabilities", { method: "POST", token, body }),
  listGrants: (token: string, tenantId?: string) => {
    const q = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : "";
    return apiRequest<TenantCapabilityGrantRow[]>(`/api/system/tenant-capability-grants${q}`, { token });
  },
  createGrant: (token: string, body: CreateTenantCapabilityGrantRequest) =>
    apiRequest<TenantCapabilityGrantRow>("/api/system/tenant-capability-grants", { method: "POST", token, body }),
};
