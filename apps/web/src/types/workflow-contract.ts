// 这些类型先作为前端契约占位，字段与 packages/shared-contract 的 OpenAPI / JSON Schema 方向保持一致。
// 后续接入类型生成后，本文件应替换为 OpenAPI Client 或 JSON Schema 生成产物的再导出层。
export type ApiResponse<TData> = {
  success: boolean;
  data: TData;
  error: null | {
    code: string;
    message: string;
    details?: Record<string, unknown>;
  };
  requestId: string;
};

export type WorkflowStatus = "draft" | "published" | "review";

export type WorkflowDraftRow = {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  status: WorkflowStatus;
  nodeCount: number;
  pausePointCount: number;
  ownerId: string | null;
  ownerName: string;
  updatedAt: string;
};

export type CreateWorkflowDraftRequest = {
  name: string;
  description?: string;
};

export type WorkflowNodeDraft = {
  nodeId: string;
  nodeType: WorkflowNodeType;
  name: string;
  positionX: number;
  positionY: number;
  inputVariables: string[];
  outputVariables: string[];
  config: Record<string, unknown>;
};

export type WorkflowEdgeDraft = {
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
  label?: string;
  conditionExpression?: string;
};

export type WorkflowDraftDetail = {
  draft: WorkflowDraftRow;
  nodes: WorkflowNodeDraft[];
  edges: WorkflowEdgeDraft[];
  variables: WorkflowVariableDraft[];
};

export type WorkflowPublishValidationIssue = {
  code: string;
  level: "error" | "warning";
  message: string;
  nodeId: string;
  nodeName: string;
};

export type WorkflowPublishValidationResult = {
  valid: boolean;
  nodeCount: number;
  edgeCount: number;
  issues: WorkflowPublishValidationIssue[];
};

export type WorkflowPublishResult = {
  draft: WorkflowDraftRow;
  versionNumber: number;
  publishedAt: string;
};

export type WorkflowVariableDraft = {
  name: string;
  type: VariableType;
  sourceNode: string;
  description: string;
  jsonSchema: Record<string, unknown>;
  sensitive: boolean;
  deliverable: boolean;
};

export type WorkflowNodeType =
  | "trigger"
  | "user_input"
  | "agent"
  | "parallel_group"
  | "merge"
  | "condition"
  | "human_review"
  | "delivery";

export type VariableType = "string" | "number" | "object" | "array" | "boolean" | "decision" | "file";

export type WorkflowVariableContract = {
  name: string;
  sourceNode: string;
  type: VariableType;
  description: string;
  sensitive: boolean;
  deliverable: boolean;
};

export type WorkflowRunState = "running" | "paused" | "waiting_event" | "resumed" | "failed" | "completed";

export type AgentTemplateAsset = {
  id: string;
  name: string;
  version: string;
  status: "draft" | "published";
  skills: string[];
  mcpServices: string[];
  promptTemplates: string[];
  model: string;
  outputMode: "once" | "ask_then_confirm" | "pause_then_continue";
};

export type McpServiceAsset = {
  id: string;
  name: string;
  version: string;
  riskLevel: "low" | "medium" | "high";
  authorizedRoles: string[];
  auditRequired: boolean;
};

export type AuditEvent = {
  id: string;
  time: string;
  actor: string;
  resourceType: string;
  action: string;
  result: "success" | "warning" | "failed";
  summary: string;
};

export type DeliveryRecord = {
  id: string;
  target: string;
  status: "pending" | "success" | "failed";
  artifact: string;
  retryable: boolean;
};

export type PromptTemplateAsset = {
  id: string;
  name: string;
  version: string;
  category: string;
  status: "draft" | "published";
  usage: string;
};

export type DeliveryCapabilityAsset = {
  id: string;
  name: string;
  channel: "document" | "email" | "oa" | "im" | "webhook" | "database";
  version: string;
  riskLevel: "low" | "medium" | "high";
  status: "draft" | "published";
};

export type TenantCapabilityGrant = {
  tenantId: string;
  capabilityId: string;
  capabilityType: "skill" | "mcp" | "prompt_template" | "delivery";
  status: "enabled" | "disabled";
};
