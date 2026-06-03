import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Select, message } from "antd";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Bot,
  Boxes,
  BrainCircuit,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  Clock3,
  FileText,
  Hash,
  Layers3,
  ListChecks,
  PackageCheck,
  Plus,
  Save,
  Search,
  ServerCog,
  Settings2,
  Tag,
  TextCursorInput,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { WorkbenchGlobalActions } from "../../components/workbench/SurfacePageLayout";
import { AgentumApiError, assetApi, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AssetType, MyAssetRow, SystemCapabilityAssetRow } from "../../types/asset";
import type {
  WorkflowBrickTemplate,
  WorkflowDesignerCatalog,
  WorkflowDraftDetail,
  WorkflowEdgeDraft,
  WorkflowNodeDraft,
  WorkflowNodeType,
  WorkflowVariableDraft,
  WorkflowVariableTemplate,
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
  outputMode: "一次性输出" | "追问确认";
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

type WorkflowBrickType = WorkflowBrickTemplate["brickType"];
type VisibleWorkflowBrickType = Exclude<WorkflowBrickType, "trigger">;

type InputFieldConfig = {
  id: string;
  label: string;
  variable: string;
  placeholder: string;
  defaultValue: string;
};

type ClusterAgentConfig = {
  id: string;
  name: string;
  agentAssetId: string;
  promptTemplateId: string;
  systemPromptTemplateId: string;
  userPromptTemplateId: string;
  skillIds: string[];
  mcpIds: string[];
  systemPrompt: string;
  userPrompt: string;
  output: string;
};

type WorkflowCapabilityOption = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  status: string;
  source: "system" | "mine";
  scope: string;
};

type WorkflowCapabilityState = {
  capabilities: WorkflowCapabilityOption[];
  loading: boolean;
  error: string;
};

type WorkflowIcon = typeof Zap;

type WorkflowEditorPageProps = {
  workflow: WorkflowDraft;
  onBack: () => void;
  onDraftSaved: (draft: WorkflowDraft) => void;
};

const SYSTEM_TRIGGER_ID = "trigger_manual";
const workflowSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const workflowSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;

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

const brickDefinitions: Record<VisibleWorkflowBrickType, {
  label: string;
  description: string;
  icon: typeof TextCursorInput;
  nodeType: WorkflowNodeType;
  accentClass: string;
}> = {
  input: {
    label: "输入节点",
    description: "配置用户需要填写的输入框和输出内容",
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
    description: "编排多个智能体并行执行并汇总成果",
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

export function WorkflowEditorPage({ workflow, onBack, onDraftSaved }: WorkflowEditorPageProps) {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [nodes, setNodes] = useState<WorkflowEditorNode[]>([]);
  const [edges, setEdges] = useState<WorkflowEditorEdge[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState("");
  const [nodeSearchValue, setNodeSearchValue] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [messageApi, messageContextHolder] = message.useMessage();
  const [designerCatalog, setDesignerCatalog] = useState<WorkflowDesignerCatalog | null>(null);
  const [capabilityOptions, setCapabilityOptions] = useState<WorkflowCapabilityOption[]>([]);
  const [capabilitiesLoading, setCapabilitiesLoading] = useState(false);
  const [capabilityError, setCapabilityError] = useState("");
  const [declaredVariables, setDeclaredVariables] = useState<WorkflowVariable[]>([]);
  const [isAddBrickModalOpen, setIsAddBrickModalOpen] = useState(false);
  // 防止双击保存按钮导致并发 API 调用；useState 在同一渲染帧内可能未及时更新，需要额外的 ref 锁。
  const saveLockRef = useRef(false);

  useEffect(() => {
    if (!token || !user?.tenantId) {
      setLoading(false);
      setLoadError("当前账号缺少租户上下文，无法加载工作流草稿");
      return;
    }

    let cancelled = false;
    setLoading(true);
    setLoadError("");

    // 设计态模板由后端统一下发；前端加载草稿后只负责映射成可编辑状态。
    void Promise.all([
      workflowApi.getDraft(user.tenantId, workflow.id, token),
      workflowApi.getDesignerCatalog(user.tenantId, token),
    ])
      .then(([detail, catalog]) => {
        if (cancelled) {
          return;
        }
        setDesignerCatalog(catalog);
        const hasPersistedGraph = detail.nodes.some((node) => node.nodeType !== "trigger" && node.nodeId !== SYSTEM_TRIGGER_ID);
        const nextNodes = hasPersistedGraph ? ensureSystemTrigger(detail.nodes.map(toEditorNode), catalog) : [createNodeFromTemplate(catalog.systemTrigger, 0, [])];
        const nextEdges = hasPersistedGraph ? detail.edges.map(toEditorEdge) : [];
        const nextVariables = detail.variables.length > 0 ? toWorkflowVariables(detail.variables, nextNodes) : buildWorkflowVariables(nextNodes, catalog.variableMetadata);
        setNodes(nextNodes);
        setEdges(nextEdges.length > 0 ? nextEdges : rebuildSequentialEdges(nextNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID)));
        setDeclaredVariables(nextVariables);
        setSelectedNodeId(nextNodes.find((node) => node.id !== SYSTEM_TRIGGER_ID)?.id ?? "");
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        console.warn("[workflow] 工作流草稿加载失败", getWorkflowEditorErrorContext(error, user.tenantId ?? undefined, workflow.id));
        setLoadError(error instanceof AgentumApiError ? error.message : "无法加载工作流草稿");
        setDesignerCatalog(null);
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

  useEffect(() => {
    if (!token || !user?.tenantId) {
      setCapabilityOptions([]);
      setCapabilityError("");
      return;
    }

    let cancelled = false;
    const tenantId = user.tenantId;
    setCapabilitiesLoading(true);
    setCapabilityError("");

    // 流程节点只能引用当前主体可见的能力资产；MCP / Skill 等执行类能力不再使用前端硬编码选项。
    // 循环分页加载全部可引用能力，避免硬编码单页上限导致超出部分的 MCP / Skill 不出现在下拉列表。
    void Promise.all([
      loadAllCapabilityPages((page, size) => assetApi.listSystemCapabilities(tenantId, token, page, size)),
      loadAllCapabilityPages((page, size) => assetApi.listMine(tenantId, token, "", page, size)),
    ])
      .then(([systemItems, myItems]) => {
        if (!cancelled) {
          setCapabilityOptions(buildCapabilityOptions(systemItems as SystemCapabilityAssetRow[], myItems as MyAssetRow[]));
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        console.warn("[workflow] 流程设计能力资产加载失败", getWorkflowEditorErrorContext(error, tenantId, workflow.id));
        setCapabilityError(error instanceof AgentumApiError ? error.message : "无法加载可引用能力");
        setCapabilityOptions([]);
      })
      .finally(() => {
        if (!cancelled) {
          setCapabilitiesLoading(false);
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
    () => declaredVariables.length > 0 ? declaredVariables : buildWorkflowVariables(orderedNodes, designerCatalog?.variableMetadata),
    [declaredVariables, designerCatalog?.variableMetadata, orderedNodes],
  );
  const businessVariables = workflowVariables.filter((variable) => variable.sourceNodeId !== SYSTEM_TRIGGER_ID);
  const availableVariables = workflowVariables.filter((variable) => {
    if (!selectedNode) {
      return true;
    }
    const sourceIndex = visibleNodes.findIndex((node) => node.id === variable.sourceNodeId);

    return sourceIndex >= 0 && sourceIndex < selectedNodeIndex;
  });
  const incompleteNodes = visibleNodes.filter((node) => node.data.configStatus === "incomplete");
  const matchedNodes = visibleNodes.filter((node) => node.data.label.includes(nodeSearchValue.trim()));

  useEffect(() => {
    if (loading) {
      return;
    }

    if (visibleNodes.length === 0 && selectedNodeId) {
      setSelectedNodeId("");
      return;
    }

    if (visibleNodes.length > 0 && !visibleNodes.some((node) => node.id === selectedNodeId)) {
      setSelectedNodeId(visibleNodes[0].id);
    }
  }, [loading, selectedNodeId, visibleNodes]);

  const persistGraph = useCallback(async (nextNodes: WorkflowEditorNode[], nextEdges: WorkflowEditorEdge[]) => {
    if (!token || !user?.tenantId) {
      messageApi.error("当前账号缺少租户上下文，无法保存工作流草稿");
      return;
    }
    if (!designerCatalog) {
      messageApi.error("流程设计模板尚未加载完成，暂时不能保存工作流草稿");
      return;
    }
    // 防止同一渲染帧内双击保存触发并发请求
    if (saveLockRef.current) {
      return;
    }
    saveLockRef.current = true;

    setSaving(true);

    try {
      const nextVariables = buildWorkflowVariables(nextNodes, designerCatalog.variableMetadata);
      const detail = await workflowApi.saveGraph(
        user.tenantId,
        workflow.id,
        token,
        nextNodes.map(toWorkflowNodeDraft),
        nextEdges.map(toWorkflowEdgeDraft),
        nextVariables.map(toWorkflowVariableDraft),
      );
      applyPersistedDetail(detail, designerCatalog, setNodes, setEdges, setSelectedNodeId);
      setDeclaredVariables(toWorkflowVariables(detail.variables, detail.nodes.map(toEditorNode)));
      messageApi.success("流程设计已保存");
      onDraftSaved(detail.draft);
    } catch (error) {
      console.warn("[workflow] 工作流草稿保存失败", getWorkflowEditorErrorContext(error, user.tenantId, workflow.id));
      messageApi.error(error instanceof AgentumApiError ? error.message : "保存工作流草稿失败");
    } finally {
      setSaving(false);
      saveLockRef.current = false;
    }
  }, [designerCatalog, messageApi, onDraftSaved, token, user?.tenantId, workflow.id]);

  function commitVisibleNodes(nextVisibleNodes: WorkflowEditorNode[], nextSelectedNodeId = selectedNodeId) {
    if (!designerCatalog) {
      messageApi.error("流程设计模板尚未加载完成，暂时不能更新编排");
      return;
    }
    const systemTrigger = nodes.find((node) => node.id === SYSTEM_TRIGGER_ID) ?? createNodeFromTemplate(designerCatalog.systemTrigger, 0, []);
    const normalizedVisibleNodes = normalizeVisibleNodeOrder(nextVisibleNodes);
    const nextNodes = [systemTrigger, ...normalizedVisibleNodes];
    const nextEdges = rebuildSequentialEdges(normalizedVisibleNodes);
    setNodes(nextNodes);
    setEdges(nextEdges);
    setDeclaredVariables(buildWorkflowVariables(nextNodes, designerCatalog.variableMetadata));
    setSelectedNodeId(nextSelectedNodeId);
    messageApi.info("本地编排已更新，保存后写入草稿");
  }

  function handleAddBrick(brickType: VisibleWorkflowBrickType) {
    if (!designerCatalog) {
      messageApi.error("流程设计模板尚未加载完成，暂时不能添加积木");
      return;
    }
    const template = designerCatalog.brickTemplates.find((item) => item.brickType === brickType);
    if (!template) {
      messageApi.error("当前积木模板不存在，请刷新后重试");
      return;
    }
    const previousOutputs = visibleNodes.length > 0 ? visibleNodes[visibleNodes.length - 1].data.outputVariables : [];
    const nextNode = createNodeFromTemplate(template, visibleNodes.length + 1, previousOutputs);
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
    if (!window.confirm("确认删除这个积木？删除后需要手动保存才会生效。")) {
      return;
    }
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
    setDeclaredVariables(buildWorkflowVariables(nextNodes, designerCatalog?.variableMetadata));
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
    if (!designerCatalog) {
      messageApi.error("流程设计模板尚未加载完成，暂时不能保存流程");
      return;
    }
    const normalizedVisibleNodes = normalizeVisibleNodeOrder(visibleNodes);
    const systemTrigger = nodes.find((node) => node.id === SYSTEM_TRIGGER_ID) ?? createNodeFromTemplate(designerCatalog.systemTrigger, 0, []);
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
    <div className="flex h-screen flex-col bg-[var(--color-bg-layout)]">
      {messageContextHolder}
      <div className="workflow-editor-toolbar flex flex-wrap items-center gap-3 border-b border-[var(--color-border-light)] px-4 py-2">
        <button type="button" onClick={onBack} className="agent-button h-7 px-2 text-xs">
          <ChevronLeft className="h-3.5 w-3.5" aria-hidden="true" />
          返回
        </button>
        <div className="mr-auto min-w-0">
          <h2 className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{workflow.name}</h2>
        </div>
        <ToolbarMetric icon={ListChecks} label="积木" value={visibleNodes.length.toString()} />
        <ToolbarMetric icon={Settings2} label="待配" value={incompleteNodes.length.toString()} tone={incompleteNodes.length > 0 ? "warning" : "default"} />
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
        <WorkbenchGlobalActions />
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-[280px_minmax(0,1fr)]">
        <main className="workflow-step-sidebar min-h-0 overflow-y-auto border-r border-[var(--color-border-light)] p-3">
          <WorkflowStepBuilder
            nodes={visibleNodes}
            selectedNodeId={selectedNodeId}
            onSelectNode={setSelectedNodeId}
            onOpenAddBrick={() => setIsAddBrickModalOpen(true)}
            onMoveNode={handleMoveNode}
            onDeleteNode={handleDeleteNode}
          />
        </main>

        <div className="min-h-0 overflow-y-auto bg-[var(--color-bg-layout)] p-4">
          {selectedNode ? (
            <NodeConfigPanel
              node={selectedNode}
              availableVariables={availableVariables}
              workflowVariables={workflowVariables}
              capabilities={capabilityOptions}
              capabilitiesLoading={capabilitiesLoading}
              capabilityError={capabilityError}
              onUpdateNode={updateSelectedNode}
              onUpdateConfig={updateSelectedConfig}
              onSave={handleSaveSelectedNode}
              saving={saving}
            />
          ) : (
            <WorkflowOverviewPanel
              nodes={visibleNodes}
              variables={businessVariables}
              incompleteNodes={incompleteNodes}
              onSelectNode={setSelectedNodeId}
              onOpenAddBrick={() => setIsAddBrickModalOpen(true)}
            />
          )}
        </div>
      </div>

      {isAddBrickModalOpen ? (
        <AddBrickModal
          templates={designerCatalog?.brickTemplates ?? []}
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
  onSelectNode,
  onOpenAddBrick,
  onMoveNode,
  onDeleteNode,
}: {
  nodes: WorkflowEditorNode[];
  selectedNodeId: string;
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
    <article className={`workflow-step-row rounded-[var(--radius-md)] border bg-[var(--color-bg-card)] shadow-[var(--shadow-xs)] transition ${selected ? "workflow-step-row--selected border-[var(--color-primary)]" : "border-[var(--color-border-light)]"}`}>
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
            <IconButton label="上移" icon={ArrowUp} disabled={!canMoveUp} onClick={onMoveUp} />
            <IconButton label="下移" icon={ArrowDown} disabled={!canMoveDown} onClick={onMoveDown} />
            <IconButton label="删除" icon={Trash2} onClick={onDelete} tone="danger" />
          </div>
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5 border-t border-[var(--color-border-light)] px-2.5 py-2">
        <TinyBadge>输出 {node.data.outputVariables.length}</TinyBadge>
        {node.data.allowQuestion ? <TinyBadge tone="info">追问</TinyBadge> : null}
      </div>
    </article>
  );
}

function AddBrickModal({
  templates,
  onClose,
  onSelect,
}: {
  templates: WorkflowBrickTemplate[];
  onClose: () => void;
  onSelect: (brickType: VisibleWorkflowBrickType) => void;
}) {
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
            {templates.map((template) => {
              const brickType = template.brickType as VisibleWorkflowBrickType;
              const definition = brickDefinitions[brickType];
              if (!definition) {
                return null;
              }
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
                    <span className="block text-sm font-semibold text-[var(--color-text-primary)]">{template.label}</span>
                    <span className="mt-2 block text-sm leading-6 text-[var(--color-text-secondary)]">{template.description}</span>
                  </span>
                </button>
              );
            })}
          </div>
          {templates.length === 0 ? (
            <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">
              暂未加载到可添加积木模板，请稍后刷新。
            </p>
          ) : null}
        </div>
      </section>
    </div>
  );
}
function NodeConfigPanel({
  node,
  availableVariables,
  workflowVariables,
  capabilities,
  capabilitiesLoading,
  capabilityError,
  onUpdateNode,
  onUpdateConfig,
  onSave,
  saving,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  workflowVariables: WorkflowVariable[];
  capabilities: WorkflowCapabilityOption[];
  capabilitiesLoading: boolean;
  capabilityError: string;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onSave: () => Promise<void>;
  saving: boolean;
}) {
  const brickType = getBrickType(node);
  const definition = brickDefinitions[brickType];
  const Icon = definition.icon;
  const capabilityState = { capabilities, loading: capabilitiesLoading, error: capabilityError };

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

      <div className="p-5">
        <div className="workflow-node-config-surface">
          <BasicInfoPanel
            node={node}
            brickType={brickType}
            availableVariables={availableVariables}
            onUpdateNode={onUpdateNode}
            onUpdateConfig={onUpdateConfig}
          />

          {brickType === "agent" ? (
            <SingleAgentBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "cluster" ? (
            <AgentClusterBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "delivery" ? (
            <DeliveryBrickConfig node={node} workflowVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} />
          ) : null}
        </div>

        <div className="mt-4 flex justify-end">
          <button
            type="button"
            onClick={() => void onSave()}
            disabled={saving}
            className="agent-button agent-button-primary h-9 px-4 text-sm"
          >
            <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
            {saving ? "保存中" : "保存当前积木"}
          </button>
        </div>
      </div>
    </aside>
  );
}

function WorkflowOverviewPanel({
  nodes,
  variables,
  incompleteNodes,
  onSelectNode,
  onOpenAddBrick,
}: {
  nodes: WorkflowEditorNode[];
  variables: WorkflowVariable[];
  incompleteNodes: WorkflowEditorNode[];
  onSelectNode: (nodeId: string) => void;
  onOpenAddBrick: () => void;
}) {
  if (nodes.length === 0) {
    return <EmptyWorkflowGuide onOpenAddBrick={onOpenAddBrick} />;
  }

  return (
    <aside className="mx-auto max-w-5xl space-y-4 rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-5 shadow-[var(--shadow-sm)]" aria-labelledby="workflow-selection-title">
      <div>
        <p className="text-xs text-[var(--color-text-tertiary)]">当前步骤</p>
        <h3 id="workflow-selection-title" className="text-base font-semibold text-[var(--color-text-primary)]">选择左侧积木继续配置</h3>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <OverviewMetric icon={ListChecks} label="步骤" value={String(nodes.length)} />
        <OverviewMetric icon={Zap} label="输出" value={String(variables.length)} />
        <OverviewMetric icon={Settings2} label="待配" value={String(incompleteNodes.length)} />
      </div>
      <PanelGroup title="待配置积木" icon={AlertTriangle}>
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
      <PanelGroup title="输出内容" icon={Zap}>
        <VariableList variables={variables.map((variable) => variable.name)} emptyText="暂无输出内容" />
      </PanelGroup>
    </aside>
  );
}

function EmptyWorkflowGuide({ onOpenAddBrick }: { onOpenAddBrick: () => void }) {
  return (
    <aside className="workflow-empty-guide mx-auto max-w-4xl rounded-[var(--radius-lg)] border border-dashed border-[var(--color-border-light)] p-6">
      <div className="mx-auto max-w-2xl text-center">
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-[var(--radius-lg)] bg-[var(--color-primary-bg)] text-[var(--color-primary)]">
          <Layers3 className="h-6 w-6" aria-hidden="true" />
        </span>
        <h3 className="mt-4 text-base font-semibold text-[var(--color-text-primary)]">从第一个积木开始搭建</h3>
        <p className="mt-2 text-sm leading-6 text-[var(--color-text-secondary)]">
          推荐先添加输入节点收集业务资料，再接单智能体或智能体集群处理，最后用交付节点生成文档、邮件或 OA 结果。
        </p>
        <div className="mt-5 grid gap-3 text-left md:grid-cols-3">
          <BuildGuideCard icon={TextCursorInput} title="输入" detail="定义用户需要填写的字段和输出内容。" />
          <BuildGuideCard icon={Bot} title="处理" detail="选择智能体、Skill、MCP 和提示词模板。" />
          <BuildGuideCard icon={PackageCheck} title="交付" detail="配置最终输出内容和交付方式。" />
        </div>
        <button type="button" onClick={onOpenAddBrick} className="agent-button agent-button-primary mt-6 h-9 px-4 text-sm">
          <Plus className="h-4 w-4" aria-hidden="true" />
          添加第一个积木
        </button>
      </div>
    </aside>
  );
}

function BuildGuideCard({ icon: Icon, title, detail }: { icon: WorkflowIcon; title: string; detail: string }) {
  return (
    <article className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-3">
      <div className="workflow-mini-card-head">
        <span className="workflow-mini-card-icon">
          <Icon className="h-4 w-4" aria-hidden="true" />
        </span>
        <strong className="block text-sm text-[var(--color-text-primary)]">{title}</strong>
      </div>
      <span className="mt-1 block text-xs leading-5 text-[var(--color-text-secondary)]">{detail}</span>
    </article>
  );
}

function BasicInfoPanel({
  node,
  brickType,
  availableVariables,
  onUpdateNode,
  onUpdateConfig,
}: {
  node: WorkflowEditorNode;
  brickType: VisibleWorkflowBrickType;
  availableVariables: WorkflowVariable[];
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
}) {
  return (
    <PanelGroup title="基础信息" icon={Settings2} className={brickType === "input" ? "xl:col-span-2" : undefined}>
      <label className="sys-field">
        <span className="sys-field-label">步骤名称</span>
        <div className="sys-field-input-wrap">
          <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
          <input
            value={node.data.label}
            onChange={(event) => onUpdateNode({ label: event.target.value })}
            className="sys-field-input"
            placeholder="请输入步骤名称"
          />
        </div>
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
      {brickType === "input" ? (
        <InputFieldsManager
          node={node}
          availableVariables={availableVariables}
          onUpdateConfig={onUpdateConfig}
          onUpdateNode={onUpdateNode}
        />
      ) : null}
      {brickType === "agent" ? (
        <OutcomeVariableField
          label="智能体输出内容"
          value={node.data.outputVariables[0] ?? ""}
          placeholder="agent_output"
          onChange={(value) => onUpdateNode({ outputVariables: value ? [normalizeVariableName(value)] : [] })}
        />
      ) : null}
    </PanelGroup>
  );
}

function OutcomeVariableField({
  label,
  value,
  placeholder,
  onChange,
}: {
  label: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <div className="sys-field-input-wrap">
        <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className="sys-field-input"
          placeholder={placeholder}
        />
      </div>
    </label>
  );
}

function InputFieldsManager({
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
  const [editingField, setEditingField] = useState<InputFieldConfig | null>(null);
  const fields = readInputFields(node.data.rawConfig?.inputFields, node.data.outputVariables);

  function commitFields(nextFields: InputFieldConfig[]) {
    onUpdateConfig({ inputFields: nextFields });
    onUpdateNode({ outputVariables: nextFields.map((field) => field.variable).filter(Boolean) });
  }

  return (
    <div className="workflow-input-field-manager">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">输入框</span>
        <button type="button" onClick={() => setEditingField(createInputField(fields.length))} className="agent-button h-8 px-3 text-xs">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          新增输入框
        </button>
      </div>
      <div className="space-y-3">
        {fields.map((field, index) => (
          <article key={field.id} className="workflow-input-field-row">
            <span className="workflow-inline-card-icon">
              <TextCursorInput className="h-4 w-4" aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium text-[var(--color-text-tertiary)]">输入框 {index + 1}</span>
                <TinyBadge>{field.variable}</TinyBadge>
              </div>
              <p className="mt-1 truncate text-sm font-semibold text-[var(--color-text-primary)]">{field.label}</p>
              <p className="mt-1 line-clamp-1 text-xs text-[var(--color-text-secondary)]">{field.placeholder || "未设置占位提示"}</p>
            </div>
            <div className="flex shrink-0 items-center gap-1">
              <button type="button" onClick={() => setEditingField(field)} className="agent-button h-8 px-2 text-xs">编辑</button>
              <IconButton label="删除输入框" icon={Trash2} tone="danger" onClick={() => commitFields(fields.filter((item) => item.id !== field.id))} />
            </div>
          </article>
        ))}
        {fields.length === 0 ? (
          <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">暂无输入框</p>
        ) : null}
      </div>
      {editingField ? (
        <InputFieldModal
          field={editingField}
          availableVariables={availableVariables}
          onClose={() => setEditingField(null)}
          onSave={(field) => {
            const exists = fields.some((item) => item.id === field.id);
            commitFields(exists ? fields.map((item) => item.id === field.id ? field : item) : [...fields, field]);
            setEditingField(null);
          }}
        />
      ) : null}
    </div>
  );
}

function SingleAgentBrickConfig({
  node,
  availableVariables,
  capabilityState,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const [modalOpen, setModalOpen] = useState(false);
  const config = node.data.rawConfig ?? {};
  const agentAssets = filterCapabilities(capabilityState.capabilities, "agent_template");
  const promptAssets = filterCapabilities(capabilityState.capabilities, "prompt_template");
  const mcpAssets = filterCapabilities(capabilityState.capabilities, "mcp");
  const skillAssets = filterCapabilities(capabilityState.capabilities, "skill");
  const selectedMcps = readStringArray(config.mcpIds ?? config.mcpServices, []);
  const selectedSkills = readStringArray(config.skillIds ?? config.skills, []);
  const agentName = findCapabilityName(agentAssets, readString(config.agentAssetId, "custom"), "自定义智能体");
  const systemPromptName = findCapabilityName(promptAssets, readString(config.systemPromptTemplateId, readString(config.promptTemplateId, "none")), "系统提示词自定义");
  const userPromptName = findCapabilityName(promptAssets, readString(config.userPromptTemplateId, "none"), "用户提示词自定义");

  return (
    <PanelGroup title="智能体配置" icon={Bot} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <div className="workflow-config-list-box">
        <div className="workflow-config-list-header">
          <span>智能体</span>
          <button type="button" className="agent-button h-8 px-2 text-xs" onClick={() => setModalOpen(true)}>编辑配置</button>
        </div>
        <div className="workflow-agent-summary-row">
          <button type="button" className="workflow-agent-summary-main" onClick={() => setModalOpen(true)}>
            <span className="workflow-agent-summary-icon"><Bot className="h-4 w-4" aria-hidden="true" /></span>
            <span className="min-w-0 text-left">
              <span className="block truncate text-sm font-semibold text-[var(--color-text-primary)]">{agentName}</span>
              <span className="mt-1 block truncate text-xs text-[var(--color-text-secondary)]">
                {systemPromptName} / {userPromptName} · Skill {selectedSkills.length} · MCP {selectedMcps.length} · {node.data.outputMode}
              </span>
            </span>
          </button>
          <div className="flex shrink-0 items-center gap-1">
            <IconButton
              label="清空智能体配置"
              icon={Trash2}
              tone="danger"
              onClick={() => {
                onUpdateConfig({
                  agentAssetId: "custom",
                  agentSource: "custom",
                  promptTemplateId: "none",
                  systemPromptTemplateId: "none",
                  userPromptTemplateId: "none",
                  systemPrompt: "",
                  userPrompt: "",
                  mcpIds: [],
                  skillIds: [],
                });
                onUpdateNode({ toolCount: 0, outputMode: "一次性输出", allowQuestion: false });
              }}
            />
          </div>
        </div>
      </div>
      {modalOpen ? (
        <SingleAgentConfigModal
          node={node}
          availableVariables={availableVariables}
          agentAssets={agentAssets}
          promptAssets={promptAssets}
          mcpAssets={mcpAssets}
          skillAssets={skillAssets}
          onClose={() => setModalOpen(false)}
          onSave={(nextConfig, patch) => {
            onUpdateConfig(nextConfig);
            onUpdateNode(patch);
            setModalOpen(false);
          }}
        />
      ) : null}
    </PanelGroup>
  );
}

function AgentClusterBrickConfig({
  node,
  availableVariables,
  capabilityState,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const [editingAgent, setEditingAgent] = useState<ClusterAgentConfig | null>(null);
  const agents = readClusterAgents(config.clusterAgents);
  const agentAssets = filterCapabilities(capabilityState.capabilities, "agent_template");
  const promptAssets = filterCapabilities(capabilityState.capabilities, "prompt_template");
  const mcpAssets = filterCapabilities(capabilityState.capabilities, "mcp");
  const skillAssets = filterCapabilities(capabilityState.capabilities, "skill");

  function commitAgents(nextAgents: ClusterAgentConfig[]) {
    onUpdateConfig({ clusterAgents: nextAgents });
    onUpdateNode({ toolCount: nextAgents.length, outputVariables: nextAgents.map((agent) => agent.output).filter(Boolean) });
  }

  return (
    <PanelGroup title="智能体集群配置" icon={Layers3} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <div className="workflow-config-list-box">
        <div className="workflow-config-list-header">
          <span>集群智能体</span>
          <button type="button" onClick={() => setEditingAgent(createClusterAgent(agents.length))} className="agent-button agent-button-primary h-8 px-3 text-xs">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增智能体
          </button>
        </div>
        <SelectLikeField
          label="执行方式"
          icon={Layers3}
          value={readString(config.executionMode, "parallel")}
          options={[{ value: "parallel", label: "并行执行" }]}
          onChange={(value) => onUpdateConfig({ executionMode: value })}
        />
        <div className="workflow-cluster-agent-list">
          {agents.map((agent, index) => (
            <article key={agent.id} className="workflow-cluster-agent-row">
              <div className="workflow-cluster-agent-index">
                <Bot className="h-4 w-4" aria-hidden="true" />
              </div>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{agent.name}</p>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  <TinyBadge>智能体 {index + 1}</TinyBadge>
                  <TinyBadge>{agent.output}</TinyBadge>
                  <TinyBadge tone="info">Skill {agent.skillIds.length}</TinyBadge>
                  <TinyBadge tone="info">MCP {agent.mcpIds.length}</TinyBadge>
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-1">
                <button type="button" onClick={() => setEditingAgent(agent)} className="agent-button h-8 px-2 text-xs">编辑</button>
                <IconButton label="删除智能体" icon={Trash2} tone="danger" onClick={() => commitAgents(agents.filter((item) => item.id !== agent.id))} />
              </div>
            </article>
          ))}
          {agents.length === 0 ? (
            <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">暂无智能体</p>
          ) : null}
        </div>
      </div>
      {editingAgent ? (
        <ClusterAgentModal
          agent={editingAgent}
          availableVariables={availableVariables}
          agentAssets={agentAssets}
          promptAssets={promptAssets}
          mcpAssets={mcpAssets}
          skillAssets={skillAssets}
          onClose={() => setEditingAgent(null)}
          onSave={(agent) => {
            const exists = agents.some((item) => item.id === agent.id);
            commitAgents(exists ? agents.map((item) => item.id === agent.id ? agent : item) : [...agents, agent]);
            setEditingAgent(null);
          }}
        />
      ) : null}
    </PanelGroup>
  );
}

function DeliveryBrickConfig({
  node,
  workflowVariables,
  capabilityState,
  onUpdateConfig,
}: {
  node: WorkflowEditorNode;
  workflowVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const deliveryAssets = filterCapabilities(capabilityState.capabilities, "delivery");
  const deliveryMode = readString(config.deliveryMode, "capability");
  const isDirectDelivery = deliveryMode === "direct";
  const deliveryTargetDefault = "说明交付目标、模板和确认方式。";
  const deliveryTargetPlaceholder = isDirectDelivery
    ? "配置交付正文，运行时将作为文本直接返回。"
    : "配置交付目标、模板和确认方式，运行时将作为交付输出。";

  return (
    <PanelGroup title="交付配置" icon={PackageCheck} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <div className="grid gap-4 lg:grid-cols-2">
        <SelectLikeField
          label="交付模式"
          icon={PackageCheck}
          value={deliveryMode}
          options={[
            { value: "capability", label: "使用交付能力" },
            { value: "direct", label: "直接输出交付" },
          ]}
          onChange={(value) => onUpdateConfig({ deliveryMode: value })}
        />
        {deliveryMode === "capability" ? (
        <CapabilitySelectField
          label="交付能力"
          icon={PackageCheck}
          value={readString(config.deliveryCapabilityId, "none")}
          emptyValue="none"
          emptyLabel="暂不绑定交付能力"
            options={deliveryAssets}
            onChange={(value) => onUpdateConfig({ deliveryCapabilityId: value })}
          />
        ) : null}
      </div>
      <PromptEditor
        label="交付配置"
        value={readString(config.deliveryTarget, deliveryTargetDefault)}
        availableVariables={workflowVariables}
        onChange={(value) => onUpdateConfig({ deliveryTarget: value })}
        placeholder={deliveryTargetPlaceholder}
      />
    </PanelGroup>
  );
}

function CapabilitySelectField({
  label,
  icon: Icon = Boxes,
  value,
  emptyValue,
  emptyLabel,
  options,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  value: string;
  emptyValue: string;
  emptyLabel: string;
  options: WorkflowCapabilityOption[];
  onChange: (value: string) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={<Icon className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={value}
        options={[
          { value: emptyValue, label: emptyLabel },
          ...options.map((option) => ({
            value: option.id,
            label: `${option.name} · ${option.version} · ${formatAssetSource(option)}`,
          })),
        ]}
        onChange={onChange}
      />
      {options.length === 0 ? <span className="sys-field-hint">暂无可选能力资产</span> : null}
    </label>
  );
}

function CapabilityMultiSelectField({
  label,
  icon: Icon = Boxes,
  options,
  selectedIds,
  placeholder,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  options: WorkflowCapabilityOption[];
  selectedIds: string[];
  placeholder: string;
  onChange: (values: string[]) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        mode="multiple"
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={<Icon className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={selectedIds}
        placeholder={placeholder}
        options={options.map((option) => ({
          value: option.id,
          label: `${option.name} · ${formatAssetSource(option)}`,
        }))}
        onChange={(values) => onChange(values)}
      />
      {options.length === 0 ? <span className="sys-field-hint">暂无可选能力资产</span> : null}
    </label>
  );
}

function CapabilityStateBanner({ state }: { state: WorkflowCapabilityState }) {
  if (state.loading) {
    return <p className="workflow-capability-state">正在加载可引用能力...</p>;
  }

  if (state.error) {
    return <p className="workflow-capability-state workflow-capability-state--danger">{state.error}</p>;
  }

  if (state.capabilities.length === 0) {
    return <p className="workflow-capability-state">当前账号暂无可引用能力。请先在系统管理把能力放入租户能力池，再由租户管理分配给当前用户、部门或角色；也可以在能力资产中创建自己的智能体或提示词模板。</p>;
  }

  return null;
}

function SelectLikeField({
  label,
  icon: Icon,
  value,
  options,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  value: string;
  options: Array<string | { value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  const normalizedOptions = options.map((option) => typeof option === "string" ? { value: option, label: option } : option);
  const effectiveOptions = value && !normalizedOptions.some((option) => option.value === value)
    ? [{ value, label: value }, ...normalizedOptions]
    : normalizedOptions;

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={Icon ? <Icon className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" /> : undefined}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={value}
        options={effectiveOptions}
        onChange={onChange}
      />
    </label>
  );
}

function PromptEditor({
  label,
  value,
  availableVariables,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  availableVariables: WorkflowVariable[];
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="sys-field-textarea workflow-prompt-textarea"
        placeholder={placeholder ?? "可以使用 {{输出内容标识}} 引用之前步骤内容"}
      />
      <VariableReferenceBar
        variables={availableVariables}
        onPick={(variable) => onChange(`${value}${value.endsWith(" ") || value.length === 0 ? "" : " "}{{${variable}}}`)}
      />
    </label>
  );
}

function VariableReferenceBar({
  variables,
  onPick,
}: {
  variables: WorkflowVariable[];
  onPick: (variable: string) => void;
}) {
  if (variables.length === 0) {
    return null;
  }

  return (
    <div className="workflow-variable-reference-bar">
      {variables.map((variable) => (
        <button key={`${variable.sourceNodeId}-${variable.name}`} type="button" onClick={() => onPick(variable.name)}>
          {`{{${variable.name}}}`}
        </button>
      ))}
    </div>
  );
}

function PromptTemplateEditor({
  label,
  templateLabel,
  templateValue,
  templateEmptyLabel,
  promptAssets,
  value,
  availableVariables,
  onTemplateChange,
  onChange,
}: {
  label: string;
  templateLabel: string;
  templateValue: string;
  templateEmptyLabel: string;
  promptAssets: WorkflowCapabilityOption[];
  value: string;
  availableVariables: WorkflowVariable[];
  onTemplateChange: (value: string) => void;
  onChange: (value: string) => void;
}) {
  return (
    <div className="workflow-prompt-section">
      <CapabilitySelectField
        label={templateLabel}
        icon={FileText}
        value={templateValue}
        emptyValue="none"
        emptyLabel={templateEmptyLabel}
        options={promptAssets}
        onChange={onTemplateChange}
      />
      <PromptEditor
        label={label}
        value={value}
        availableVariables={availableVariables}
        onChange={onChange}
      />
    </div>
  );
}

function InputFieldModal({
  field,
  availableVariables,
  onClose,
  onSave,
}: {
  field: InputFieldConfig;
  availableVariables: WorkflowVariable[];
  onClose: () => void;
  onSave: (field: InputFieldConfig) => void;
}) {
  const [draft, setDraft] = useState<InputFieldConfig>(field);

  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section className="sys-modal workflow-config-modal" aria-labelledby="input-field-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>输入信息</div>
            <span id="input-field-modal-title" className="sys-modal-title">配置输入框</span>
          </div>
          <button className="sys-modal-close" onClick={onClose} aria-label="关闭输入框配置"><X size={18} /></button>
        </div>
        <div className="sys-modal-body">
          <label className="sys-field">
            <span className="sys-field-label">显示名称</span>
            <div className="sys-field-input-wrap">
              <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
              <input value={draft.label} onChange={(event) => setDraft({ ...draft, label: event.target.value })} className="sys-field-input" />
            </div>
          </label>
          <label className="sys-field">
            <span className="sys-field-label">输出内容标识</span>
            <div className="sys-field-input-wrap">
              <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
              <input value={draft.variable} onChange={(event) => setDraft({ ...draft, variable: normalizeVariableName(event.target.value) })} className="sys-field-input" />
            </div>
          </label>
          <label className="sys-field">
            <span className="sys-field-label">占位提示</span>
            <div className="sys-field-input-wrap">
              <TextCursorInput size={16} className="sys-field-prefix" aria-hidden="true" />
              <input value={draft.placeholder} onChange={(event) => setDraft({ ...draft, placeholder: event.target.value })} className="sys-field-input" />
            </div>
          </label>
          <PromptEditor
            label="默认内容"
            value={draft.defaultValue}
            availableVariables={availableVariables}
            onChange={(value) => setDraft({ ...draft, defaultValue: value })}
            placeholder="可用 {{输出内容标识}} 引用之前步骤内容"
          />
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onSave({ ...draft, variable: normalizeVariableName(draft.variable) || "input_value" })}>保存</button>
        </div>
      </section>
    </div>
  );
}

function SingleAgentConfigModal({
  node,
  availableVariables,
  agentAssets,
  promptAssets,
  mcpAssets,
  skillAssets,
  onClose,
  onSave,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  agentAssets: WorkflowCapabilityOption[];
  promptAssets: WorkflowCapabilityOption[];
  mcpAssets: WorkflowCapabilityOption[];
  skillAssets: WorkflowCapabilityOption[];
  onClose: () => void;
  onSave: (config: Record<string, unknown>, patch: Partial<EditorNodeData>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const [draft, setDraft] = useState({
    agentAssetId: readString(config.agentAssetId, "custom"),
    systemPromptTemplateId: readString(config.systemPromptTemplateId, readString(config.promptTemplateId, "none")),
    userPromptTemplateId: readString(config.userPromptTemplateId, "none"),
    systemPrompt: readString(config.systemPrompt, "请配置这个智能体的角色、任务边界和输出要求。"),
    userPrompt: readString(config.userPrompt, "请基于已产生的可引用内容完成本步骤任务。"),
    mcpIds: readStringArray(config.mcpIds ?? config.mcpServices, []),
    skillIds: readStringArray(config.skillIds ?? config.skills, []),
    outputMode: node.data.outputMode,
    allowQuestion: node.data.allowQuestion,
  });

  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section className="sys-modal workflow-config-modal workflow-agent-modal" aria-labelledby="single-agent-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>单智能体</div>
            <span id="single-agent-modal-title" className="sys-modal-title">配置智能体</span>
          </div>
          <button className="sys-modal-close" onClick={onClose} aria-label="关闭智能体配置"><X size={18} /></button>
        </div>
        <div className="sys-modal-body workflow-agent-modal-body">
          <div className="workflow-modal-section grid gap-4 lg:grid-cols-2">
            <CapabilitySelectField
              label="智能体模板"
              icon={Bot}
              value={draft.agentAssetId}
              emptyValue="custom"
              emptyLabel="自定义智能体"
              options={agentAssets}
              onChange={(value) => setDraft({ ...draft, agentAssetId: value })}
            />
          </div>
          <PromptTemplateEditor
            label="系统提示词"
            templateLabel="系统提示词模板"
            templateValue={draft.systemPromptTemplateId}
            templateEmptyLabel="系统提示词自定义"
            promptAssets={promptAssets}
            value={draft.systemPrompt}
            availableVariables={availableVariables}
            onTemplateChange={(value) => setDraft({ ...draft, systemPromptTemplateId: value })}
            onChange={(value) => setDraft({ ...draft, systemPrompt: value })}
          />
          <PromptTemplateEditor
            label="用户提示词"
            templateLabel="用户提示词模板"
            templateValue={draft.userPromptTemplateId}
            templateEmptyLabel="用户提示词自定义"
            promptAssets={promptAssets}
            value={draft.userPrompt}
            availableVariables={availableVariables}
            onTemplateChange={(value) => setDraft({ ...draft, userPromptTemplateId: value })}
            onChange={(value) => setDraft({ ...draft, userPrompt: value })}
          />
          <div className="workflow-modal-section workflow-modal-section--spacious grid gap-4 lg:grid-cols-2">
            <CapabilityMultiSelectField
              label="MCP"
              icon={ServerCog}
              options={mcpAssets}
              selectedIds={draft.mcpIds}
              placeholder="选择 MCP"
              onChange={(values) => setDraft({ ...draft, mcpIds: values })}
            />
            <CapabilityMultiSelectField
              label="Skill"
              icon={BrainCircuit}
              options={skillAssets}
              selectedIds={draft.skillIds}
              placeholder="选择 Skill"
              onChange={(values) => setDraft({ ...draft, skillIds: values })}
            />
          </div>
          <div className="workflow-modal-section grid gap-3 lg:grid-cols-2">
            <label className="workflow-toggle-row">
              <span>允许重新生成</span>
              <input
                type="checkbox"
                checked={draft.outputMode === "追问确认"}
                onChange={(event) => setDraft({ ...draft, outputMode: event.target.checked ? "追问确认" : "一次性输出" })}
              />
            </label>
            <label className="workflow-toggle-row">
              <span>允许追问修改</span>
              <input
                type="checkbox"
                checked={draft.allowQuestion}
                onChange={(event) => setDraft({ ...draft, allowQuestion: event.target.checked })}
              />
            </label>
          </div>
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            onClick={() => onSave({
              agentAssetId: draft.agentAssetId,
              agentSource: draft.agentAssetId === "custom" ? "custom" : "asset",
              promptTemplateId: draft.systemPromptTemplateId,
              systemPromptTemplateId: draft.systemPromptTemplateId,
              userPromptTemplateId: draft.userPromptTemplateId,
              systemPrompt: draft.systemPrompt,
              userPrompt: draft.userPrompt,
              mcpIds: draft.mcpIds,
              skillIds: draft.skillIds,
            }, {
              toolCount: draft.mcpIds.length + draft.skillIds.length,
              outputMode: draft.outputMode,
              allowQuestion: draft.allowQuestion,
            })}
          >
            保存
          </button>
        </div>
      </section>
    </div>
  );
}

function ClusterAgentModal({
  agent,
  availableVariables,
  agentAssets,
  promptAssets,
  mcpAssets,
  skillAssets,
  onClose,
  onSave,
}: {
  agent: ClusterAgentConfig;
  availableVariables: WorkflowVariable[];
  agentAssets: WorkflowCapabilityOption[];
  promptAssets: WorkflowCapabilityOption[];
  mcpAssets: WorkflowCapabilityOption[];
  skillAssets: WorkflowCapabilityOption[];
  onClose: () => void;
  onSave: (agent: ClusterAgentConfig) => void;
}) {
  const [draft, setDraft] = useState<ClusterAgentConfig>(agent);

  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section className="sys-modal workflow-config-modal workflow-agent-modal" aria-labelledby="cluster-agent-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>智能体集群</div>
            <span id="cluster-agent-modal-title" className="sys-modal-title">配置集群智能体</span>
          </div>
          <button className="sys-modal-close" onClick={onClose} aria-label="关闭智能体配置"><X size={18} /></button>
        </div>
        <div className="sys-modal-body workflow-agent-modal-body">
          <div className="workflow-modal-section grid gap-4 lg:grid-cols-2">
            <label className="sys-field">
              <span className="sys-field-label">智能体名称</span>
              <div className="sys-field-input-wrap">
                <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
                <input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} className="sys-field-input" />
              </div>
            </label>
            <OutcomeVariableField
              label="输出内容标识"
              value={draft.output}
              placeholder="agent_output"
              onChange={(value) => setDraft({ ...draft, output: normalizeVariableName(value) })}
            />
            <CapabilitySelectField
              label="智能体模板"
              icon={Bot}
              value={draft.agentAssetId || "custom"}
              emptyValue="custom"
              emptyLabel="自定义智能体"
              options={agentAssets}
              onChange={(value) => setDraft({ ...draft, agentAssetId: value })}
            />
          </div>
          <PromptTemplateEditor
            label="系统提示词"
            templateLabel="系统提示词模板"
            templateValue={draft.systemPromptTemplateId || draft.promptTemplateId || "none"}
            templateEmptyLabel="系统提示词自定义"
            promptAssets={promptAssets}
            value={draft.systemPrompt}
            availableVariables={availableVariables}
            onTemplateChange={(value) => setDraft({ ...draft, systemPromptTemplateId: value, promptTemplateId: value })}
            onChange={(value) => setDraft({ ...draft, systemPrompt: value })}
          />
          <PromptTemplateEditor
            label="用户提示词"
            templateLabel="用户提示词模板"
            templateValue={draft.userPromptTemplateId || "none"}
            templateEmptyLabel="用户提示词自定义"
            promptAssets={promptAssets}
            value={draft.userPrompt}
            availableVariables={availableVariables}
            onTemplateChange={(value) => setDraft({ ...draft, userPromptTemplateId: value })}
            onChange={(value) => setDraft({ ...draft, userPrompt: value })}
          />
          <div className="workflow-modal-section workflow-modal-section--spacious grid gap-4 lg:grid-cols-2">
            <CapabilityMultiSelectField
              label="Skill"
              icon={BrainCircuit}
              options={skillAssets}
              selectedIds={draft.skillIds}
              placeholder="选择 Skill"
              onChange={(values) => setDraft({ ...draft, skillIds: values })}
            />
            <CapabilityMultiSelectField
              label="MCP"
              icon={ServerCog}
              options={mcpAssets}
              selectedIds={draft.mcpIds}
              placeholder="选择 MCP"
              onChange={(values) => setDraft({ ...draft, mcpIds: values })}
            />
          </div>
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onSave({ ...draft, output: normalizeVariableName(draft.output) || "agent_output" })}>保存</button>
        </div>
      </section>
    </div>
  );
}

function PanelGroup({ title, icon: Icon, children, className = "" }: { title: string; icon?: WorkflowIcon; children: ReactNode; className?: string }) {
  return (
    <section className={`workflow-config-panel-group rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] px-3 py-3 ${className}`}>
      <h4 className="workflow-config-panel-title">
        {Icon ? (
          <span className="workflow-config-panel-title-icon">
            <Icon className="h-4 w-4" aria-hidden="true" />
          </span>
        ) : null}
        <span>{title}</span>
      </h4>
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

function OverviewMetric({ icon: Icon, label, value }: { icon: WorkflowIcon; label: string; value: string }) {
  return (
    <div className="workflow-overview-metric">
      <span className="workflow-overview-metric-icon">
        <Icon className="h-4 w-4" aria-hidden="true" />
      </span>
      <span className="min-w-0 text-left">
        <p className="text-xs text-[var(--color-text-tertiary)]">{label}</p>
        <p className="mt-1 text-lg font-semibold text-[var(--color-text-primary)]">{value}</p>
      </span>
    </div>
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
    <div className="flex h-screen flex-col">
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

function buildCapabilityOptions(systemAssets: SystemCapabilityAssetRow[], myAssets: MyAssetRow[]): WorkflowCapabilityOption[] {
  const systemOptions = systemAssets.map((asset) => ({
    id: asset.id,
    assetType: asset.assetType,
    name: asset.name,
    code: asset.code,
    version: asset.version,
    status: asset.status,
    source: "system" as const,
    scope: asset.assignmentScope,
  }));
  const myOptions = myAssets
    .filter((asset) => asset.assetType === "agent_template" || asset.assetType === "prompt_template")
    .map((asset) => ({
      id: asset.id,
      assetType: asset.assetType,
      name: asset.name,
      code: asset.code,
      version: asset.version,
      status: asset.status,
      source: "mine" as const,
      scope: asset.visibility === "tenant" ? "租户内复用" : "本人维护",
    }));

  return [...systemOptions, ...myOptions].sort((left, right) => {
    if (left.assetType !== right.assetType) {
      return left.assetType.localeCompare(right.assetType);
    }
    return left.name.localeCompare(right.name, "zh-CN");
  });
}

function filterCapabilities(capabilities: WorkflowCapabilityOption[], assetType: AssetType) {
  return capabilities.filter((capability) => capability.assetType === assetType);
}

function formatAssetSource(option: WorkflowCapabilityOption) {
  return option.source === "system" ? "对我开放" : "我的能力";
}

function findCapabilityName(options: WorkflowCapabilityOption[], id: string, fallback: string) {
  if (id === "custom" || id === "none") {
    return fallback;
  }
  return options.find((option) => option.id === id)?.name ?? fallback;
}

function createNodeFromTemplate(template: WorkflowBrickTemplate, index: number, inputVariables: string[] = []): WorkflowEditorNode {
  const brickType = template.brickType;
  const id = brickType === "trigger" ? SYSTEM_TRIGGER_ID : `${brickType}_${Date.now().toString(36)}_${index}`;
  let outputVariables = buildTemplateOutputVariables(template, index);
  const rawConfig = cloneRecord(template.defaultConfig);
  const effectiveInputVariables = brickType === "trigger" ? template.defaultInputVariables : (inputVariables.length > 0 ? inputVariables : template.defaultInputVariables);

  if (brickType === "input") {
    const inputFields = readInputFields(rawConfig.inputFields, outputVariables);
    rawConfig.inputFields = inputFields;
    outputVariables = inputFields.map((field) => field.variable);
  }

  if (brickType === "cluster") {
    const clusterAgents = readClusterAgents(rawConfig.clusterAgents);
    rawConfig.clusterAgents = clusterAgents;
    rawConfig.executionMode = readString(rawConfig.executionMode, "parallel");
    outputVariables = clusterAgents.map((agent) => agent.output);
  }

  return {
    id,
    position: { x: index * 260, y: 0 },
    data: {
      label: template.defaultName,
      typeLabel: template.label,
      nodeType: template.nodeType,
      summary: template.defaultSummary,
      inputVariables: effectiveInputVariables,
      outputVariables,
      pausePoint: false,
      configStatus: brickType === "trigger" ? "complete" : "incomplete",
      runState: template.runState,
      outputMode: template.outputMode,
      toolCount: template.toolCount,
      allowQuestion: template.allowQuestion,
      rawConfig: {
        ...rawConfig,
        brickType,
      },
    },
  };
}

function buildTemplateOutputVariables(template: WorkflowBrickTemplate, index: number) {
  if (template.brickType === "trigger") {
    return [...template.defaultOutputVariables];
  }
  if (index <= 1 && template.firstOutputVariable) {
    return [template.firstOutputVariable];
  }

  return template.outputPrefix ? [`${template.outputPrefix}_${index}`] : [...template.defaultOutputVariables];
}

function createInputField(index: number): InputFieldConfig {
  return {
    id: `field_${Date.now().toString(36)}_${index}`,
    label: index === 0 ? "业务输入" : `输入字段 ${index + 1}`,
    variable: `input_${index + 1}`,
    placeholder: "请输入内容",
    defaultValue: "",
  };
}

function createClusterAgent(index: number): ClusterAgentConfig {
  return {
    id: `cluster_agent_${Date.now().toString(36)}_${index}`,
    name: `子智能体 ${index + 1}`,
    agentAssetId: "custom",
    promptTemplateId: "none",
    systemPromptTemplateId: "none",
    userPromptTemplateId: "none",
    skillIds: [],
    mcpIds: [],
    systemPrompt: "请配置这个智能体的角色、任务边界和输出要求。",
    userPrompt: "请基于已产生的可引用内容完成本智能体任务。",
    output: `agent_${index + 1}_output`,
  };
}

function ensureSystemTrigger(nextNodes: WorkflowEditorNode[], catalog: WorkflowDesignerCatalog) {
  if (nextNodes.some((node) => node.id === SYSTEM_TRIGGER_ID)) {
    return nextNodes;
  }

  return [createNodeFromTemplate(catalog.systemTrigger, 0, []), ...nextNodes];
}

function toEditorNode(node: WorkflowNodeDraft): WorkflowEditorNode {
  const config = node.config ?? {};
  const brickType = readBrickType(config.brickType, inferBrickTypeFromNodeType(node.nodeType));
  const fallback = buildFallbackNodeData(node.nodeType, brickType);

  return {
    id: node.nodeId,
    position: { x: node.positionX, y: node.positionY },
    data: {
      label: node.name,
      typeLabel: readString(config.typeLabel, fallback.typeLabel),
      nodeType: node.nodeType,
      summary: readString(config.summary, fallback.summary),
      inputVariables: [...(node.inputVariables ?? [])],
      outputVariables: [...(node.outputVariables ?? [])],
      pausePoint: false,
      configStatus: readLiteral(config.configStatus, ["complete", "incomplete"], fallback.configStatus),
      runState: readLiteral(config.runState, ["未开始", "等待输入", "执行中", "等待审核", "已完成", "待配置"], fallback.runState),
      outputMode: readLiteral(config.outputMode, ["一次性输出", "追问确认"], fallback.outputMode),
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
      pausePoint: false,
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
  catalog: WorkflowDesignerCatalog,
  setNodes: (nodes: WorkflowEditorNode[]) => void,
  setEdges: (edges: WorkflowEditorEdge[]) => void,
  setSelectedNodeId: (updater: string | ((currentSelection: string) => string)) => void,
) {
  const nextNodes = ensureSystemTrigger(detail.nodes.map(toEditorNode), catalog);
  const nextEdges = detail.edges.map(toEditorEdge);
  setNodes(nextNodes);
  setEdges(nextEdges.length > 0 ? nextEdges : rebuildSequentialEdges(nextNodes.filter((node) => node.id !== SYSTEM_TRIGGER_ID)));
  setSelectedNodeId((currentSelection) => nextNodes.some((node) => node.id === currentSelection) ? currentSelection : "");
}

function buildWorkflowVariables(nodes: WorkflowEditorNode[], metadataByName: Record<string, WorkflowVariableTemplate> = {}): WorkflowVariable[] {
  return nodes.flatMap((node) =>
    node.data.outputVariables.map((name) => {
      const metadata = metadataByName[name] ?? {
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
  const visibleDefinition = brickType === "trigger" ? null : brickDefinitions[brickType];
  return {
    label: "未命名积木",
    typeLabel: visibleDefinition?.label ?? nodeTypeLabels[nodeType],
    nodeType,
    summary: "请在右侧配置这个积木的业务目标和参数。",
    inputVariables: [],
    outputVariables: [],
    pausePoint: false,
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
  const previousVariables = new Set<string>();

  return visibleNodes.map((node, index) => {
    const inputVariables = collectReferencedVariables(node, previousVariables);
    const normalizedNode = {
      ...node,
      position: { x: (index + 1) * 260, y: 0 },
      data: {
        ...node.data,
        inputVariables,
      },
    };
    node.data.outputVariables.forEach((variable) => {
      if (variable) {
        previousVariables.add(variable);
      }
    });
    return normalizedNode;
  });
}

function collectReferencedVariables(node: WorkflowEditorNode, previousVariables: Set<string>) {
  const references = new Set<string>();
  const allowed = previousVariables;
  const serializedConfig = JSON.stringify(node.data.rawConfig ?? {});
  for (const match of serializedConfig.matchAll(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g)) {
    const variable = match[1];
    if (allowed.has(variable)) {
      references.add(variable);
    }
  }

  return [...references];
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

function getBrickType(node: WorkflowEditorNode): VisibleWorkflowBrickType {
  const brickType = readBrickType(node.data.rawConfig?.brickType, inferBrickTypeFromNodeType(node.data.nodeType));
  return brickType === "trigger" ? "delivery" : brickType;
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
  if (nodeType === "trigger") {
    return "trigger";
  }
  return "delivery";
}

function readInputFields(value: unknown, outputVariables: string[]): InputFieldConfig[] {
  if (Array.isArray(value)) {
    const fields = value.filter(isInputFieldConfig);
    if (fields.length > 0) {
      return fields.map((field) => ({ ...field, defaultValue: readString(field.defaultValue, "") }));
    }
  }

  return outputVariables.length > 0
    ? outputVariables.map((variable, index) => ({
      id: `field_fallback_${index}`,
      label: index === 0 ? "业务输入" : `输入字段 ${index + 1}`,
      variable,
      placeholder: "请输入内容",
      defaultValue: "",
    }))
    : [createInputField(0)];
}

function readClusterAgents(value: unknown): ClusterAgentConfig[] {
  if (Array.isArray(value)) {
    const agents = value.filter(isClusterAgentConfig);
    if (agents.length > 0) {
      return agents.map((agent) => ({
        ...agent,
        agentAssetId: readString(agent.agentAssetId, "custom"),
        promptTemplateId: readString(agent.promptTemplateId, "none"),
        systemPromptTemplateId: readString(agent.systemPromptTemplateId, readString(agent.promptTemplateId, "none")),
        userPromptTemplateId: readString(agent.userPromptTemplateId, "none"),
        skillIds: readStringArray(agent.skillIds, []),
        mcpIds: readStringArray(agent.mcpIds, []),
        systemPrompt: readString(agent.systemPrompt, "请配置这个智能体的角色、任务边界和输出要求。"),
        userPrompt: readString(agent.userPrompt ?? (agent as unknown as { prompt?: unknown }).prompt, "请基于已产生的可引用内容完成本智能体任务。"),
      }));
    }
  }

  // 集群节点默认空列表，让用户按需添加子智能体，避免自动占位污染变量声明。
  return [];
}

function normalizeVariableName(value: string) {
  // 后端 WorkflowVariableDeclarationValidator 正则 ^[a-z][a-z0-9_]*$ 只接受纯小写；前端统一转小写，避免保存时 400。
  return value.trim().toLowerCase().replace(/\s+/g, "_").replace(/[^a-z0-9_]/g, "");
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
  return value === "trigger" || value === "input" || value === "agent" || value === "cluster" || value === "delivery" ? value : fallback;
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
    && typeof (value as ClusterAgentConfig).output === "string";
}

function getWorkflowEditorErrorContext(error: unknown, tenantId?: string, workflowId?: string) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, workflowId };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, workflowId };
}

// 能力资产分页加载，确保获取全部可引用能力，避免硬编码单页上限导致选项截断。
async function loadAllCapabilityPages<T extends { items: unknown[]; total: number }>(
  fetcher: (page: number, size: number) => Promise<T>,
  size = 100,
): Promise<unknown[]> {
  const firstPage = await fetcher(1, size);
  const allItems = [...firstPage.items];
  const totalPages = Math.ceil(firstPage.total / size);
  for (let page = 2; page <= totalPages; page++) {
    const nextPage = await fetcher(page, size);
    allItems.push(...nextPage.items);
  }
  return allItems;
}
