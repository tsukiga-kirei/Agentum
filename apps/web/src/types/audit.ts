export interface AuditRunSummary {
  id: string;
  title: string;
  workflowName: string;
  versionNumber: number;
  state: string;
  startedAt: string;
  completedAt: string | null;
  operatorName: string;
}

export interface WorkflowRunInfo {
  id: string;
  title: string;
  workflowName: string;
  versionNumber: number;
  state: string;
  startedAt: string;
  completedAt: string | null;
  operatorName: string;
}

export interface NodeRunInfo {
  id: string;
  nodeKey: string;
  nodeType: string;
  name: string;
  state: string;
  stateLabel: string;
  inputSnapshot: Record<string, any>;
  outputSnapshot: Record<string, any>;
  configSnapshot: Record<string, any>;
  startedAt: string | null;
  completedAt: string | null;
}

export interface VariableSnapshotInfo {
  id: string;
  nodeRunId: string | null;
  variableName: string;
  valueType: string;
  value: any;
  sourceNodeKey: string | null;
  sensitive: boolean;
  deliveryVisible: boolean;
  createdAt: string;
}

export interface RunEventInfo {
  id: string;
  eventType: string;
  title: string;
  description: string;
  nodeKey: string | null;
  operatorName: string;
  eventTime: string;
}

export interface ModelCallLogInfo {
  id: string;
  nodeRunId: string;
  modelName: string;
  status: string;
  promptSnapshot: Record<string, any>;
  responseSnapshot: Record<string, any>;
  tokenUsage: Record<string, any>;
  latencyMs: number | null;
  createdAt: string;
  completedAt: string | null;
}

export interface McpCallLogInfo {
  id: string;
  nodeRunId: string;
  toolName: string;
  capabilityCode: string;
  status: string;
  requestPayload: Record<string, any>;
  responsePayload: Record<string, any>;
  latencyMs: number | null;
  createdAt: string;
  completedAt: string | null;
}

export interface DeliveryRecordInfo {
  id: string;
  nodeRunId: string;
  deliveryType: string;
  target: string | null;
  title: string;
  status: string;
  payload: Record<string, any>;
  resultSnapshot: Record<string, any>;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface AuditEvidence {
  runInfo: WorkflowRunInfo;
  nodeRuns: NodeRunInfo[];
  variableSnapshots: VariableSnapshotInfo[];
  runEvents: RunEventInfo[];
  modelCallLogs: ModelCallLogInfo[];
  mcpCallLogs: McpCallLogInfo[];
  deliveryRecords: DeliveryRecordInfo[];
}

export interface AuditToolCall {
  id: string;
  runId: string;
  nodeRunId: string;
  toolType: "MCP" | "MODEL";
  toolName: String;
  status: string;
  latencyMs: number | null;
  createdAt: string;
  callerName: string;
  requestPayload: Record<string, any>;
  responsePayload: Record<string, any>;
  errorMessage: string | null;
}

export interface AuditOperationLog {
  id: string;
  operatorName: string;
  actionType: string;
  targetType: string;
  targetName: string | null;
  description: string;
  payload: string;
  clientIp: string | null;
  createdAt: string;
}
