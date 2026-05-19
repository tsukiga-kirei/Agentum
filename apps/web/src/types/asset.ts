import type { PageResponse } from "./organization";

export type AssetSummary = {
  openedToMeSystemTotal: number;
  tenantSystemPoolTotal: number;
  myAssetTotal: number;
};

export type AssetType = "agent_template" | "skill" | "mcp" | "prompt_template" | "delivery";

export type SystemCapabilityAssetRow = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  riskLevel: string;
  status: string;
  assignedToMe: boolean;
  assignmentScope: string;
  openedAt: string;
};

export type MyAssetRow = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  description: string;
  riskLevel: string;
  status: string;
  visibility: "private" | "tenant";
  sourceType: string;
  baseSystemCapabilityId: string | null;
  createdAt: string;
  updatedAt: string;
};

export type CreateMyAssetRequest = {
  assetType: AssetType;
  name: string;
  code: string;
  version?: string;
  description?: string;
  riskLevel?: string;
  visibility?: "private" | "tenant";
  baseSystemCapabilityId?: string;
  config?: Record<string, unknown>;
};

export type SystemCapabilityAssetPage = PageResponse<SystemCapabilityAssetRow>;
export type MyAssetPage = PageResponse<MyAssetRow>;
