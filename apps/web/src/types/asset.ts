import type { PageResponse } from "./organization";

export type AssetSummary = {
  openedToMeSystemTotal: number;
  tenantSystemPoolTotal: number;
  myAssetTotal: number;
};

export type AssetType = "agent_template" | "skill" | "mcp" | "prompt_template" | "delivery";
export type CreatableAssetType = "agent_template" | "prompt_template";

export type SystemCapabilityAssetRow = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  description: string;
  promptContent: string;
  riskLevel: string;
  status: string;
  assignedToMe: boolean;
  assignmentScope: string;
  openSource: "tenant_admin" | "user_shared";
  ownerDisplayName: string;
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
  visibility: "private" | "shared";
  sourceType: string;
  baseSystemCapabilityId: string | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
};

export type ShareableMemberRow = {
  userId: string;
  username: string;
  displayName: string;
};

export type MyAssetDetail = MyAssetRow & {
  config: {
    promptContent?: string;
    systemPrompt?: string;
    systemPromptTemplateId?: string;
    skillIds?: string[];
    mcpIds?: string[];
  };
  sharedUserIds: string[];
};

export type UpdateMyAssetSharingRequest = {
  visibility: "private" | "shared";
  sharedUserIds?: string[];
};

export type CreateMyAssetRequest = {
  assetType: CreatableAssetType;
  name: string;
  code?: string;
  version?: string;
  description?: string;
  riskLevel?: string;
  visibility?: "private" | "shared";
  baseSystemCapabilityId?: string;
  config?: Record<string, unknown>;
  sharedUserIds?: string[];
};

export type UpdateMyAssetRequest = Omit<CreateMyAssetRequest, "assetType" | "baseSystemCapabilityId" | "code">;

export type SystemCapabilityAssetPage = PageResponse<SystemCapabilityAssetRow>;
export type MyAssetPage = PageResponse<MyAssetRow>;
