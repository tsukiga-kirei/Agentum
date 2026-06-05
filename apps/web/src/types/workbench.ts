import type { PageResponse } from "./organization";

// 业务工作台契约：publishedWorkflowTotal 按“有冻结版本且入口未收回”的业务入口口径统计；
// openedCapabilityTotal / myAssetTotal 等来自真实治理数据。
// 待办仅展示已保存且未完成任务；任务记录仅展示已完成任务。

export type WorkbenchMetrics = {
  pendingTodoTotal: number;
  runningRunTotal: number;
  publishedWorkflowTotal: number;
  availableWorkflowTotal: number;
  openedCapabilityTotal: number;
  myAssetTotal: number;
};

export type WorkbenchPendingTodoRow = {
  id: string;
  runId: string;
  openTodoId: string | null;
  title: string;
  runNumber: string;
  workflowName: string;
  currentNodeName: string;
  state: string;
  stateLabel: string;
  waitingReason: string;
  action: string;
  hasOpenTodo: boolean;
  progressPercent: number;
  completedNodeCount: number;
  totalNodeCount: number;
  updatedAt: string;
};

export type WorkbenchRecentRunRow = {
  id: string;
  title: string;
  runNumber: string;
  workflowName: string;
  state: string;
  stateLabel: string;
  currentNode: string;
  ownerName: string;
  completedNodeCount: number;
  totalNodeCount: number;
  updatedAt: string;
};

export type WorkbenchSummary = {
  metrics: WorkbenchMetrics;
  pendingTodos: WorkbenchPendingTodoRow[];
  recentRuns: WorkbenchRecentRunRow[];
  generatedAt: string;
};

export type WorkbenchAvailableWorkflowRow = {
  id: string;
  name: string;
  description: string;
  nodeCount: number;
  latestVersionNumber: number;
  publishedAt: string;
  ownerId: string | null;
  ownerName: string;
  visibility: "owner" | "open" | "locked" | "manager" | string;
  canLaunch: boolean;
  launchBlockedReason: string;
};

export type WorkbenchAvailableWorkflowNodeRow = {
  nodeId: string;
  nodeType: string;
  name: string;
  summary: string;
  sortOrder: number;
};

export type WorkbenchAvailableWorkflowPreview = {
  workflowId: string;
  versionNumber: number;
  nodes: WorkbenchAvailableWorkflowNodeRow[];
};

export type WorkbenchAvailableWorkflowPage = PageResponse<WorkbenchAvailableWorkflowRow>;

export type WorkbenchTaskRunRow = {
  id: string;
  title: string;
  runNumber: string;
  workflowName: string;
  workflowVersionNumber: number;
  state: string;
  stateLabel: string;
  currentNodeName: string;
  ownerName: string;
  completedNodeCount: number;
  totalNodeCount: number;
  progressPercent: number;
  hasOpenTodo: boolean;
  updatedAt: string;
};

export type WorkbenchNodeRunRow = {
  id: string;
  nodeId: string;
  nodeType: string;
  name: string;
  state: string;
  stateLabel: string;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  config: Record<string, unknown>;
  sortOrder: number;
};

export type WorkbenchRunEventRow = {
  id: string;
  eventType: string;
  title: string;
  description: string;
  nodeId: string | null;
  eventTime: string;
};

export type WorkbenchRunDetail = {
  id: string;
  title: string;
  runNumber: string;
  saved: boolean;
  readOnly: boolean;
  workflowId: string;
  workflowName: string;
  workflowVersionNumber: number;
  state: string;
  stateLabel: string;
  progressPercent: number;
  currentNodeKey: string | null;
  currentNodeName: string | null;
  currentNodeType: string | null;
  ownerName: string;
  startedAt: string;
  updatedAt: string;
  nodes: WorkbenchNodeRunRow[];
  events: WorkbenchRunEventRow[];
  openTodo: WorkbenchPendingTodoRow | null;
};

export type WorkbenchTaskRunPage = PageResponse<WorkbenchTaskRunRow>;
