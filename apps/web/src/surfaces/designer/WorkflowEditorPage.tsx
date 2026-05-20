import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Bot,
  CheckCircle2,
  ChevronLeft,
  Clock3,
  FileText,
  GripVertical,
  Layers3,
  ListChecks,
  MessageSquareText,
  PackageCheck,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  RefreshCw,
  Save,
  Search,
  Settings2,
  TextCursorInput,
  Trash2,
  Wrench,
  Zap,
} from "lucide-react";
import { AgentumApiError, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  WorkflowDraftDetail,
  WorkflowEdgeDraft,
  WorkflowNodeDraft,
  WorkflowNodeType,
  WorkflowVariableDraft,
} from "../../types/workflow-contract";
import { WorkflowDraft } from "./WorkflowDraftsPage";

type EditorNodeData = {
  label: string;
  typeLabel: string;
  nodeType: WorkflowNodeType;
  summary: string;
  inputVariables: string[];
  outputVariables: string[];
  pausePoint: boolean;
  configStatus: "complete" | "incomplete";
  runState: "未开始" | "等待输入" | "执行中" | "等待审核" | "已完成" | "待配置";
  outputMode: "一次性输出" | "追问确认" | "分析后暂停";
  toolCount: number;
  allowQuestion: boolean;
  rawConfig?: Record<string, unknown>;
};

type WorkflowEditorNode = {
  id: string;
  position: { x: number; y: number };
  data: EditorNodeData;
};

type WorkflowEditorEdge = {
  id: string;
  source: string;
  target: string;
  label?: string;
  conditionExpression?: string;
};

type WorkflowVariable = {
  name: string;
  sourceNodeId: string;
  sourceNodeName: string;
  type: "string" | "number" | "object" | "array" | "boolean" | "decision" | "file";
  sensitive: boolean;
  deliverable: boolean;
  description: string;
};

type WorkflowBrickType = "input" | "agent" | "cluster" | "delivery";

type InputFieldConfig = {
  id: string;
  label: string;
  variable: string;
  placeholder: string;
};

type ClusterAgentConfig = {
  id: string;
  name: string;
  prompt: string;
  output: string;
};

type WorkflowEditorPageProps = {
  workflow: WorkflowDraft;
  onBack: () => void;
  onDraftSaved: (draft: WorkflowDraft) => void;
};

const SYSTEM_TRIGGER_ID = "trigger_manual";

const nodeTypeLabels: Record<WorkflowNodeType, string> = {
  trigger: "系统触发",
  user_input: "输入节点",
  agent: "单智能体节点",
  parallel_group: "智能体集群节点",
  merge: "组装节点",
  condition: "条件节点",
  human_review: "审核节点",
  delivery: "交付节点",
};

const brickDefinitions: Record<WorkflowBrickType, {
  label: string;
  description: string;
  icon: typeof TextCursorInput;
  nodeType: WorkflowNodeType;
  accentClass: string;
}> = {
  input: {
    label: "输入节点",
    description: "配置用户需要填写的输入框和输出参数",
    icon: TextCursorInput,
    nodeType: "user_input",
    accentClass: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  },
  agent: {
    label: "单智能体节点",
    description: "选择智能体，或配置提示词、MCP 与 Skill",
    icon: Bot,
    nodeType: "agent",
    accentClass: "bg-indigo-100 text-indigo-800 dark:bg-indigo-950/50 dark:text-indigo-300",
  },
  cluster: {
    label: "智能体集群节点",
    description: "编排多个智能体并配置拼接与汇总规则",
    icon: Layers3,
    nodeType: "parallel_group",
    accentClass: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300",
  },
  delivery: {
    label: "交付节点",
    description: "配置最终文档、OA、邮件等交付方式",
    icon: PackageCheck,
    nodeType: "delivery",
    accentClass: "bg-orange-100 text-orange-800 dark:bg-orange-950/50 dark:text-orange-300",
  },
};

const starterNodes: WorkflowEditorNode[] = [
  {
    id: SYSTEM_TRIGGER_ID,
    position: { x: 0, y: 0 },
    data: {
      label: "手动发起",
      typeLabel: "系统触发",
      nodeType: "trigger",
      summary: "业务人员从工作台发起流程，系统自动写入发起人和发起时间。",
      inputVariables: [],
      outputVariables: ["starter", "started_at"],
      pausePoint: false,
      configStatus: "complete",
      runState: "已完成",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
      rawConfig: { brickType: "trigger" },
    },
  },
  createBrickNode("input", 0, ["starter"]),
  createBrickNode("agent", 1, ["company_full_name"]),
  createBrickNode("cluster", 2, ["agent_response"]),
  createBrickNode("delivery", 3, ["cluster_result"]),
];

const starterEdges = rebuildSequentialEdges(starterNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID));

const starterVariableMetadata: Record<string, Pick<WorkflowVariable, "type" | "sensitive" | "deliverable" | "description">> = {
  starter: { type: "string", sensitive: false, deliverable: false, description: "流程发起人标识" },
  started_at: { type: "string", sensitive: false, deliverable: false, description: "流程发起时间" },
  company_full_name: { type: "string", sensitive: false, deliverable: false, description: "授信公司全称" },
  agent_response: { type: "object", sensitive: false, deliverable: false, description: "单智能体回复内容" },
  cluster_result: { type: "object", sensitive: false, deliverable: true, description: "智能体集群拼接后的结果" },
  delivery_record: { type: "object", sensitive: false, deliverable: true, description: "交付记录" },
};

export function WorkflowEditorPage({ workflow, onBack, onDraftSaved }: WorkflowEditorPageProps) {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [nodes, setNodes] = useState<WorkflowEditorNode[]>([]);
  const [edges, setEdges] = useState<WorkflowEditorEdge[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState("");
  const [isConfigCollapsed, setIsConfigCollapsed] = useState(false);
  const [nodeSearchValue, setNodeSearchValue] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [saveFeedback, setSaveFeedback] = useState<{ tone: "success" | "error" | "info"; message: string } | null>(null);
  const [usingStarterTemplate, setUsingStarterTemplate] = useState(false);
  const [declaredVariables, setDeclaredVariables] = useState<WorkflowVariable[]>([]);
  const [isAddBrickModalOpen, setIsAddBrickModalOpen] = useState(false);

  useEffect(() => {
    if (!token || !user?.tenantId) {
      setLoading(false);
      setLoadError("当前账号缺少租户上下文，无法加载工作流草稿");
      return;
    }

    let cancelled = false;
    setLoading(true);
    setLoadError("");
    setSaveFeedback(null);

    // 设计态现在以“步骤积木”为主，但仍读取后端草稿结构，便于后续平滑迁移到阶段 / 步骤契约。
    void workflowApi.getDraft(user.tenantId, workflow.id, token)
      .then((detail) => {
        if (cancelled) {
          return;
        }
        const hasPersistedGraph = detail.nodes.length > 0;
        const nextNodes = hasPersistedGraph ? ensureSystemTrigger(detail.nodes.map(toEditorNode)) : cloneStarterNodes();
        const nextEdges = hasPersistedGraph ? detail.edges.map(toEditorEdge) : cloneStarterEdges();
        const nextVariables = detail.variables.length > 0 ? toWorkflowVariables(detail.variables, nextNodes) : buildWorkflowVariables(nextNodes);
        setNodes(nextNodes);
        setEdges(nextEdges.length > 0 ? nextEdges : rebuildSequentialEdges(nextNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID)));
        setDeclaredVariables(nextVariables);
        setSelectedNodeId("");
        setUsingStarterTemplate(!hasPersistedGraph);
        if (!hasPersistedGraph) {
          setSaveFeedback({ tone: "info", message: "已载入基础积木模板，左侧可继续添加和调整步骤。" });
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        console.warn("[workflow] 工作流草稿加载失败", getWorkflowEditorErrorContext(error, user.tenantId ?? undefined, workflow.id));
        setLoadError(error instanceof AgentumApiError ? error.message : "无法加载工作流草稿");
        setNodes([]);
        setEdges([]);
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [token, user?.tenantId, workflow.id]);

  const orderedNodes = useMemo(() => orderNodesByEdges(nodes, edges), [edges, nodes]);
  const visibleNodes = useMemo(() => orderedNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID), [orderedNodes]);
  const selectedNode = visibleNodes.find((node) => node.id === selectedNodeId) ?? null;
  const selectedNodeIndex = selectedNode ? visibleNodes.findIndex((node) => node.id === selectedNode.id) : -1;
  const workflowVariables = useMemo(
    () => declaredVariables.length > 0 ? declaredVariables : buildWorkflowVariables(orderedNodes),
    [declaredVariables, orderedNodes],
  );
  const availableVariables = workflowVariables.filter((variable) => {
    if (!selectedNode) {
      return true;
    }
    const sourceIndex = visibleNodes.findIndex((node) => node.id === variable.sourceNodeId);

    return variable.sourceNodeId === SYSTEM_TRIGGER_ID || (sourceIndex >= 0 && sourceIndex < selectedNodeIndex);
  });
  const incompleteNodes = visibleNodes.filter((node) => node.data.configStatus === "incomplete");
  const pausePointCount = visibleNodes.filter((node) => node.data.pausePoint).length;
  const matchedNodes = visibleNodes.filter((node) => node.data.label.includes(nodeSearchValue.trim()));

  const persistGraph = useCallback(async (nextNodes: WorkflowEditorNode[], nextEdges: WorkflowEditorEdge[]) => {
    if (!token || !user?.tenantId) {
      setSaveFeedback({ tone: "error", message: "当前账号缺少租户上下文，无法保存工作流草稿" });
      return;
    }

    setSaving(true);
    setSaveFeedback(null);

    try {
      const nextVariables = buildWorkflowVariables(nextNodes);
      const detail = await workflowApi.saveGraph(
        user.tenantId,
        workflow.id,
        token,
        nextNodes.map(toWorkflowNodeDraft),
        nextEdges.map(toWorkflowEdgeDraft),
        nextVariables.map(toWorkflowVariableDraft),
      );
      applyPersistedDetail(detail, setNodes, setEdges, setSelectedNodeId);
      setDeclaredVariables(toWorkflowVariables(detail.variables, detail.nodes.map(toEditorNode)));
      setUsingStarterTemplate(false);
      setSaveFeedback({ tone: "success", message: "流程设计已保存" });
      onDraftSaved(detail.draft);
    } catch (error) {
      console.warn("[workflow] 工作流草稿保存失败", getWorkflowEditorErrorContext(error, user.tenantId, workflow.id));
      setSaveFeedback({ tone: "error", message: error instanceof AgentumApiError ? error.message : "保存工作流草稿失败" });
    } finally {
      setSaving(false);
    }
  }, [onDraftSaved, token, user?.tenantId, workflow.id]);

  function commitVisibleNodes(nextVisibleNodes: WorkflowEditorNode[], nextSelectedNodeId = selectedNodeId) {
    const systemTrigger = nodes.find((node) => node.id === SYSTEM_TRIGGER_ID) ?? cloneStarterNodes()[0];
    const normalizedVisibleNodes = normalizeVisibleNodeOrder(nextVisibleNodes);
    const nextNodes = [systemTrigger, ...normalizedVisibleNodes];
    const nextEdges = rebuildSequentialEdges(normalizedVisibleNodes);
    setNodes(nextNodes);
    setEdges(nextEdges);
    setDeclaredVariables(buildWorkflowVariables(nextNodes));
    setSelectedNodeId(nextSelectedNodeId);
    setSaveFeedback({ tone: "info", message: "本地编排已更新，保存后写入草稿。" });
  }

  function handleAddBrick(brickType: WorkflowBrickType) {
    const previousOutputs = visibleNodes.length > 0 ? visibleNodes[visibleNodes.length - 1].data.outputVariables : ["starter"];
    const nextNode = createBrickNode(brickType, visibleNodes.length, previousOutputs);
    commitVisibleNodes([...visibleNodes, nextNode], nextNode.id);
    setIsAddBrickModalOpen(false);
  }

  function handleMoveNode(nodeId: string, direction: -1 | 1) {
    const currentIndex = visibleNodes.findIndex((node) => node.id === nodeId);
    const nextIndex = currentIndex + direction;
    if (currentIndex < 0 || nextIndex < 0 || nextIndex >= visibleNodes.length) {
      return;
    }
    const nextVisibleNodes = [...visibleNodes];
    const [movingNode] = nextVisibleNodes.splice(currentIndex, 1);
    nextVisibleNodes.splice(nextIndex, 0, movingNode);
    commitVisibleNodes(nextVisibleNodes, nodeId);
  }

  function handleDeleteNode(nodeId: string) {
    const nextVisibleNodes = visibleNodes.filter((node) => node.id !== nodeId);
    commitVisibleNodes(nextVisibleNodes, nextVisibleNodes[0]?.id ?? "");
  }

  function handleSearchLocate() {
    const nextNode = matchedNodes[0];

    if (nextNode) {
      setSelectedNodeId(nextNode.id);
    }
  }

  function updateSelectedNode(patch: Partial<EditorNodeData>) {
    if (!selectedNode) {
      return;
    }
    const nextNodes = nodes.map((node) => {
      if (node.id !== selectedNode.id) {
        return node;
      }
      return {
        ...node,
        data: {
          ...node.data,
          ...patch,
          configStatus: "incomplete" as const,
        },
      };
    });
    setNodes(nextNodes);
    setDeclaredVariables(buildWorkflowVariables(nextNodes));
  }

  function updateSelectedConfig(nextConfig: Record<string, unknown>) {
    if (!selectedNode) {
      return;
    }
    updateSelectedNode({
      rawConfig: {
        ...(selectedNode.data.rawConfig ?? {}),
        ...nextConfig,
      },
    });
  }

  async function handleSaveAll() {
    const normalizedVisibleNodes = normalizeVisibleNodeOrder(visibleNodes);
    const systemTrigger = nodes.find((node) => node.id === SYSTEM_TRIGGER_ID) ?? cloneStarterNodes()[0];
    const nextNodes = [systemTrigger, ...normalizedVisibleNodes];
    const nextEdges = rebuildSequentialEdges(normalizedVisibleNodes);
    await persistGraph(nextNodes, nextEdges);
  }

  async function handleSaveSelectedNode() {
    if (!selectedNode) {
      await handleSaveAll();
      return;
    }
    const nextNodes = nodes.map((node) => {
      if (node.id !== selectedNode.id) {
        return node;
      }
      return {
        ...node,
        data: {
          ...node.data,
          configStatus: "complete" as const,
        },
      };
    });
    await persistGraph(nextNodes, rebuildSequentialEdges(nextNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID)));
  }

  if (loading) {
    return <EditorStateShell workflowName={workflow.name} onBack={onBack} icon={<Clock3 className="h-5 w-5" aria-hidden="true" />} message="正在加载工作流草稿" />;
  }

  if (loadError) {
    return (
      <EditorStateShell
        workflowName={workflow.name}
        onBack={onBack}
        icon={<AlertTriangle className="h-5 w-5" aria-hidden="true" />}
        message={loadError}
      />
    );
  }

  return (
    <div className="flex h-[calc(100vh-var(--header-height))] flex-col bg-[var(--color-bg-layout)]">
      <div className="flex flex-wrap items-center gap-3 border-b border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-4 py-2">
        <button type="button" onClick={onBack} className="agent-button h-7 px-2 text-xs">
          <ChevronLeft className="h-3.5 w-3.5" aria-hidden="true" />
          返回
        </button>
        <div className="mr-auto min-w-0">
          <h2 className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{workflow.name}</h2>
          <p className="truncate text-xs text-[var(--color-text-tertiary)]">左侧搭积木，右侧配置当前步骤</p>
        </div>
        <ToolbarMetric icon={ListChecks} label="积木" value={visibleNodes.length.toString()} />
        <ToolbarMetric icon={Settings2} label="待配" value={incompleteNodes.length.toString()} tone={incompleteNodes.length > 0 ? "warning" : "default"} />
        <ToolbarMetric icon={Clock3} label="暂停" value={pausePointCount.toString()} />
        {saveFeedback ? <SaveFeedback feedback={saveFeedback} /> : null}
        <label className="relative block w-52">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--color-text-tertiary)]" aria-hidden="true" />
          <span className="sr-only">搜索积木</span>
          <input
            value={nodeSearchValue}
            onChange={(event) => setNodeSearchValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                handleSearchLocate();
              }
            }}
            className="agent-input h-7 w-full pl-7 pr-2 text-xs outline-none"
            placeholder="搜索积木"
          />
        </label>
        <button type="button" onClick={() => void handleSaveAll()} disabled={saving} className="agent-button agent-button-primary h-8 px-3 text-xs">
          <Save className="h-3.5 w-3.5" aria-hidden="true" />
          {saving ? "保存中" : "保存流程"}
        </button>
        <button
          type="button"
          onClick={() => setIsConfigCollapsed((current) => !current)}
          className="agent-button h-7 px-2 text-xs"
          title={isConfigCollapsed ? "展开配置" : "收起配置"}
        >
          {isConfigCollapsed ? <PanelRightOpen className="h-3.5 w-3.5" /> : <PanelRightClose className="h-3.5 w-3.5" />}
        </button>
      </div>

      <div className={`grid min-h-0 flex-1 ${isConfigCollapsed ? "grid-cols-[280px]" : "grid-cols-[280px_minmax(0,1fr)]"}`}>
        <main className="min-h-0 overflow-y-auto border-r border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-3">
          <WorkflowStepBuilder
            nodes={visibleNodes}
            selectedNodeId={selectedNodeId}
            usingStarterTemplate={usingStarterTemplate}
            onSelectNode={setSelectedNodeId}
            onOpenAddBrick={() => setIsAddBrickModalOpen(true)}
            onMoveNode={handleMoveNode}
            onDeleteNode={handleDeleteNode}
          />
        </main>

        {!isConfigCollapsed ? (
          <div className="min-h-0 overflow-y-auto bg-[var(--color-bg-layout)] p-4">
            {selectedNode ? (
              <NodeConfigPanel
                node={selectedNode}
                availableVariables={availableVariables}
                workflowVariables={workflowVariables}
                onUpdateNode={updateSelectedNode}
                onUpdateConfig={updateSelectedConfig}
                onSave={handleSaveSelectedNode}
                saving={saving}
              />
            ) : (
              <WorkflowOverviewPanel
                nodes={visibleNodes}
                variables={workflowVariables}
                incompleteNodes={incompleteNodes}
                onSelectNode={setSelectedNodeId}
              />
            )}
          </div>
        ) : null}
      </div>

      {isAddBrickModalOpen ? (
        <AddBrickModal
          onClose={() => setIsAddBrickModalOpen(false)}
          onSelect={handleAddBrick}
        />
      ) : null}
    </div>
  );
}

function WorkflowStepBuilder({
  nodes,
  selectedNodeId,
  usingStarterTemplate,
  onSelectNode,
  onOpenAddBrick,
  onMoveNode,
  onDeleteNode,
}: {
  nodes: WorkflowEditorNode[];
  selectedNodeId: string;
  usingStarterTemplate: boolean;
  onSelectNode: (nodeId: string) => void;
  onOpenAddBrick: () => void;
  onMoveNode: (nodeId: string, direction: -1 | 1) => void;
  onDeleteNode: (nodeId: string) => void;
}) {
  return (
    <section className="space-y-3" aria-label="工作流步骤编排">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-[var(--color-text-primary)]">工作流步骤</h3>
          {usingStarterTemplate ? <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">起步模板，可继续调整</p> : null}
        </div>
        <button type="button" onClick={onOpenAddBrick} className="agent-button agent-button-primary h-8 shrink-0 px-2.5 text-xs">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          添加
        </button>
      </div>

      <div className="space-y-2">
        {nodes.length === 0 ? (
          <div className="rounded-[var(--radius-lg)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-5 text-center">
            <Plus className="mx-auto h-7 w-7 text-[var(--color-text-tertiary)]" aria-hidden="true" />
            <p className="mt-3 text-sm font-medium text-[var(--color-text-primary)]">还没有步骤</p>
            <p className="mt-1 text-xs text-[var(--color-text-secondary)]">点击添加积木开始搭建。</p>
          </div>
        ) : null}

        {nodes.map((node, index) => (
          <WorkflowStepRow
            key={node.id}
            node={node}
            index={index}
            selected={selectedNodeId === node.id}
            canMoveUp={index > 0}
            canMoveDown={index < nodes.length - 1}
            onSelect={() => onSelectNode(node.id)}
            onMoveUp={() => onMoveNode(node.id, -1)}
            onMoveDown={() => onMoveNode(node.id, 1)}
            onDelete={() => onDeleteNode(node.id)}
          />
        ))}
      </div>
    </section>
  );
}

function WorkflowStepRow({
  node,
  index,
  selected,
  canMoveUp,
  canMoveDown,
  onSelect,
  onMoveUp,
  onMoveDown,
  onDelete,
}: {
  node: WorkflowEditorNode;
  index: number;
  selected: boolean;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onSelect: () => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onDelete: () => void;
}) {
  const brickType = getBrickType(node);
  const definition = brickDefinitions[brickType];
  const Icon = definition.icon;

  return (
    <article className={`rounded-[var(--radius-md)] border bg-[var(--color-bg-card)] shadow-[var(--shadow-xs)] transition ${selected ? "border-[var(--color-primary)]" : "border-[var(--color-border-light)]"}`}>
      <div className="p-2.5">
        <button type="button" onClick={onSelect} className="flex w-full min-w-0 items-start gap-2 text-left">
          <span className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md ${definition.accentClass}`}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </span>
          <span className="min-w-0">
            <span className="flex flex-wrap items-center gap-2">
              <span className="text-[11px] font-medium text-[var(--color-text-tertiary)]">步骤 {index + 1}</span>
              <span className="rounded bg-[var(--color-bg-hover)] px-2 py-0.5 text-[11px] font-medium text-[var(--color-text-secondary)]">{definition.label}</span>
            </span>
            <span className="mt-1 block truncate text-sm font-semibold text-[var(--color-text-primary)]">{node.data.label}</span>
            <span className="mt-1 line-clamp-2 text-xs leading-5 text-[var(--color-text-secondary)]">{node.data.summary}</span>
          </span>
        </button>
        <div className="mt-2 flex items-center justify-between gap-2">
          {node.data.configStatus === "incomplete" ? <TinyBadge tone="warning">待配置</TinyBadge> : <TinyBadge tone="success">已配置</TinyBadge>}
          <div className="flex items-center gap-0.5">
            <IconButton label="拖动占位" icon={GripVertical} disabled onClick={() => undefined} />
            <IconButton label="上移" icon={ArrowUp} disabled={!canMoveUp} onClick={onMoveUp} />
            <IconButton label="下移" icon={ArrowDown} disabled={!canMoveDown} onClick={onMoveDown} />
            <IconButton label="删除" icon={Trash2} onClick={onDelete} tone="danger" />
          </div>
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5 border-t border-[var(--color-border-light)] px-2.5 py-2">
        <TinyBadge>入 {node.data.inputVariables.length}</TinyBadge>
        <TinyBadge>出 {node.data.outputVariables.length}</TinyBadge>
        {node.data.allowQuestion ? <TinyBadge tone="info">追问</TinyBadge> : null}
      </div>
    </article>
  );
}

function AddBrickModal({ onClose, onSelect }: { onClose: () => void; onSelect: (brickType: WorkflowBrickType) => void }) {
  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section className="sys-modal" style={{ maxWidth: 720 }} aria-labelledby="add-brick-title" onClick={(event) => event.stopPropagation()}>
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>添加到末尾</div>
            <span id="add-brick-title" className="sys-modal-title">选择积木</span>
          </div>
          <button className="sys-modal-close" onClick={onClose} aria-label="关闭添加积木弹窗">×</button>
        </div>
        <div className="sys-modal-body">
          <div className="grid gap-3 md:grid-cols-2">
            {(Object.keys(brickDefinitions) as WorkflowBrickType[]).map((brickType) => {
              const definition = brickDefinitions[brickType];
              const Icon = definition.icon;

              return (
                <button
                  key={brickType}
                  type="button"
                  onClick={() => onSelect(brickType)}
                  className="flex min-h-[116px] items-start gap-3 rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 text-left transition hover:border-[var(--color-primary)]"
                >
                  <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-md ${definition.accentClass}`}>
                    <Icon className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold text-[var(--color-text-primary)]">{definition.label}</span>
                    <span className="mt-2 block text-sm leading-6 text-[var(--color-text-secondary)]">{definition.description}</span>
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      </section>
    </div>
  );
}
function NodeConfigPanel({
  node,
  availableVariables,
  workflowVariables,
  onUpdateNode,
  onUpdateConfig,
  onSave,
  saving,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  workflowVariables: WorkflowVariable[];
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onSave: () => Promise<void>;
  saving: boolean;
}) {
  const brickType = getBrickType(node);
  const definition = brickDefinitions[brickType];
  const Icon = definition.icon;

  return (
    <aside className="mx-auto max-w-5xl rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]" aria-labelledby="node-config-title">
      <div className="border-b border-[var(--color-border-light)] px-5 py-4">
        <div className="flex items-center gap-3">
          <span className={`flex h-10 w-10 items-center justify-center rounded-lg ${definition.accentClass}`}>
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <div>
            <p className="text-xs text-[var(--color-text-tertiary)]">{definition.label}</p>
            <h3 id="node-config-title" className="text-base font-semibold text-[var(--color-text-primary)]">
              {node.data.label}
            </h3>
          </div>
        </div>
      </div>

      <div className="grid gap-4 p-5 xl:grid-cols-2">
        <PanelGroup title="基础信息">
          <label className="sys-field">
            <span className="sys-field-label">步骤名称</span>
            <input
              value={node.data.label}
              onChange={(event) => onUpdateNode({ label: event.target.value })}
              className="sys-field-input"
              placeholder="请输入步骤名称"
            />
          </label>
          <label className="sys-field">
            <span className="sys-field-label">步骤说明</span>
            <textarea
              value={node.data.summary}
              onChange={(event) => onUpdateNode({ summary: event.target.value })}
              className="sys-field-textarea"
              placeholder="说明这一步要完成什么任务"
            />
          </label>
        </PanelGroup>

        <ParameterPanel
          node={node}
          availableVariables={availableVariables}
          onUpdateNode={onUpdateNode}
        />

        {brickType === "input" ? (
          <InputBrickConfig node={node} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
        ) : null}

        {brickType === "agent" ? (
          <SingleAgentBrickConfig node={node} availableVariables={availableVariables} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
        ) : null}

        {brickType === "cluster" ? (
          <AgentClusterBrickConfig node={node} availableVariables={availableVariables} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
        ) : null}

        {brickType === "delivery" ? (
          <DeliveryBrickConfig node={node} workflowVariables={workflowVariables} onUpdateConfig={onUpdateConfig} />
        ) : null}

        {(brickType === "agent" || brickType === "cluster") ? (
          <InteractionConfig node={node} onUpdateNode={onUpdateNode} />
        ) : null}

        <button
          type="button"
          onClick={() => void onSave()}
          disabled={saving}
          className="agent-button agent-button-primary h-10 w-full px-3 text-sm xl:col-span-2"
        >
          <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
          {saving ? "保存中" : "保存当前积木"}
        </button>
      </div>
    </aside>
  );
}

function WorkflowOverviewPanel({
  nodes,
  variables,
  incompleteNodes,
  onSelectNode,
}: {
  nodes: WorkflowEditorNode[];
  variables: WorkflowVariable[];
  incompleteNodes: WorkflowEditorNode[];
  onSelectNode: (nodeId: string) => void;
}) {
  return (
    <aside className="mx-auto max-w-5xl space-y-4 rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-5 shadow-[var(--shadow-sm)]" aria-labelledby="workflow-overview-title">
      <div>
        <p className="text-xs text-[var(--color-text-tertiary)]">工作流总览</p>
        <h3 id="workflow-overview-title" className="text-base font-semibold text-[var(--color-text-primary)]">先搭步骤，再配置能力</h3>
        <p className="mt-2 text-sm leading-6 text-[var(--color-text-secondary)]">左侧添加四类积木并调整顺序。点击某个积木后，这里会切换为对应配置面板。</p>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <OverviewMetric label="步骤" value={String(nodes.length)} />
        <OverviewMetric label="变量" value={String(variables.length)} />
        <OverviewMetric label="待配" value={String(incompleteNodes.length)} />
      </div>
      <PanelGroup title="待配置积木">
        {incompleteNodes.length === 0 ? (
          <p className="text-sm text-[var(--color-text-tertiary)]">当前没有待配置积木。</p>
        ) : (
          <div className="space-y-2">
            {incompleteNodes.map((node) => (
              <button key={node.id} type="button" onClick={() => onSelectNode(node.id)} className="flex w-full items-center justify-between rounded bg-[var(--color-bg-card)] px-2 py-2 text-left text-sm ring-1 ring-[var(--color-border-light)]">
                <span className="truncate text-[var(--color-text-primary)]">{node.data.label}</span>
                <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600" aria-hidden="true" />
              </button>
            ))}
          </div>
        )}
      </PanelGroup>
      <PanelGroup title="输出参数">
        <VariableList variables={variables.map((variable) => variable.name)} emptyText="暂无输出参数" />
      </PanelGroup>
    </aside>
  );
}

function ParameterPanel({
  node,
  availableVariables,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  return (
    <PanelGroup title="输入输出参数">
      <label className="sys-field">
        <span className="sys-field-label">输入参数</span>
        <VariableSelectList
          selectedVariables={node.data.inputVariables}
          availableVariables={availableVariables.map((variable) => variable.name)}
          onChange={(variables) => onUpdateNode({ inputVariables: variables })}
        />
      </label>
      <label className="sys-field">
        <span className="sys-field-label">输出参数</span>
        <input
          value={node.data.outputVariables.join(", ")}
          onChange={(event) => onUpdateNode({ outputVariables: parseVariableList(event.target.value) })}
          className="sys-field-input"
          placeholder="例如：agent_response"
        />
        <span className="sys-field-hint">多个参数用英文逗号分隔；下游积木通过这些参数衔接。</span>
      </label>
    </PanelGroup>
  );
}

function InputBrickConfig({
  node,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const fields = readInputFields(node.data.rawConfig?.inputFields, node.data.outputVariables);

  function updateField(fieldId: string, patch: Partial<InputFieldConfig>) {
    const nextFields = fields.map((field) => field.id === fieldId ? { ...field, ...patch } : field);
    onUpdateConfig({ inputFields: nextFields });
    onUpdateNode({ outputVariables: nextFields.map((field) => field.variable).filter(Boolean) });
  }

  return (
    <PanelGroup title="输入框配置">
      <div className="space-y-3">
        {fields.map((field, index) => (
          <article key={field.id} className="rounded-[var(--radius-md)] bg-[var(--color-bg-card)] p-3 ring-1 ring-[var(--color-border-light)]">
            <div className="mb-3 flex items-center justify-between">
              <span className="text-xs font-medium text-[var(--color-text-tertiary)]">输入框 {index + 1}</span>
            </div>
            <label className="sys-field">
              <span className="sys-field-label">显示名称</span>
              <input value={field.label} onChange={(event) => updateField(field.id, { label: event.target.value })} className="sys-field-input" />
            </label>
            <label className="sys-field">
              <span className="sys-field-label">输出参数名</span>
              <input value={field.variable} onChange={(event) => updateField(field.id, { variable: normalizeVariableName(event.target.value) })} className="sys-field-input" />
            </label>
            <label className="sys-field">
              <span className="sys-field-label">占位提示</span>
              <input value={field.placeholder} onChange={(event) => updateField(field.id, { placeholder: event.target.value })} className="sys-field-input" />
            </label>
          </article>
        ))}
      </div>
      <button
        type="button"
        onClick={() => {
          const nextField = createInputField(fields.length);
          onUpdateConfig({ inputFields: [...fields, nextField] });
          onUpdateNode({ outputVariables: [...node.data.outputVariables, nextField.variable] });
        }}
        className="agent-button mt-3 h-9 px-3 text-xs"
      >
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
        添加输入框
      </button>
    </PanelGroup>
  );
}

function SingleAgentBrickConfig({
  node,
  availableVariables,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const selectedMcps = readStringArray(config.mcpServices, ["企业信息 MCP"]);
  const selectedSkills = readStringArray(config.skills, ["授信分析 Skill"]);

  return (
    <PanelGroup title="智能体配置">
      <SelectLikeField
        label="智能体来源"
        value={readString(config.agentSource, "自定义智能体")}
        options={["自定义智能体", "能力资产：授信分析智能体", "能力资产：报告撰写智能体"]}
        onChange={(value) => onUpdateConfig({ agentSource: value })}
      />
      <SelectLikeField
        label="提示词模板"
        value={readString(config.promptTemplate, "授信报告分析模板")}
        options={["授信报告分析模板", "风险识别模板", "不使用模板"]}
        onChange={(value) => onUpdateConfig({ promptTemplate: value })}
      />
      <label className="sys-field">
        <span className="sys-field-label">自定义提示词</span>
        <textarea
          value={readString(config.systemPrompt, "你是授信报告分析智能体，请基于输入参数完成分析并输出结构化回复。")}
          onChange={(event) => onUpdateConfig({ systemPrompt: event.target.value })}
          className="sys-field-textarea"
          placeholder="配置这个智能体的角色、任务边界和输出要求"
        />
      </label>
      <CapabilityToggleGroup
        title="MCP"
        options={["企业信息 MCP", "司法查询 MCP", "文件读取 MCP", "财务数据库 MCP"]}
        selected={selectedMcps}
        onChange={(values) => {
          onUpdateConfig({ mcpServices: values });
          onUpdateNode({ toolCount: values.length });
        }}
      />
      <CapabilityToggleGroup
        title="Skill"
        options={["授信分析 Skill", "风险识别 Skill", "报告撰写 Skill", "追问澄清 Skill"]}
        selected={selectedSkills}
        onChange={(values) => onUpdateConfig({ skills: values })}
      />
      <p className="text-xs leading-5 text-[var(--color-text-tertiary)]">可引用输入：{availableVariables.map((variable) => variable.name).join("、") || "暂无"}</p>
    </PanelGroup>
  );
}

function AgentClusterBrickConfig({
  node,
  availableVariables,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const agents = readClusterAgents(config.clusterAgents);

  function updateAgent(agentId: string, patch: Partial<ClusterAgentConfig>) {
    const nextAgents = agents.map((agent) => agent.id === agentId ? { ...agent, ...patch } : agent);
    onUpdateConfig({ clusterAgents: nextAgents });
    onUpdateNode({ toolCount: nextAgents.length });
  }

  return (
    <PanelGroup title="智能体集群配置">
      <div className="space-y-3">
        {agents.map((agent, index) => (
          <article key={agent.id} className="rounded-[var(--radius-md)] bg-[var(--color-bg-card)] p-3 ring-1 ring-[var(--color-border-light)]">
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-[var(--color-text-tertiary)]">智能体 {index + 1}</span>
              <button
                type="button"
                onClick={() => {
                  const nextAgents = agents.filter((item) => item.id !== agent.id);
                  onUpdateConfig({ clusterAgents: nextAgents });
                  onUpdateNode({ toolCount: nextAgents.length });
                }}
                className="rounded p-1 text-[var(--color-text-tertiary)] hover:bg-[var(--color-bg-hover)] hover:text-red-600"
                aria-label="删除智能体"
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            </div>
            <label className="sys-field">
              <span className="sys-field-label">智能体名称</span>
              <input value={agent.name} onChange={(event) => updateAgent(agent.id, { name: event.target.value })} className="sys-field-input" />
            </label>
            <label className="sys-field">
              <span className="sys-field-label">任务提示词</span>
              <textarea value={agent.prompt} onChange={(event) => updateAgent(agent.id, { prompt: event.target.value })} className="sys-field-textarea" />
            </label>
            <label className="sys-field">
              <span className="sys-field-label">输出参数</span>
              <input value={agent.output} onChange={(event) => updateAgent(agent.id, { output: normalizeVariableName(event.target.value) })} className="sys-field-input" />
            </label>
          </article>
        ))}
      </div>
      <button
        type="button"
        onClick={() => {
          const nextAgents = [...agents, createClusterAgent(agents.length)];
          onUpdateConfig({ clusterAgents: nextAgents });
          onUpdateNode({ toolCount: nextAgents.length });
        }}
        className="agent-button mt-3 h-9 px-3 text-xs"
      >
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
        添加智能体
      </button>
      <label className="sys-field mt-3">
        <span className="sys-field-label">拼接规则</span>
        <textarea
          value={readString(config.mergeRule, "按章节顺序合并多个智能体输出，冲突内容保留来源并交给用户审查。")}
          onChange={(event) => onUpdateConfig({ mergeRule: event.target.value })}
          className="sys-field-textarea"
          placeholder="说明多智能体结果如何拼接、去重和处理冲突"
        />
      </label>
      <p className="text-xs leading-5 text-[var(--color-text-tertiary)]">可引用输入：{availableVariables.map((variable) => variable.name).join("、") || "暂无"}</p>
    </PanelGroup>
  );
}

function DeliveryBrickConfig({
  node,
  workflowVariables,
  onUpdateConfig,
}: {
  node: WorkflowEditorNode;
  workflowVariables: WorkflowVariable[];
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
}) {
  const config = node.data.rawConfig ?? {};

  return (
    <PanelGroup title="交付配置">
      <SelectLikeField
        label="交付方式"
        value={readString(config.deliveryChannel, "Word / PDF")}
        options={["Word / PDF", "OA 流程", "邮件", "Webhook"]}
        onChange={(value) => onUpdateConfig({ deliveryChannel: value })}
      />
      <SelectLikeField
        label="交付内容"
        value={readString(config.artifactVariable, node.data.inputVariables[0] ?? "cluster_result")}
        options={workflowVariables.map((variable) => variable.name)}
        onChange={(value) => onUpdateConfig({ artifactVariable: value })}
      />
      <label className="sys-field">
        <span className="sys-field-label">交付说明</span>
        <textarea
          value={readString(config.deliveryTarget, "生成授信报告正式文档，等待用户确认后交付。")}
          onChange={(event) => onUpdateConfig({ deliveryTarget: event.target.value })}
          className="sys-field-textarea"
          placeholder="说明交付目标、模板和确认方式"
        />
      </label>
    </PanelGroup>
  );
}

function InteractionConfig({
  node,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  return (
    <PanelGroup title="交互控制">
      <label className="flex items-center justify-between gap-3 rounded bg-[var(--color-bg-card)] px-3 py-2 text-sm ring-1 ring-[var(--color-border-light)]">
        <span className="text-[var(--color-text-primary)]">允许重新生成</span>
        <input
          type="checkbox"
          checked={node.data.outputMode === "追问确认" || node.data.outputMode === "分析后暂停"}
          onChange={(event) => onUpdateNode({ outputMode: event.target.checked ? "追问确认" : "一次性输出" })}
        />
      </label>
      <label className="mt-2 flex items-center justify-between gap-3 rounded bg-[var(--color-bg-card)] px-3 py-2 text-sm ring-1 ring-[var(--color-border-light)]">
        <span className="text-[var(--color-text-primary)]">允许追问修改</span>
        <input
          type="checkbox"
          checked={node.data.allowQuestion}
          onChange={(event) => onUpdateNode({ allowQuestion: event.target.checked })}
        />
      </label>
    </PanelGroup>
  );
}

function VariableSelectList({
  selectedVariables,
  availableVariables,
  onChange,
}: {
  selectedVariables: string[];
  availableVariables: string[];
  onChange: (variables: string[]) => void;
}) {
  if (availableVariables.length === 0) {
    return <p className="text-sm text-[var(--color-text-tertiary)]">暂无可选输入参数。</p>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {availableVariables.map((variable) => {
        const selected = selectedVariables.includes(variable);
        return (
          <button
            key={variable}
            type="button"
            onClick={() => {
              onChange(selected ? selectedVariables.filter((item) => item !== variable) : [...selectedVariables, variable]);
            }}
            className={`rounded px-2 py-1 text-xs font-medium ring-1 transition ${
              selected
                ? "bg-[var(--color-primary)] text-white ring-[var(--color-primary)]"
                : "bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] ring-[var(--color-border-light)] hover:text-[var(--color-primary)]"
            }`}
          >
            {variable}
          </button>
        );
      })}
    </div>
  );
}

function CapabilityToggleGroup({
  title,
  options,
  selected,
  onChange,
}: {
  title: string;
  options: string[];
  selected: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-[var(--color-text-tertiary)]">{title}</p>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = selected.includes(option);
          return (
            <button
              key={option}
              type="button"
              onClick={() => onChange(active ? selected.filter((item) => item !== option) : [...selected, option])}
              className={`rounded px-2 py-1 text-xs font-medium ring-1 transition ${
                active
                  ? "bg-[var(--color-primary)] text-white ring-[var(--color-primary)]"
                  : "bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] ring-[var(--color-border-light)] hover:text-[var(--color-primary)]"
              }`}
            >
              {option}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function SelectLikeField({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)} className="sys-field-input">
        {options.map((option) => <option key={option} value={option}>{option}</option>)}
      </select>
    </label>
  );
}

function ConfigRows({ rows }: { rows: Array<[string, string]> }) {
  return (
    <dl className="space-y-2">
      {rows.map(([label, value]) => (
        <div key={label} className="grid grid-cols-[88px_minmax(0,1fr)] gap-3 text-sm">
          <dt className="text-[var(--color-text-tertiary)]">{label}</dt>
          <dd className="min-w-0 font-medium text-[var(--color-text-primary)]">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function PanelGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] px-3 py-3">
      <h4 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">{title}</h4>
      {children}
    </section>
  );
}

function VariableList({ variables, emptyText }: { variables: string[]; emptyText: string }) {
  if (variables.length === 0) {
    return <p className="text-sm text-[var(--color-text-tertiary)]">{emptyText}</p>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {variables.map((variable) => (
        <span
          key={variable}
          className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]"
        >
          {variable}
        </span>
      ))}
    </div>
  );
}

function ToolbarMetric({ icon: Icon, label, value, tone = "default" }: { icon: typeof Zap; label: string; value: string; tone?: "default" | "warning" }) {
  return (
    <span className={`inline-flex h-7 items-center gap-1.5 rounded px-2 text-xs font-medium ${tone === "warning" ? "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300" : "bg-[var(--color-bg-hover)] text-[var(--color-text-secondary)]"}`}>
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {label} {value}
    </span>
  );
}

function OverviewMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] px-3 py-2 text-center">
      <p className="text-xs text-[var(--color-text-tertiary)]">{label}</p>
      <p className="mt-1 text-lg font-semibold text-[var(--color-text-primary)]">{value}</p>
    </div>
  );
}

function SaveFeedback({ feedback }: { feedback: { tone: "success" | "error" | "info"; message: string } }) {
  return (
    <span
      className={`rounded px-2 py-1 text-xs font-medium ${
        feedback.tone === "success"
          ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300"
          : feedback.tone === "error"
            ? "bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300"
            : "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300"
      }`}
    >
      {feedback.message}
    </span>
  );
}

function TinyBadge({ children, tone = "default" }: { children: ReactNode; tone?: "default" | "warning" | "info" | "success" }) {
  const className =
    tone === "warning"
      ? "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300"
      : tone === "info"
        ? "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300"
        : tone === "success"
          ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300"
          : "bg-[var(--color-bg-hover)] text-[var(--color-text-secondary)]";

  return <span className={`rounded px-2 py-1 text-[11px] font-medium ${className}`}>{children}</span>;
}

function IconButton({
  icon: Icon,
  label,
  disabled,
  onClick,
  tone = "default",
}: {
  icon: typeof ArrowUp;
  label: string;
  disabled?: boolean;
  onClick: () => void;
  tone?: "default" | "danger";
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`rounded-md p-2 transition disabled:cursor-not-allowed disabled:opacity-40 ${
        tone === "danger"
          ? "text-[var(--color-text-tertiary)] hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-950/30"
          : "text-[var(--color-text-tertiary)] hover:bg-[var(--color-bg-hover)] hover:text-[var(--color-primary)]"
      }`}
      aria-label={label}
      title={label}
    >
      <Icon className="h-4 w-4" aria-hidden="true" />
    </button>
  );
}

function EditorStateShell({
  workflowName,
  onBack,
  icon,
  message,
}: {
  workflowName: string;
  onBack: () => void;
  icon: ReactNode;
  message: string;
}) {
  return (
    <div className="flex h-[calc(100vh-var(--header-height))] flex-col">
      <div className="flex items-center gap-3 border-b border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-4 py-2">
        <button type="button" onClick={onBack} className="agent-button h-7 px-2 text-xs">
          <ChevronLeft className="h-3.5 w-3.5" aria-hidden="true" />
          返回
        </button>
        <h2 className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{workflowName}</h2>
      </div>
      <div className="flex flex-1 items-center justify-center bg-[var(--color-bg-layout)] px-4">
        <div className="agent-card flex min-w-[280px] items-center gap-3 p-4 text-sm text-[var(--color-text-primary)]">
          <span className="text-[var(--color-primary)]">{icon}</span>
          <span>{message}</span>
        </div>
      </div>
    </div>
  );
}

function cloneStarterNodes(): WorkflowEditorNode[] {
  return starterNodes.map((node) => cloneEditorNode(node));
}

function cloneStarterEdges(): WorkflowEditorEdge[] {
  return starterEdges.map((edge) => ({ ...edge }));
}

function cloneEditorNode(node: WorkflowEditorNode): WorkflowEditorNode {
  return {
    ...node,
    position: { ...node.position },
    data: {
      ...node.data,
      inputVariables: [...node.data.inputVariables],
      outputVariables: [...node.data.outputVariables],
      rawConfig: cloneRecord(node.data.rawConfig ?? {}),
    },
  };
}

function createBrickNode(brickType: WorkflowBrickType, index: number, inputVariables: string[] = []): WorkflowEditorNode {
  const definition = brickDefinitions[brickType];
  const id = `${brickType}_${Date.now().toString(36)}_${index}`;
  const outputVariable = getDefaultOutputVariable(brickType, index);
  const defaultData: Record<WorkflowBrickType, Pick<EditorNodeData, "label" | "summary" | "pausePoint" | "runState" | "outputMode" | "toolCount" | "allowQuestion" | "rawConfig">> = {
    input: {
      label: "输入信息",
      summary: "配置用户需要填写的输入框。",
      pausePoint: true,
      runState: "等待输入",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
      rawConfig: {
        brickType,
        inputFields: [createInputField(0)],
      },
    },
    agent: {
      label: "单智能体处理",
      summary: "选择或配置一个智能体，加载提示词模板、MCP 和 Skill 完成任务。",
      pausePoint: true,
      runState: "待配置",
      outputMode: "追问确认",
      toolCount: 1,
      allowQuestion: true,
      rawConfig: {
        brickType,
        agentSource: "自定义智能体",
        promptTemplate: "授信报告分析模板",
        systemPrompt: "你是授信报告分析智能体，请基于输入参数完成分析并输出结构化回复。",
        mcpServices: ["企业信息 MCP"],
        skills: ["授信分析 Skill"],
      },
    },
    cluster: {
      label: "智能体集群处理",
      summary: "多个智能体并行处理，再按拼接规则汇总输出。",
      pausePoint: true,
      runState: "待配置",
      outputMode: "追问确认",
      toolCount: 2,
      allowQuestion: true,
      rawConfig: {
        brickType,
        clusterAgents: [createClusterAgent(0), createClusterAgent(1)],
        mergeRule: "按章节顺序合并多个智能体输出，冲突内容保留来源并交给用户审查。",
      },
    },
    delivery: {
      label: "交付结果",
      summary: "配置最终交付方式和交付内容。",
      pausePoint: false,
      runState: "待配置",
      outputMode: "一次性输出",
      toolCount: 1,
      allowQuestion: false,
      rawConfig: {
        brickType,
        deliveryChannel: "Word / PDF",
        artifactVariable: inputVariables[0] ?? "cluster_result",
        deliveryTarget: "生成正式文档，等待用户确认后交付。",
      },
    },
  };

  const data = defaultData[brickType];

  return {
    id,
    position: { x: index * 260, y: 0 },
    data: {
      label: data.label,
      typeLabel: definition.label,
      nodeType: definition.nodeType,
      summary: data.summary,
      inputVariables,
      outputVariables: [outputVariable],
      pausePoint: data.pausePoint,
      configStatus: "incomplete",
      runState: data.runState,
      outputMode: data.outputMode,
      toolCount: data.toolCount,
      allowQuestion: data.allowQuestion,
      rawConfig: data.rawConfig,
    },
  };
}

function createInputField(index: number): InputFieldConfig {
  return {
    id: `field_${Date.now().toString(36)}_${index}`,
    label: index === 0 ? "授信公司全称" : `输入字段 ${index + 1}`,
    variable: index === 0 ? "company_full_name" : `input_${index + 1}`,
    placeholder: index === 0 ? "请输入完整公司名称" : "请输入内容",
  };
}

function createClusterAgent(index: number): ClusterAgentConfig {
  const defaults = [
    ["经营概况智能体", "基于输入和外部数据生成主体经营概况。", "business_section"],
    ["风险分析智能体", "识别司法、舆情、财务和经营风险。", "risk_section"],
    ["授信建议智能体", "结合上下文形成授信额度、期限和条件建议。", "credit_suggestion"],
  ];
  const fallback = defaults[index] ?? [`子智能体 ${index + 1}`, "请补充该智能体的任务提示词。", `agent_${index + 1}_output`];

  return {
    id: `cluster_agent_${Date.now().toString(36)}_${index}`,
    name: fallback[0],
    prompt: fallback[1],
    output: fallback[2],
  };
}

function ensureSystemTrigger(nextNodes: WorkflowEditorNode[]) {
  if (nextNodes.some((node) => node.id === SYSTEM_TRIGGER_ID)) {
    return nextNodes;
  }

  return [cloneStarterNodes()[0], ...nextNodes];
}

function toEditorNode(node: WorkflowNodeDraft): WorkflowEditorNode {
  const config = node.config ?? {};
  const brickType = readBrickType(config.brickType, inferBrickTypeFromNodeType(node.nodeType));
  const definition = brickDefinitions[brickType];
  const fallback = buildFallbackNodeData(node.nodeType, brickType);

  return {
    id: node.nodeId,
    position: { x: node.positionX, y: node.positionY },
    data: {
      label: node.name,
      typeLabel: readString(config.typeLabel, fallback.typeLabel || definition.label),
      nodeType: node.nodeType,
      summary: readString(config.summary, fallback.summary),
      inputVariables: [...(node.inputVariables ?? [])],
      outputVariables: [...(node.outputVariables ?? [])],
      pausePoint: readBoolean(config.pausePoint, fallback.pausePoint),
      configStatus: readLiteral(config.configStatus, ["complete", "incomplete"], fallback.configStatus),
      runState: readLiteral(config.runState, ["未开始", "等待输入", "执行中", "等待审核", "已完成", "待配置"], fallback.runState),
      outputMode: readLiteral(config.outputMode, ["一次性输出", "追问确认", "分析后暂停"], fallback.outputMode),
      toolCount: readNumber(config.toolCount, fallback.toolCount),
      allowQuestion: readBoolean(config.allowQuestion, fallback.allowQuestion),
      rawConfig: { ...config, brickType },
    },
  };
}

function toEditorEdge(edge: WorkflowEdgeDraft): WorkflowEditorEdge {
  return {
    id: edge.edgeId,
    source: edge.sourceNodeId,
    target: edge.targetNodeId,
    label: edge.label || undefined,
    conditionExpression: edge.conditionExpression,
  };
}

function toWorkflowNodeDraft(node: WorkflowEditorNode): WorkflowNodeDraft {
  return {
    nodeId: node.id,
    nodeType: node.data.nodeType,
    name: node.data.label,
    positionX: node.position.x,
    positionY: node.position.y,
    inputVariables: node.data.inputVariables,
    outputVariables: node.data.outputVariables,
    config: {
      ...(node.data.rawConfig ?? {}),
      typeLabel: node.data.typeLabel,
      summary: node.data.summary,
      pausePoint: node.data.pausePoint,
      configStatus: node.data.configStatus,
      runState: node.data.runState,
      outputMode: node.data.outputMode,
      toolCount: node.data.toolCount,
      allowQuestion: node.data.allowQuestion,
    },
  };
}

function toWorkflowEdgeDraft(edge: WorkflowEditorEdge): WorkflowEdgeDraft {
  return {
    edgeId: edge.id,
    sourceNodeId: edge.source,
    targetNodeId: edge.target,
    label: edge.label,
    conditionExpression: edge.conditionExpression,
  };
}

function applyPersistedDetail(
  detail: WorkflowDraftDetail,
  setNodes: (nodes: WorkflowEditorNode[]) => void,
  setEdges: (edges: WorkflowEditorEdge[]) => void,
  setSelectedNodeId: (updater: (currentSelection: string) => string) => void,
) {
  const nextNodes = ensureSystemTrigger(detail.nodes.map(toEditorNode));
  const nextEdges = detail.edges.map(toEditorEdge);
  setNodes(nextNodes);
  setEdges(nextEdges.length > 0 ? nextEdges : rebuildSequentialEdges(nextNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID)));
  setSelectedNodeId((currentSelection) => nextNodes.some((node) => node.id === currentSelection) ? currentSelection : "");
}

function buildWorkflowVariables(nodes: WorkflowEditorNode[]): WorkflowVariable[] {
  return nodes.flatMap((node) =>
    node.data.outputVariables.map((name) => {
      const metadata = starterVariableMetadata[name] ?? {
        type: "string" as const,
        sensitive: false,
        deliverable: node.data.nodeType === "delivery",
        description: "",
      };
      return {
        name,
        sourceNodeId: node.id,
        sourceNodeName: node.data.label,
        type: metadata.type,
        sensitive: metadata.sensitive,
        deliverable: metadata.deliverable,
        description: metadata.description,
      };
    }),
  );
}

function toWorkflowVariables(variables: WorkflowVariableDraft[], nodes: WorkflowEditorNode[]): WorkflowVariable[] {
  const nodesById = new Map(nodes.map((node) => [node.id, node]));

  return variables.map((variable) => ({
    name: variable.name,
    sourceNodeId: variable.sourceNode,
    sourceNodeName: nodesById.get(variable.sourceNode)?.data.label ?? variable.sourceNode,
    type: variable.type,
    sensitive: variable.sensitive,
    deliverable: variable.deliverable,
    description: variable.description,
  }));
}

function toWorkflowVariableDraft(variable: WorkflowVariable): WorkflowVariableDraft {
  return {
    name: variable.name,
    type: variable.type,
    sourceNode: variable.sourceNodeId,
    description: variable.description,
    jsonSchema: {},
    sensitive: variable.sensitive,
    deliverable: variable.deliverable,
  };
}

function buildFallbackNodeData(nodeType: WorkflowNodeType, brickType: WorkflowBrickType): EditorNodeData {
  return {
    label: "未命名积木",
    typeLabel: brickDefinitions[brickType].label,
    nodeType,
    summary: "请在右侧配置这个积木的业务目标和参数。",
    inputVariables: [],
    outputVariables: [],
    pausePoint: ["user_input", "agent", "parallel_group"].includes(nodeType),
    configStatus: "incomplete",
    runState: "待配置",
    outputMode: "一次性输出",
    toolCount: 0,
    allowQuestion: false,
    rawConfig: { brickType },
  };
}

function orderNodesByEdges(nodes: WorkflowEditorNode[], edges: WorkflowEditorEdge[]) {
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const startNode = nodesById.get(SYSTEM_TRIGGER_ID) ?? nodes[0];
  const ordered: WorkflowEditorNode[] = [];
  const visited = new Set<string>();

  function visit(nodeId: string) {
    const node = nodesById.get(nodeId);
    if (!node || visited.has(nodeId)) {
      return;
    }
    visited.add(nodeId);
    ordered.push(node);
    edges.filter((edge) => edge.source === nodeId).forEach((edge) => visit(edge.target));
  }

  if (startNode) {
    visit(startNode.id);
  }

  nodes.forEach((node) => {
    if (!visited.has(node.id)) {
      ordered.push(node);
    }
  });

  return ordered;
}

function normalizeVisibleNodeOrder(visibleNodes: WorkflowEditorNode[]) {
  return visibleNodes.map((node, index) => ({
    ...node,
    position: { x: (index + 1) * 260, y: 0 },
    data: {
      ...node.data,
      inputVariables: index === 0 ? ["starter"] : visibleNodes[index - 1]?.data.outputVariables ?? [],
    },
  }));
}

function rebuildSequentialEdges(visibleNodes: WorkflowEditorNode[]): WorkflowEditorEdge[] {
  if (visibleNodes.length === 0) {
    return [];
  }

  const edges: WorkflowEditorEdge[] = [
    {
      id: `e_${SYSTEM_TRIGGER_ID}_${visibleNodes[0].id}`,
      source: SYSTEM_TRIGGER_ID,
      target: visibleNodes[0].id,
    },
  ];

  for (let index = 0; index < visibleNodes.length - 1; index += 1) {
    edges.push({
      id: `e_${visibleNodes[index].id}_${visibleNodes[index + 1].id}`,
      source: visibleNodes[index].id,
      target: visibleNodes[index + 1].id,
    });
  }

  return edges;
}

function getBrickType(node: WorkflowEditorNode): WorkflowBrickType {
  return readBrickType(node.data.rawConfig?.brickType, inferBrickTypeFromNodeType(node.data.nodeType));
}

function inferBrickTypeFromNodeType(nodeType: WorkflowNodeType): WorkflowBrickType {
  if (nodeType === "user_input") {
    return "input";
  }
  if (nodeType === "agent") {
    return "agent";
  }
  if (nodeType === "parallel_group" || nodeType === "merge") {
    return "cluster";
  }
  return "delivery";
}

function getDefaultOutputVariable(brickType: WorkflowBrickType, index: number) {
  if (brickType === "input") {
    return index === 0 ? "company_full_name" : `input_${index + 1}`;
  }
  if (brickType === "agent") {
    return index === 1 ? "agent_response" : `agent_response_${index + 1}`;
  }
  if (brickType === "cluster") {
    return index === 2 ? "cluster_result" : `cluster_result_${index + 1}`;
  }
  return "delivery_record";
}

function readInputFields(value: unknown, outputVariables: string[]): InputFieldConfig[] {
  if (Array.isArray(value)) {
    const fields = value.filter(isInputFieldConfig);
    if (fields.length > 0) {
      return fields;
    }
  }

  return outputVariables.length > 0
    ? outputVariables.map((variable, index) => ({
      id: `field_fallback_${index}`,
      label: index === 0 ? "授信公司全称" : `输入字段 ${index + 1}`,
      variable,
      placeholder: index === 0 ? "请输入完整公司名称" : "请输入内容",
    }))
    : [createInputField(0)];
}

function readClusterAgents(value: unknown): ClusterAgentConfig[] {
  if (Array.isArray(value)) {
    const agents = value.filter(isClusterAgentConfig);
    if (agents.length > 0) {
      return agents;
    }
  }

  return [createClusterAgent(0), createClusterAgent(1)];
}

function parseVariableList(value: string) {
  return value.split(",").map((item) => normalizeVariableName(item)).filter(Boolean);
}

function normalizeVariableName(value: string) {
  return value.trim().replace(/\s+/g, "_").replace(/[^a-zA-Z0-9_]/g, "");
}

function cloneRecord(value: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}

function readString(value: unknown, fallback: string): string {
  return typeof value === "string" && value.trim() ? value : fallback;
}

function readBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function readNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function readLiteral<T extends string>(value: unknown, values: readonly T[], fallback: T): T {
  return typeof value === "string" && values.includes(value as T) ? value as T : fallback;
}

function readStringArray(value: unknown, fallback: string[]): string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string") ? value : fallback;
}

function readBrickType(value: unknown, fallback: WorkflowBrickType): WorkflowBrickType {
  return value === "input" || value === "agent" || value === "cluster" || value === "delivery" ? value : fallback;
}

function isInputFieldConfig(value: unknown): value is InputFieldConfig {
  return typeof value === "object"
    && value !== null
    && typeof (value as InputFieldConfig).id === "string"
    && typeof (value as InputFieldConfig).label === "string"
    && typeof (value as InputFieldConfig).variable === "string"
    && typeof (value as InputFieldConfig).placeholder === "string";
}

function isClusterAgentConfig(value: unknown): value is ClusterAgentConfig {
  return typeof value === "object"
    && value !== null
    && typeof (value as ClusterAgentConfig).id === "string"
    && typeof (value as ClusterAgentConfig).name === "string"
    && typeof (value as ClusterAgentConfig).prompt === "string"
    && typeof (value as ClusterAgentConfig).output === "string";
}

function getWorkflowEditorErrorContext(error: unknown, tenantId?: string, workflowId?: string) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, workflowId };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, workflowId };
}
