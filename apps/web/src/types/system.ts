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

export type ModelProviderRow = {
  id: string;
  name: string;
  providerType: string;
  baseUrl: string | null;
  defaultModel: string | null;
  status: string;
};

export type CreateModelProviderRequest = {
  name: string;
  providerType: string;
  baseUrl?: string;
  defaultModel?: string;
  status?: string;
};

export type SystemCapabilityRow = {
  id: string;
  capabilityType: string;
  name: string;
  code: string;
  version: string;
  riskLevel: string;
  status: string;
};

export type CreateSystemCapabilityRequest = {
  capabilityType: string;
  name: string;
  code: string;
  version?: string;
  riskLevel?: string;
  status?: string;
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
