import type { PageResponse } from "./organization";

export type AssetSummary = {
  openedToMeSystemTotal: number;
  tenantSystemPoolTotal: number;
  myAssetTotal: number;
};

export type AssetType = "agent_template" | "skill" | "mcp" | "prompt_template" | "delivery";
export type CreatableAssetType = "agent_template" | "prompt_template";
export type AccessScope = "self" | "specified" | "all";
export type AccessLevel = "none" | "read" | "edit" | "owner";

export type SystemCapabilityAssetRow = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  description: string;
  promptContent: string;
  config: Record<string, unknown>;
  riskLevel: string;
  status: string;
  assignedToMe: boolean;
  assignmentScope: string;
  openSource: "tenant_admin" | "user_shared";
  accessLevel: AccessLevel;
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
  readScope: AccessScope;
  editScope: AccessScope;
  accessLevel: AccessLevel;
  canManageAccess: boolean;
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
  readUserIds: string[];
  editUserIds: string[];
};

export type UpdateMyAssetAccessRequest = {
  readScope: AccessScope;
  editScope: AccessScope;
  readUserIds?: string[];
  editUserIds?: string[];
};

export type CreateMyAssetRequest = {
  assetType: CreatableAssetType;
  name: string;
  code?: string;
  version?: string;
  description?: string;
  riskLevel?: string;
  readScope?: AccessScope;
  editScope?: AccessScope;
  baseSystemCapabilityId?: string;
  config?: Record<string, unknown>;
  readUserIds?: string[];
  editUserIds?: string[];
};

export type UpdateMyAssetRequest = Pick<CreateMyAssetRequest, "name" | "version" | "description" | "riskLevel" | "config">;

export type SystemCapabilityAssetPage = PageResponse<SystemCapabilityAssetRow>;
export type MyAssetPage = PageResponse<MyAssetRow>;
