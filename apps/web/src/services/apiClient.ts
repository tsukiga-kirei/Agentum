import type { AuthUser, LoginRequest, LoginResponse, TenantOption } from "../types/auth";

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

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  const envelope = (await response.json()) as ApiEnvelope<T>;

  if (!response.ok || !envelope.success) {
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
