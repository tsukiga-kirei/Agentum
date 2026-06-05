import type { PageResponse } from "./organization";

// 业务工作台契约：publishedWorkflowTotal 按“有冻结版本且入口未收回”的业务入口口径统计；
// openedCapabilityTotal / myAssetTotal 等来自真实治理数据。
// pendingTodos 和 recentRuns 在运行态上线前为空列表，前端结合 runtimeAvailable 展示运行态建设中提示。

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
  title: string;
  workflowName: string;
  waitingReason: string;
  waitingFor: string;
  action: string;
  dueAt: string | null;
};

export type WorkbenchRecentRunRow = {
  id: string;
  workflowName: string;
  state: string;
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
};

export type WorkbenchAvailableWorkflowPage = PageResponse<WorkbenchAvailableWorkflowRow>;
