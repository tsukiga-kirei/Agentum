import type { PageResponse } from "./organization";

// 业务工作台契约：publishedWorkflowTotal 按“有冻结版本且入口未收回”的业务入口口径统计；
// openedCapabilityTotal / myAssetTotal 等来自真实治理数据。
// 业务工作台已接入运行态接口：创建任务列表展示全部已发布流程，并通过 canLaunch 区分权限。

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
  nodeRunId: string;
  title: string;
  workflowName: string;
  nodeName: string;
  waitingReason: string;
  waitingFor: string;
  action: string;
  createdAt: string;
};

export type WorkbenchRecentRunRow = {
  id: string;
  title: string;
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
  runtimeAvailable: boolean;
  runtimeStatusLabel: string;
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

export type WorkbenchAvailableWorkflowPage = PageResponse<WorkbenchAvailableWorkflowRow>;

export type WorkbenchTaskRunRow = {
  id: string;
  title: string;
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
