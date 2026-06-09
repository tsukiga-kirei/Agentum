import type { PageResponse } from "./organization";

export type SystemSummary = {
  tenantTotal: number;
  tenantActive: number;
  modelProviderTotal: number;
  systemCapabilityTotal: number;
  tenantCapabilityGrantTotal: number;
};

export type SystemTenantRow = {
  id: string;
  name: string;
  code: string;
  status: string;
};

export type UpdateTenantStatusRequest = {
  status: string;
};

export type CreateTenantRequest = {
  name: string;
  code: string;
  adminUsername: string;
  adminDisplayName: string;
  adminPassword: string;
  adminEmail?: string;
};

export type CreateTenantAdminRequest = {
  username: string;
  displayName: string;
  password: string;
  email?: string;
  departmentId?: string;
};

export type UpdateTenantAdminProfileRequest = {
  username: string;
  displayName: string;
  email?: string;
};

export type UpdateTenantAdminStatusRequest = {
  status: "active" | "disabled";
};

export type ModelProviderRow = {
  id: string;
  name: string;
  providerType: string;
  baseUrl: string | null;
  defaultModel: string | null;
  apiKeyConfigured: boolean;
  status: string;
  connectivityStatus: string;
  connectivityCheckedAt: string | null;
  maxTokens: number | null;
};

export type ModelProviderTypeRow = {
  code: string;
  name: string;
  description: string | null;
  authScheme: string;
  defaultBaseUrl: string | null;
  modelListEndpoint: string | null;
};

export type ModelProviderTestResult = {
  providerId: string;
  status: string;
  summary: string;
  availableModels: string[];
  latencyMs: number;
  checkedAt: string;
  connectivityStatus: string;
};

export type CreateModelProviderRequest = {
  name: string;
  providerType: string;
  baseUrl?: string;
  defaultModel: string;
  apiKey?: string;
  status?: string;
  maxTokens: number;
};

export type UpdateModelProviderRequest = CreateModelProviderRequest;

export type SystemCapabilityRow = {
  id: string;
  capabilityType: string;
  name: string;
  code: string;
  version: string;
  description: string;
  riskLevel: string;
  status: string;
  config: Record<string, unknown>;
  connectivityStatus: string;
  connectivityCheckedAt: string | null;
};

export type CreateSystemCapabilityRequest = {
  capabilityType: string;
  name: string;
  version?: string;
  description?: string;
  riskLevel?: string;
  status?: string;
  config?: Record<string, unknown>;
};

export type UpdateSystemCapabilityRequest = CreateSystemCapabilityRequest;

export type CapabilityToolRow = {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
};

export type CapabilityTestResult = {
  capabilityId: string;
  status: string;
  summary: string;
  tools: CapabilityToolRow[];
  checkedAt: string;
  connectivityStatus: string;
};

export type TenantCapabilityGrantRow = {
  id: string;
  tenantId: string;
  tenantName: string;
  tenantCode: string;
  capabilityId: string;
  capabilityName: string;
  capabilityCode: string;
  capabilityType: string;
  grantStatus: string;
};

export type CreateTenantCapabilityGrantRequest = {
  tenantId: string;
  capabilityId: string;
  status?: string;
};

export type UpdateTenantCapabilityGrantStatusRequest = {
  status: string;
};

export type TenantModelAssignmentRow = {
  id: string;
  tenantId: string;
  providerId: string;
  providerName: string;
  providerType: string;
  defaultModel: string | null;
  assignmentStatus: string;
};

export type CreateTenantModelAssignmentRequest = {
  tenantId: string;
  providerId: string;
  defaultModel?: string;
  status?: string;
};

export type UpdateTenantModelAssignmentStatusRequest = {
  status: string;
};

export type SystemTenantPage = PageResponse<SystemTenantRow>;
export type ModelProviderPage = PageResponse<ModelProviderRow>;
export type SystemCapabilityPage = PageResponse<SystemCapabilityRow>;
