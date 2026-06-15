import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { App, Select, Tooltip } from "antd";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Bot,
  Boxes,
  BrainCircuit,
  ChevronDown,
  ChevronLeft,
  CheckCircle2,
  Clock3,
  Download,
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
  Type,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { WorkbenchGlobalActions } from "../../components/workbench/SurfacePageLayout";
import { SysImpactConfirmModal } from "../../components/common/SysImpactConfirmModal";
import { DocumentDeliveryStyleSections } from "../../components/document/DocumentDeliveryStyleSections";
import type { DocumentDeliveryStyleValues } from "../../constants/documentDeliveryStyleOptions";
import { AgentumApiError, assetApi, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AssetType, MyAssetRow, SystemCapabilityAssetRow } from "../../types/asset";
import type {
  FileDownloadResponse,
  WordDocumentPreviewRequest,
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
import {
  DEFAULT_CLUSTER_USER_PROMPT,
  DEFAULT_SYSTEM_PROMPT,
  DEFAULT_USER_PROMPT,
  formatWorkflowSaveError,
  mergePersistedNodeConfigs,
  normalizeWorkflowNodeConfig,
  validateCustomPromptConfiguration,
} from "./workflowPromptDefaults";
import {
  applyValidatedConfigStatus,
  buildWorkflowNodeValidationMap,
  collectRuntimeTemplateTextFields,
  collectRuntimeTemplateVariableNames,
  describeDeleteNodeVariableImpact,
  describeMoveNodeVariableImpact,
  extractTemplateVariableNames,
  summarizeValidationIssues,
  type WorkflowNodeValidationIssue,
} from "./workflowNodeValidation";

type EditorNodeData = {
  label: string;
  typeLabel: string;
  nodeType: WorkflowNodeType;
  summary: string;
  inputVariables: string[];
  outputVariables: string[];
  configStatus: "complete" | "incomplete";
  runState: "未开始" | "等待输入" | "执行中" | "等待审核" | "已完成" | "待配置";
  outputMode: "一次性输出" | "追问确认";
  toolCount: number;
  allowUserEdit: boolean;
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

type VariableReferenceItem = {
  name: string;
  label?: string;
  description: string;
  sourceLabel: string;
  typeLabel?: string;
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
  maxTokens?: number;
  allowUserEdit: boolean;
  allowQuestion: boolean;
};

type WorkflowCapabilityOption = {
  id: string;
  assetType: AssetType;
  name: string;
  code: string;
  version: string;
  status: string;
  config: Record<string, unknown>;
  source: "system" | "mine" | "shared";
  scope: string;
};

type WorkflowCapabilityState = {
  capabilities: WorkflowCapabilityOption[];
  loading: boolean;
  error: string;
};

type DocumentDeliveryStyleDraft = DocumentDeliveryStyleValues;

type WorkflowIcon = typeof Zap;

type WorkflowEditorPageProps = {
  workflow: WorkflowDraft;
  onBack: () => void;
  onDraftSaved: (draft: WorkflowDraft) => void;
};

type WorkflowImpactConfirmState = {
  title: string;
  message: string;
  confirmLabel: string;
  confirmDanger?: boolean;
  onConfirm: () => void;
};

const SYSTEM_TRIGGER_ID = "trigger_manual";
const workflowSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const workflowSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;
const DEFAULT_WORD_DOCUMENT_STYLE: DocumentDeliveryStyleDraft = {
  chineseFont: "宋体",
  latinFont: "Times New Roman",
  bodyFontSize: 12,
  heading1FontSize: 16,
  heading2FontSize: 14,
  heading3FontSize: 13,
  heading1ChineseFont: "",
  heading1LatinFont: "",
  heading2ChineseFont: "",
  heading2LatinFont: "",
  heading3ChineseFont: "",
  heading3LatinFont: "",
  tableChineseFont: "",
  tableLatinFont: "",
  tableFontSize: 0,
  tableCellAlignment: "left",
  lineSpacing: 1.5,
  firstLineIndentChars: 2,
  paragraphSpacingBefore: 0,
  paragraphSpacingAfter: 6,
  marginTopCm: 2.54,
  marginBottomCm: 2.54,
  marginLeftCm: 3.18,
  marginRightCm: 3.18,
  titleCentered: false,
  headingFirstLineIndent: false,
};
const DEFAULT_WORD_PREVIEW_MARKDOWN = `# 交付文档预览

这是一段正文预览，用来检查字体、字号、首行缩进和行距。

## 结论摘要

- 支持将 AI Markdown 转换为 Word 文档。
- 支持标题、正文、列表、表格、引用和代码块。

| 字段 | 示例 |
| --- | --- |
| 交付类型 | Word 文档 |
| 文件格式 | docx |

> 预览内容只用于设计阶段校验样式，不参与正式运行。`;

const WORD_FILE_NAME_VARIABLES: VariableReferenceItem[] = [
  {
    name: "runNumber",
    description: "当前工作流运行编号，适合拼入文件名；格式示例：RUN-20260615-001。",
    sourceLabel: "系统运行变量",
    typeLabel: "string",
  },
  {
    name: "date",
    description: "运行当天日期，格式：YYYY-MM-DD，例如 2026-06-15。",
    sourceLabel: "系统日期变量",
    typeLabel: "date",
  },
  {
    name: "dateCompact",
    description: "运行当天紧凑日期，格式：YYYYMMDD，例如 20260615。",
    sourceLabel: "系统日期变量",
    typeLabel: "date",
  },
];

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
  const [saveSucceeded, setSaveSucceeded] = useState(false);
  const saveSucceededTimerRef = useRef<number | null>(null);
  const { message: messageApi } = App.useApp();
  const [designerCatalog, setDesignerCatalog] = useState<WorkflowDesignerCatalog | null>(null);
  const [capabilityOptions, setCapabilityOptions] = useState<WorkflowCapabilityOption[]>([]);
  const [capabilitiesLoading, setCapabilitiesLoading] = useState(false);
  const [capabilityError, setCapabilityError] = useState("");
  const [declaredVariables, setDeclaredVariables] = useState<WorkflowVariable[]>([]);
  const [isAddBrickModalOpen, setIsAddBrickModalOpen] = useState(false);
  const [impactConfirm, setImpactConfirm] = useState<WorkflowImpactConfirmState | null>(null);
  // 防止双击保存按钮导致并发 API 调用；useState 在同一渲染帧内可能未及时更新，需要额外的 ref 锁。
  const saveLockRef = useRef(false);
  const nodesRef = useRef(nodes);
  nodesRef.current = nodes;

  useEffect(() => () => {
    if (saveSucceededTimerRef.current !== null) {
      window.clearTimeout(saveSucceededTimerRef.current);
    }
  }, []);

  useEffect(() => {
    if (!token || !user?.tenantId) {
      setLoading(false);
      messageApi.error("当前账号缺少租户上下文，无法加载工作流草稿");
      onBack();
      return;
    }

    let cancelled = false;
    setLoading(true);

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
        messageApi.error(error instanceof AgentumApiError ? error.message : "无法加载工作流草稿");
        setDesignerCatalog(null);
        setNodes([]);
        setEdges([]);
        onBack();
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [messageApi, onBack, token, user?.tenantId, workflow.id]);

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
  const nodeValidationMap = useMemo(
    () => buildWorkflowNodeValidationMap(visibleNodes),
    [visibleNodes],
  );
  const incompleteNodes = useMemo(
    () => visibleNodes.filter((node) => (nodeValidationMap.get(node.id)?.length ?? 0) > 0),
    [visibleNodes, nodeValidationMap],
  );
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
      const sentNodeDrafts = nextNodes.map(toWorkflowNodeDraft);
      const nextVariables = buildWorkflowVariables(nextNodes, designerCatalog.variableMetadata);
      const detail = await workflowApi.saveGraph(
        user.tenantId,
        workflow.id,
        token,
        sentNodeDrafts,
        nextEdges.map(toWorkflowEdgeDraft),
        nextVariables.map(toWorkflowVariableDraft),
      );
      const mergedDetail = {
        ...detail,
        nodes: mergePersistedNodeConfigs(sentNodeDrafts, detail.nodes),
      };
      applyPersistedDetail(mergedDetail, designerCatalog, setNodes, setEdges, setSelectedNodeId);
      setDeclaredVariables(toWorkflowVariables(mergedDetail.variables, mergedDetail.nodes.map(toEditorNode)));
      messageApi.success("流程设计已保存");
      setSaveSucceeded(true);
      if (saveSucceededTimerRef.current !== null) {
        window.clearTimeout(saveSucceededTimerRef.current);
      }
      saveSucceededTimerRef.current = window.setTimeout(() => {
        setSaveSucceeded(false);
        saveSucceededTimerRef.current = null;
      }, 2500);
      onDraftSaved(detail.draft);
    } catch (error) {
      console.warn("[workflow] 工作流草稿保存失败", getWorkflowEditorErrorContext(error, user.tenantId, workflow.id));
      messageApi.error(formatWorkflowSaveError(error));
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

    const impactMessage = describeMoveNodeVariableImpact(visibleNodes, nodeId, direction);
    const nextVisibleNodes = [...visibleNodes];
    const [movingNode] = nextVisibleNodes.splice(currentIndex, 1);
    nextVisibleNodes.splice(nextIndex, 0, movingNode);

    function applyMove() {
      commitVisibleNodes(nextVisibleNodes, nodeId);
    }

    if (!impactMessage) {
      applyMove();
      return;
    }

    setImpactConfirm({
      title: "确认移动积木？",
      message: impactMessage,
      confirmLabel: "仍要移动",
      onConfirm: () => {
        applyMove();
        setImpactConfirm(null);
      },
    });
  }

  function handleDeleteNode(nodeId: string) {
    const impactMessage = describeDeleteNodeVariableImpact(visibleNodes, nodeId);
    const nextVisibleNodes = visibleNodes.filter((node) => node.id !== nodeId);
    const footer = "删除后需要点击顶部「保存流程」才会写入草稿。";

    function applyDelete() {
      commitVisibleNodes(nextVisibleNodes, nextVisibleNodes[0]?.id ?? "");
    }

    setImpactConfirm({
      title: "确认删除积木？",
      message: impactMessage ? `${impactMessage}\n\n${footer}` : `确认删除这个积木？\n\n${footer}`,
      confirmLabel: impactMessage ? "仍要删除" : "确认删除",
      confirmDanger: Boolean(impactMessage),
      onConfirm: () => {
        applyDelete();
        setImpactConfirm(null);
      },
    });
  }

  function handleSearchLocate() {
    const nextNode = matchedNodes[0];

    if (nextNode) {
      setSelectedNodeId(nextNode.id);
    }
  }

  function updateSelectedNode(patch: Partial<EditorNodeData>) {
    if (!selectedNodeId) {
      return;
    }
    setNodes((currentNodes) => {
      const nextNodes = currentNodes.map((node) => {
        if (node.id !== selectedNodeId) {
          return node;
        }
        return {
          ...node,
          data: {
            ...node.data,
            ...patch,
          },
        };
      });
      nodesRef.current = nextNodes;
      setDeclaredVariables(buildWorkflowVariables(nextNodes, designerCatalog?.variableMetadata));
      return nextNodes;
    });
  }

  function updateSelectedConfig(nextConfig: Record<string, unknown>) {
    if (!selectedNodeId) {
      return;
    }
    setNodes((currentNodes) => {
      const nextNodes = currentNodes.map((node) => {
        if (node.id !== selectedNodeId) {
          return node;
        }
        return {
          ...node,
          data: {
            ...node.data,
            rawConfig: {
              ...(node.data.rawConfig ?? {}),
              ...nextConfig,
            },
          },
        };
      });
      nodesRef.current = nextNodes;
      setDeclaredVariables(buildWorkflowVariables(nextNodes, designerCatalog?.variableMetadata));
      return nextNodes;
    });
  }

  /** 弹窗等场景需要同时写 rawConfig 与节点展示字段，单次 setState 避免两次合并丢字段。 */
  function updateSelectedConfigAndNode(nextConfig: Record<string, unknown>, patch: Partial<EditorNodeData>) {
    if (!selectedNodeId) {
      return;
    }
    setNodes((currentNodes) => {
      const nextNodes = currentNodes.map((node) => {
        if (node.id !== selectedNodeId) {
          return node;
        }
        return {
          ...node,
          data: {
            ...node.data,
            ...patch,
            rawConfig: {
              ...(node.data.rawConfig ?? {}),
              ...nextConfig,
            },
          },
        };
      });
      nodesRef.current = nextNodes;
      setDeclaredVariables(buildWorkflowVariables(nextNodes, designerCatalog?.variableMetadata));
      return nextNodes;
    });
  }

  async function handleSaveWorkflow() {
    if (!designerCatalog) {
      messageApi.error("流程设计模板尚未加载完成，暂时不能保存流程");
      return;
    }
    const currentNodes = nodesRef.current;
    const currentVisibleNodes = orderNodesByEdges(currentNodes, edges).filter((node) => node.id !== SYSTEM_TRIGGER_ID);
    const normalizedVisibleNodes = applyValidatedConfigStatus(normalizeVisibleNodeOrder(currentVisibleNodes));
    const systemTrigger = currentNodes.find((node) => node.id === SYSTEM_TRIGGER_ID)
      ?? createNodeFromTemplate(designerCatalog.systemTrigger, 0, []);
    const nextNodes = [systemTrigger, ...normalizedVisibleNodes];
    const nextEdges = rebuildSequentialEdges(normalizedVisibleNodes);
    await persistGraph(nextNodes, nextEdges);
  }

  if (loading) {
    return <EditorStateShell workflowName={workflow.name} onBack={onBack} icon={<Clock3 className="h-5 w-5" aria-hidden="true" />} message="正在加载工作流草稿" />;
  }

  return (
    <div className="flex h-screen flex-col bg-[var(--color-bg-layout)]">
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
        <button
          type="button"
          onClick={() => void handleSaveWorkflow()}
          disabled={saving}
          className="agent-button agent-button-primary h-8 px-3 text-xs"
        >
          {saveSucceeded && !saving ? (
            <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
          ) : (
            <Save className="h-3.5 w-3.5" aria-hidden="true" />
          )}
          {saving ? "保存中" : saveSucceeded ? "已保存" : "保存流程"}
        </button>
        <WorkbenchGlobalActions />
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-[280px_minmax(0,1fr)]">
        <main className="workflow-step-sidebar min-h-0 overflow-y-auto border-r border-[var(--color-border-light)] p-3">
          <WorkflowStepBuilder
            nodes={visibleNodes}
            nodeValidationMap={nodeValidationMap}
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
              onSyncAgentConfig={updateSelectedConfigAndNode}
            />
          ) : (
            <WorkflowOverviewPanel
              nodes={visibleNodes}
              variables={businessVariables}
              incompleteNodes={incompleteNodes}
              nodeValidationMap={nodeValidationMap}
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

      <SysImpactConfirmModal
        open={impactConfirm !== null}
        title={impactConfirm?.title ?? ""}
        message={impactConfirm?.message ?? ""}
        confirmLabel={impactConfirm?.confirmLabel ?? "确认"}
        confirmDanger={impactConfirm?.confirmDanger}
        onCancel={() => setImpactConfirm(null)}
        onConfirm={() => impactConfirm?.onConfirm()}
      />
    </div>
  );
}

function WorkflowStepBuilder({
  nodes,
  nodeValidationMap,
  selectedNodeId,
  onSelectNode,
  onOpenAddBrick,
  onMoveNode,
  onDeleteNode,
}: {
  nodes: WorkflowEditorNode[];
  nodeValidationMap: Map<string, WorkflowNodeValidationIssue[]>;
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
            validationIssues={nodeValidationMap.get(node.id) ?? []}
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
  validationIssues,
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
  validationIssues: WorkflowNodeValidationIssue[];
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
        <div className="mt-2 space-y-1">
          <div className="flex items-center justify-between gap-2">
            {validationIssues.length > 0 ? <TinyBadge tone="warning">待配置</TinyBadge> : <TinyBadge tone="success">已配置</TinyBadge>}
            <div className="flex items-center gap-0.5">
              <IconButton label="上移" icon={ArrowUp} disabled={!canMoveUp} onClick={onMoveUp} />
              <IconButton label="下移" icon={ArrowDown} disabled={!canMoveDown} onClick={onMoveDown} />
              <IconButton label="删除" icon={Trash2} onClick={onDelete} tone="danger" />
            </div>
          </div>
          {validationIssues.length > 0 ? (
            <p className="text-[11px] leading-4 text-amber-700">{summarizeValidationIssues(validationIssues)}</p>
          ) : null}
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5 border-t border-[var(--color-border-light)] px-2.5 py-2">
        <TinyBadge>输出 {node.data.outputVariables.length}</TinyBadge>
        <AgentInteractionFeatureBadges
          allowUserEdit={node.data.allowUserEdit}
          allowQuestion={node.data.allowQuestion}
        />
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
  onSyncAgentConfig,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  workflowVariables: WorkflowVariable[];
  capabilities: WorkflowCapabilityOption[];
  capabilitiesLoading: boolean;
  capabilityError: string;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onSyncAgentConfig: (nextConfig: Record<string, unknown>, patch: Partial<EditorNodeData>) => void;
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
            <SingleAgentBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} onSyncConfig={onSyncAgentConfig} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "cluster" ? (
            <AgentClusterBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "delivery" ? (
            <DeliveryBrickConfig node={node} workflowVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} />
          ) : null}
        </div>
      </div>
    </aside>
  );
}

function WorkflowOverviewPanel({
  nodes,
  variables,
  incompleteNodes,
  nodeValidationMap,
  onSelectNode,
  onOpenAddBrick,
}: {
  nodes: WorkflowEditorNode[];
  variables: WorkflowVariable[];
  incompleteNodes: WorkflowEditorNode[];
  nodeValidationMap: Map<string, WorkflowNodeValidationIssue[]>;
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
              <button
                key={node.id}
                type="button"
                onClick={() => onSelectNode(node.id)}
                className="flex w-full items-start justify-between gap-3 rounded bg-[var(--color-bg-card)] px-2 py-2 text-left text-sm ring-1 ring-[var(--color-border-light)]"
              >
                <span className="min-w-0">
                  <span className="block truncate font-medium text-[var(--color-text-primary)]">{node.data.label}</span>
                  <span className="mt-1 block text-xs leading-5 text-[var(--color-text-secondary)]">
                    {summarizeValidationIssues(nodeValidationMap.get(node.id) ?? [])}
                  </span>
                </span>
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden="true" />
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
  onSyncConfig,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  onSyncConfig: (nextConfig: Record<string, unknown>, patch: Partial<EditorNodeData>) => void;
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

  return (
    <PanelGroup title="智能体配置" icon={Bot} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <div className="workflow-config-list-box">
        <div className="workflow-config-list-header">
          <span>智能体</span>
        </div>
        <div className="workflow-cluster-agent-list">
          <article className="workflow-cluster-agent-row">
            <div className="workflow-cluster-agent-index">
              <Bot className="h-4 w-4" aria-hidden="true" />
            </div>
            <button type="button" className="min-w-0 flex-1 text-left" onClick={() => setModalOpen(true)}>
              <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{agentName}</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                <TinyBadge>单智能体</TinyBadge>
                <TinyBadge tone="info">Skill {selectedSkills.length}</TinyBadge>
                <TinyBadge tone="info">MCP {selectedMcps.length}</TinyBadge>
                <AgentInteractionFeatureBadges
                  allowUserEdit={node.data.allowUserEdit}
                  allowQuestion={node.data.allowQuestion}
                />
              </div>
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
                    systemPrompt: DEFAULT_SYSTEM_PROMPT,
                    userPrompt: DEFAULT_USER_PROMPT,
                    mcpIds: [],
                    skillIds: [],
                  });
                  onUpdateNode({ toolCount: 0, allowUserEdit: false, allowQuestion: false });
                }}
              />
            </div>
          </article>
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
          onConfigChange={onSyncConfig}
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
          <button type="button" onClick={() => setEditingAgent(createClusterAgent(agents.length, node.id))} className="agent-button agent-button-primary h-8 px-3 text-xs">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增智能体
          </button>
        </div>
        <SelectLikeField
          label="执行方式"
          icon={Layers3}
          value={readString(config.executionMode, "parallel")}
          options={[
            { value: "parallel", label: "并行执行" },
            { value: "sequential", label: "顺序执行" },
          ]}
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
                  <AgentInteractionFeatureBadges
                    allowUserEdit={agent.allowUserEdit}
                    allowQuestion={agent.allowQuestion}
                  />
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
            const usedOutputs = new Set(agents.filter((item) => item.id !== agent.id).map((item) => item.output).filter(Boolean));
            const normalizedAgent = {
              ...agent,
              output: uniqueVariableName(agent.output || createClusterAgentOutputVariable(node.id, agents.length), usedOutputs),
            };
            commitAgents(exists ? agents.map((item) => item.id === agent.id ? normalizedAgent : item) : [...agents, normalizedAgent]);
            setEditingAgent(null);
          }}
        />
      ) : null}
    </PanelGroup>
  );
}

const STALE_DIRECT_DELIVERY_FIELDS = {
  deliveryContent: "",
  deliveryTarget: "",
  body: "",
} as const;

const STALE_WORD_DELIVERY_FIELDS = {
  markdownContent: "",
  fileNameTemplate: "",
  previewMarkdown: "",
} as const;

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
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const { message: messageApi } = App.useApp();
  const config = node.data.rawConfig ?? {};
  const deliveryMode = readString(config.deliveryMode, "direct");
  const isDirectDelivery = deliveryMode === "direct";
  const deliveryAssets = filterCapabilities(capabilityState.capabilities, "delivery");
  const wordDeliveryAssets = deliveryAssets.filter((option) => isWordDocumentDeliveryCapability(option));
  const defaultWordDeliveryCapability = wordDeliveryAssets[0];
  const rawSelectedCapabilityId = readString(config.deliveryCapabilityId, "");
  const selectedCapabilityId = rawSelectedCapabilityId === "none" ? "" : rawSelectedCapabilityId;
  const effectiveSelectedCapabilityId = selectedCapabilityId || defaultWordDeliveryCapability?.id || "";
  const selectedDeliveryCapability = deliveryAssets.find((option) => option.id === selectedCapabilityId)
    ?? deliveryAssets.find((option) => option.id === effectiveSelectedCapabilityId);
  const isWordDelivery = !isDirectDelivery && (
    isWordDocumentDeliveryCapability(selectedDeliveryCapability)
    || readString(config.deliveryType, "") === "word_document"
    || readString(config.documentKind, "") === "word"
  );
  const documentStyle = readDocumentDeliveryStyle(config.documentStyle, selectedDeliveryCapability?.config);
  const [exporting, setExporting] = useState(false);
  const defaultMarkdownVariable = workflowVariables.find((variable) => variable.deliverable)?.name ?? workflowVariables[workflowVariables.length - 1]?.name ?? "";
  const defaultDirectTemplate = defaultMarkdownVariable
    ? `# 交付结果\n\n{{${defaultMarkdownVariable}}}`
    : "# 交付结果\n\n请在这里编写最终交付内容。";
  const defaultMarkdownTemplate = defaultMarkdownVariable
    ? `# 交付文档\n\n{{${defaultMarkdownVariable}}}`
    : "# 交付文档\n\n请在这里编写最终 Markdown 交付正文。";
  const rawFileNameTemplate = readString(config.fileNameTemplate, "交付文档-{{runNumber}}.docx");
  const fileNameTemplate = normalizeWordFileNameTemplate(rawFileNameTemplate);
  const fileNameVariableItems = WORD_FILE_NAME_VARIABLES;

  useEffect(() => {
    if (rawFileNameTemplate !== fileNameTemplate) {
      onUpdateConfig({ fileNameTemplate });
    }
  }, [rawFileNameTemplate, fileNameTemplate]);

  useEffect(() => {
    if (!isWordDelivery) {
      return;
    }
    const hasStaleDirectFields = readString(config.deliveryContent, "")
      || readString(config.deliveryTarget, "")
      || readString(config.body, "");
    if (!hasStaleDirectFields) {
      return;
    }
    onUpdateConfig(STALE_DIRECT_DELIVERY_FIELDS);
  }, [isWordDelivery, config.deliveryContent, config.deliveryTarget, config.body]);

  useEffect(() => {
    if (isDirectDelivery) {
      return;
    }
    if (!rawSelectedCapabilityId && defaultWordDeliveryCapability) {
      onUpdateConfig({
        deliveryMode: "capability",
        deliveryCapabilityId: defaultWordDeliveryCapability.id,
        deliveryType: "word_document",
        documentKind: "word",
        ...STALE_DIRECT_DELIVERY_FIELDS,
        fileNameTemplate: readString(config.fileNameTemplate, "交付文档-{{runNumber}}.docx"),
        markdownContent: readString(config.markdownContent, defaultMarkdownTemplate),
        documentStyle: readDocumentDeliveryStyle(config.documentStyle, defaultWordDeliveryCapability.config),
        previewMarkdown: readString(config.previewMarkdown, DEFAULT_WORD_PREVIEW_MARKDOWN),
      });
    }
  }, [isDirectDelivery, rawSelectedCapabilityId, defaultWordDeliveryCapability?.id]);

  function handleDeliveryModeChange(value: string) {
    if (value === "direct") {
      onUpdateConfig({
        deliveryMode: "direct",
        deliveryType: "direct",
        deliveryCapabilityId: "none",
        documentKind: "",
        ...STALE_WORD_DELIVERY_FIELDS,
        deliveryContent: readString(config.deliveryContent, defaultDirectTemplate),
      });
      return;
    }
    const capability = defaultWordDeliveryCapability ?? deliveryAssets[0];
    if (capability && isWordDocumentDeliveryCapability(capability)) {
      onUpdateConfig({
        deliveryMode: "capability",
        deliveryCapabilityId: capability.id,
        deliveryType: "word_document",
        documentKind: "word",
        ...STALE_DIRECT_DELIVERY_FIELDS,
        fileNameTemplate: readString(config.fileNameTemplate, "交付文档-{{runNumber}}.docx"),
        markdownContent: readString(config.markdownContent, defaultMarkdownTemplate),
        documentStyle: readDocumentDeliveryStyle(config.documentStyle, capability.config),
        previewMarkdown: readString(config.previewMarkdown, DEFAULT_WORD_PREVIEW_MARKDOWN),
      });
      return;
    }
    onUpdateConfig({
      deliveryMode: "capability",
      deliveryCapabilityId: capability?.id ?? "",
      deliveryType: capability ? readString(capability.config?.deliveryChannel, readString(capability.config?.sourceType, "")) : "",
      documentKind: "",
    });
  }

  function handleDeliveryCapabilityChange(value: string) {
    const capability = deliveryAssets.find((option) => option.id === value);
    if (isWordDocumentDeliveryCapability(capability)) {
      onUpdateConfig({
        deliveryMode: "capability",
        deliveryCapabilityId: value,
        deliveryType: "word_document",
        documentKind: "word",
        ...STALE_DIRECT_DELIVERY_FIELDS,
        fileNameTemplate: readString(config.fileNameTemplate, "交付文档-{{runNumber}}.docx"),
        markdownContent: readString(config.markdownContent, defaultMarkdownTemplate),
        documentStyle: readDocumentDeliveryStyle(config.documentStyle, capability?.config),
        previewMarkdown: readString(config.previewMarkdown, DEFAULT_WORD_PREVIEW_MARKDOWN),
      });
      return;
    }

    onUpdateConfig({
      deliveryMode: "capability",
      deliveryCapabilityId: value,
      deliveryType: readString(capability?.config?.deliveryChannel, readString(capability?.config?.sourceType, "")),
      documentKind: "",
    });
  }

  function updateDocumentStyle(key: string, value: string | number | boolean) {
    onUpdateConfig({
      documentStyle: {
        ...documentStyle,
        [key]: value,
      },
    });
  }

  function updateDocumentStyles(updates: Partial<DocumentDeliveryStyleDraft>) {
    onUpdateConfig({
      documentStyle: {
        ...documentStyle,
        ...updates,
      },
    });
  }

  async function handleWordDocumentFile() {
    if (!token || !user?.tenantId) {
      messageApi.error("当前账号缺少租户上下文，无法生成 Word 文档");
      return;
    }
    if (!effectiveSelectedCapabilityId) {
      messageApi.error("请先选择 Word 文档交付能力");
      return;
    }
    setExporting(true);
    try {
      const fileName = ensureDocxFileName(renderDesignTemplate(fileNameTemplate, workflowVariables));
      const markdown = readString(config.previewMarkdown, DEFAULT_WORD_PREVIEW_MARKDOWN);
      const request: WordDocumentPreviewRequest = {
        capabilityId: effectiveSelectedCapabilityId,
        markdown,
        fileName,
        style: documentStyle,
      };
      const file = await workflowApi.previewWordDocument(user.tenantId, token, request);
      downloadFile(file);
      messageApi.success("Word 预览样例已导出");
    } catch (error) {
      console.warn("[workflow] Word 文档预览生成失败", getWorkflowEditorErrorContext(error, user.tenantId, node.id));
      messageApi.error(error instanceof AgentumApiError ? error.message : "Word 文档生成失败");
    } finally {
      setExporting(false);
    }
  }

  return (
    <PanelGroup title="交付配置" icon={PackageCheck} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <SelectLikeField
        label="交付方式"
        icon={PackageCheck}
        value={deliveryMode}
        options={[
          { value: "direct", label: "直接交付（节点内配置，无需分配能力）" },
          { value: "capability", label: "能力交付（Word / 邮件等系统能力）" },
        ]}
        onChange={handleDeliveryModeChange}
      />

      {isDirectDelivery ? (
        <div className="mt-4 space-y-4">
          <div className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
            <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">直接交付</h4>
            <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
              运行时将模板与上游变量拼接为最终交付内容，用户在交付节点核对后可复制，无需系统管理员分配交付能力。
            </p>
          </div>
          <PromptEditor
            label="交付内容模板"
            value={readString(config.deliveryContent, defaultDirectTemplate)}
            availableVariables={workflowVariables}
            onChange={(value) => onUpdateConfig({ deliveryContent: value })}
            placeholder="支持 Markdown，可用 {{输出内容标识}} 引用之前步骤内容"
          />
        </div>
      ) : (
        <>
          <CapabilitySelectField
            label="交付能力"
            icon={PackageCheck}
            value={effectiveSelectedCapabilityId}
            options={deliveryAssets}
            placeholder="请选择交付能力"
            onChange={handleDeliveryCapabilityChange}
          />
          {!effectiveSelectedCapabilityId ? (
            <p className="workflow-capability-state workflow-capability-state--warning">
              当前主体还没有可用的交付能力。请先由系统管理员开放对应能力，再由租户管理员分配给当前用户、部门或角色。
            </p>
          ) : null}

          {isWordDelivery ? (
        <div className="mt-4 space-y-4">
          <div className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <div>
                <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">Word 文档交付</h4>
                <p className="mt-1 text-xs text-[var(--color-text-secondary)]">交付正文模板会作为最终 Markdown，运行时将模板和变量拼接后转换为 docx 文件。</p>
              </div>
              <button
                type="button"
                onClick={() => void handleWordDocumentFile()}
                disabled={exporting || !effectiveSelectedCapabilityId}
                className="agent-button agent-button-primary h-8 px-3 text-xs"
              >
                <Download className="h-3.5 w-3.5" aria-hidden="true" />
                {exporting ? "导出中" : "导出预览样例"}
              </button>
            </div>
            <FileNameVariableBar
              items={fileNameVariableItems}
              onPick={(variable) => onUpdateConfig({ fileNameTemplate: appendFileNameToken(fileNameTemplate, `{{${variable}}}`) })}
            />
            <TextInputField
              label="文件名模板"
              icon={FileText}
              value={fileNameTemplate}
              placeholder="交付文档-{{runNumber}}-{{dateCompact}}.docx"
              onChange={(value) => onUpdateConfig({ fileNameTemplate: value })}
            />
          </div>

          <DocumentDeliveryStyleSections
            style={documentStyle}
            onFieldChange={(key, value) => updateDocumentStyle(key, value)}
            onFieldsChange={updateDocumentStyles}
          />

          <PromptEditor
            label="交付正文模板"
            value={readString(config.markdownContent, defaultMarkdownTemplate)}
            availableVariables={workflowVariables}
            onChange={(value) => onUpdateConfig({ markdownContent: value })}
            placeholder="最终转换为 Word 的 Markdown，可用 {{输出内容标识}} 引用之前步骤内容"
          />
          <PromptEditor
            label="预览 Markdown"
            value={readString(config.previewMarkdown, DEFAULT_WORD_PREVIEW_MARKDOWN)}
            availableVariables={[]}
            showVariableBar={false}
            onChange={(value) => onUpdateConfig({ previewMarkdown: value })}
            placeholder="仅用于设计阶段导出样例，不参与正式运行，也不会替换变量"
          />
        </div>
      ) : (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/20 dark:text-amber-300">
          当前交付节点按 Word 文档交付设计，请选择系统内置 Word 文档交付能力。
        </div>
      )}
        </>
      )}
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
  placeholder,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  value: string;
  emptyValue?: string;
  emptyLabel?: string;
  options: WorkflowCapabilityOption[];
  placeholder?: string;
  onChange: (value: string) => void;
}) {
  const normalizedOptions = [
    ...(emptyValue && emptyLabel ? [{ value: emptyValue, label: emptyLabel }] : []),
    ...options.map((option) => ({
      value: option.id,
      label: `${option.name} · ${option.version} · ${formatAssetSource(option)}`,
    })),
  ];

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={<Icon className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={value || undefined}
        placeholder={placeholder}
        options={normalizedOptions}
        onChange={onChange}
      />
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

function TextInputField({
  label,
  icon: Icon,
  value,
  placeholder,
  maxLength = 160,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  value: string;
  placeholder?: string;
  maxLength?: number;
  onChange: (value: string) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <div className="sys-field-input-wrap">
        {Icon ? <Icon size={16} className="sys-field-prefix" aria-hidden="true" /> : null}
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className="sys-field-input"
          placeholder={placeholder}
          maxLength={maxLength}
        />
      </div>
    </label>
  );
}

function NumberInputField({
  label,
  value,
  min,
  max,
  step,
  onChange,
}: {
  label: string;
  value: string | number;
  min: number;
  max: number;
  step: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <div className="sys-field-input-wrap">
        <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
        <input
          value={String(value)}
          onChange={(event) => onChange(parseNumberInput(event.target.value, Number(value)))}
          className="sys-field-input"
          type="number"
          min={min}
          max={max}
          step={step}
        />
      </div>
    </label>
  );
}

function PromptEditor({
  label,
  value,
  availableVariables,
  onChange,
  placeholder,
  showVariableBar = true,
}: {
  label: string;
  value: string;
  availableVariables: WorkflowVariable[];
  onChange: (value: string) => void;
  placeholder?: string;
  showVariableBar?: boolean;
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
      {showVariableBar ? (
        <VariableReferenceBar
          variables={availableVariables}
          onPick={(variable) => onChange(`${value}${value.endsWith(" ") || value.length === 0 ? "" : " "}{{${variable}}}`)}
        />
      ) : null}
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
  return <VariableReferenceItemBar items={variables.map(variableToReferenceItem)} onPick={onPick} />;
}

function FileNameVariableBar({
  items,
  onPick,
}: {
  items: VariableReferenceItem[];
  onPick: (variable: string) => void;
}) {
  return (
    <div className="workflow-file-template-variable-section">
      <span className="workflow-variable-reference-title">可插入变量</span>
      <VariableReferenceItemBar items={items} onPick={onPick} />
    </div>
  );
}

function VariableReferenceItemBar({
  items,
  onPick,
}: {
  items: VariableReferenceItem[];
  onPick: (variable: string) => void;
}) {
  if (items.length === 0) {
    return null;
  }

  return (
    <div className="workflow-variable-reference-bar">
      {items.map((item) => (
        <Tooltip
          key={`${item.sourceLabel}-${item.name}`}
          rootClassName="agentum-tooltip"
          title={<VariableReferenceTooltip item={item} />}
        >
          <button type="button" onClick={() => onPick(item.name)} aria-label={`插入变量 {{${item.name}}}`}>
            {item.label ?? `{{${item.name}}}`}
          </button>
        </Tooltip>
      ))}
    </div>
  );
}

function VariableReferenceTooltip({ item }: { item: VariableReferenceItem }) {
  return (
    <div className="workflow-variable-tooltip">
      <strong>{`{{${item.name}}}`}</strong>
      <span>写法：{`{{${item.name}}}`}</span>
      <span>来源：{item.sourceLabel}</span>
      {item.typeLabel ? <span>类型：{item.typeLabel}</span> : null}
      <span>{item.description}</span>
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

type SingleAgentConfigDraft = {
  agentAssetId: string;
  systemPromptTemplateId: string;
  userPromptTemplateId: string;
  systemPrompt: string;
  userPrompt: string;
  mcpIds: string[];
  skillIds: string[];
  maxTokens?: number;
  allowUserEdit: boolean;
  allowQuestion: boolean;
};

function buildSingleAgentConfigDraft(node: WorkflowEditorNode): SingleAgentConfigDraft {
  const config = node.data.rawConfig ?? {};
  return {
    agentAssetId: readString(config.agentAssetId, "custom"),
    systemPromptTemplateId: readString(config.systemPromptTemplateId, readString(config.promptTemplateId, "none")),
    userPromptTemplateId: readString(config.userPromptTemplateId, "none"),
    systemPrompt: readString(config.systemPrompt, ""),
    userPrompt: readString(config.userPrompt, ""),
    mcpIds: readStringArray(config.mcpIds ?? config.mcpServices, []),
    skillIds: readStringArray(config.skillIds ?? config.skills, []),
    maxTokens: readOptionalInt(config.maxTokens),
    allowUserEdit: node.data.allowUserEdit,
    allowQuestion: node.data.allowQuestion,
  };
}

function buildSingleAgentConfigPayload(draft: SingleAgentConfigDraft): Record<string, unknown> {
  return {
    agentAssetId: draft.agentAssetId,
    agentSource: draft.agentAssetId === "custom" ? "custom" : "asset",
    promptTemplateId: draft.systemPromptTemplateId,
    systemPromptTemplateId: draft.systemPromptTemplateId,
    userPromptTemplateId: draft.userPromptTemplateId,
    systemPrompt: draft.systemPrompt.trim(),
    userPrompt: draft.userPrompt.trim(),
    mcpIds: draft.mcpIds,
    skillIds: draft.skillIds,
    ...(draft.maxTokens ? { maxTokens: draft.maxTokens } : {}),
  };
}

function buildSingleAgentNodePatch(draft: SingleAgentConfigDraft): Partial<EditorNodeData> {
  return {
    toolCount: draft.mcpIds.length + draft.skillIds.length,
    allowUserEdit: draft.allowUserEdit,
    allowQuestion: draft.allowQuestion,
  };
}

function SingleAgentConfigModal({
  node,
  availableVariables,
  agentAssets,
  promptAssets,
  mcpAssets,
  skillAssets,
  onClose,
  onConfigChange,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  agentAssets: WorkflowCapabilityOption[];
  promptAssets: WorkflowCapabilityOption[];
  mcpAssets: WorkflowCapabilityOption[];
  skillAssets: WorkflowCapabilityOption[];
  onClose: () => void;
  onConfigChange: (config: Record<string, unknown>, patch: Partial<EditorNodeData>) => void;
}) {
  const { message } = App.useApp();
  const initialDraftRef = useRef(buildSingleAgentConfigDraft(node));
  const [draft, setDraftState] = useState<SingleAgentConfigDraft>(initialDraftRef.current);
  const onConfigChangeRef = useRef(onConfigChange);
  onConfigChangeRef.current = onConfigChange;

  function applyDraft(nextDraft: SingleAgentConfigDraft) {
    onConfigChangeRef.current(buildSingleAgentConfigPayload(nextDraft), buildSingleAgentNodePatch(nextDraft));
  }

  function setDraft(nextDraft: SingleAgentConfigDraft) {
    setDraftState(nextDraft);
    applyDraft(nextDraft);
  }

  function handleCancel() {
    setDraftState(initialDraftRef.current);
    applyDraft(initialDraftRef.current);
    onClose();
  }

  return (
    <div className="sys-modal-mask" onClick={handleCancel}>
      <section className="sys-modal workflow-config-modal workflow-agent-modal" aria-labelledby="single-agent-modal-title" onClick={(event) => event.stopPropagation()}>
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>单智能体</div>
            <span id="single-agent-modal-title" className="sys-modal-title">配置智能体</span>
          </div>
          <button className="sys-modal-close" onClick={handleCancel} aria-label="关闭智能体配置"><X size={18} /></button>
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
          <MaxTokensField
            value={draft.maxTokens}
            onChange={(value) => setDraft({ ...draft, maxTokens: value })}
          />
          <AgentInteractionOptions
            allowUserEdit={draft.allowUserEdit}
            allowQuestion={draft.allowQuestion}
            onChange={(patch) => setDraft({ ...draft, ...patch })}
          />
        </div>
        <div className="sys-modal-footer">
          <p className="mr-auto text-xs text-[var(--color-text-tertiary)]">修改会即时同步到当前节点，请再点顶部「保存流程」写入草稿。</p>
          <button type="button" className="sys-btn sys-btn--default" onClick={handleCancel}>取消</button>
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            onClick={() => {
              const validationError = validateCustomPromptConfiguration(
                draft,
                promptAssets.map((asset) => asset.id),
              );
              if (validationError) {
                message.error(validationError);
                return;
              }
              onClose();
            }}
          >
            完成
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
  const { message } = App.useApp();
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
          <MaxTokensField
            value={draft.maxTokens}
            onChange={(value) => setDraft({ ...draft, maxTokens: value })}
          />
          <AgentInteractionOptions
            allowUserEdit={draft.allowUserEdit}
            allowQuestion={draft.allowQuestion}
            onChange={(patch) => setDraft({ ...draft, ...patch })}
          />
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            onClick={() => {
              const validationError = validateCustomPromptConfiguration(
                draft,
                promptAssets.map((asset) => asset.id),
              );
              if (validationError) {
                message.error(validationError);
                return;
              }
              onSave({
                ...draft,
                output: normalizeVariableName(draft.output) || "agent_output",
                systemPrompt: draft.systemPrompt.trim(),
                userPrompt: draft.userPrompt.trim(),
              });
            }}
          >
            保存
          </button>
        </div>
      </section>
    </div>
  );
}

function MaxTokensField({
  value,
  onChange,
}: {
  value?: number;
  onChange: (value: number | undefined) => void;
}) {
  return (
    <div className="workflow-modal-section">
      <label className="sys-field">
        <span className="sys-field-label">最大输出 Token（可选）</span>
        <div className="sys-field-input-wrap">
          <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
          <input
            className="sys-field-input"
            type="number"
            min={256}
            max={131072}
            step={256}
            placeholder="留空则沿用模型供应商配置"
            value={value ?? ""}
            onChange={(event) => {
              const raw = event.target.value.trim();
              if (!raw) {
                onChange(undefined);
                return;
              }
              const parsed = Number.parseInt(raw, 10);
              onChange(Number.isFinite(parsed) ? parsed : undefined);
            }}
          />
        </div>
        <span className="sys-field-hint">节点级覆盖供应商默认值；长报告建议 8192 或以上。</span>
      </label>
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

function AgentInteractionFeatureBadges({
  allowUserEdit,
  allowQuestion,
}: {
  allowUserEdit?: boolean;
  allowQuestion?: boolean;
}) {
  return (
    <>
      {allowUserEdit ? <TinyBadge tone="info">修改</TinyBadge> : null}
      {allowQuestion ? <TinyBadge tone="info">追问</TinyBadge> : null}
    </>
  );
}

function AgentInteractionOptions({
  allowUserEdit,
  allowQuestion,
  onChange,
}: {
  allowUserEdit: boolean;
  allowQuestion: boolean;
  onChange: (patch: Partial<{ allowUserEdit: boolean; allowQuestion: boolean }>) => void;
}) {
  return (
    <div className="workflow-modal-section grid gap-3 lg:grid-cols-2">
      <label className="workflow-toggle-row">
        <span>允许修改</span>
        <input
          type="checkbox"
          checked={allowUserEdit}
          onChange={(event) => onChange({ allowUserEdit: event.target.checked })}
        />
      </label>
      <label className="workflow-toggle-row">
        <span>允许追问</span>
        <input
          type="checkbox"
          checked={allowQuestion}
          onChange={(event) => onChange({ allowQuestion: event.target.checked })}
        />
      </label>
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
    config: asset.config ?? {},
    source: asset.openSource === "user_shared" ? ("shared" as const) : ("system" as const),
    scope: asset.assignmentScope,
  }));
  const myOptions = myAssets
    .filter((asset) => (asset.assetType === "agent_template" || asset.assetType === "prompt_template") && asset.status === "published")
    .map((asset) => ({
      id: asset.id,
      assetType: asset.assetType,
      name: asset.name,
      code: asset.code,
      version: asset.version,
      status: asset.status,
      config: {},
      source: "mine" as const,
      scope: asset.accessLevel === "owner" ? "本人维护" : asset.accessLevel === "edit" ? "可编辑" : "可读取",
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
  if (option.source === "shared") return "同事共享";
  return option.source === "system" ? "租户管理分配" : "我的能力";
}

function isWordDocumentDeliveryCapability(option?: WorkflowCapabilityOption | null) {
  if (!option || option.assetType !== "delivery") {
    return false;
  }
  const channel = readString(option.config?.deliveryChannel, "").toLowerCase();
  const kind = readString(option.config?.documentKind, "").toLowerCase();
  const code = option.code.toLowerCase();
  const name = option.name.toLowerCase();
  return channel === "document"
    || channel === "word_document"
    || kind === "word"
    || code.includes("word")
    || name.includes("word");
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
    // 新建输入节点按节点序号同步字段变量，避免多个输入节点都沿用模板默认的 input_1。
    const inputFields = readInputFields(rawConfig.inputFields, outputVariables).map((field, fieldIndex) => ({
      ...field,
      variable: outputVariables[fieldIndex] ?? field.variable,
    }));
    rawConfig.inputFields = inputFields;
    outputVariables = inputFields.map((field) => field.variable);
  }

  if (brickType === "agent") {
    if (!readString(rawConfig.userPrompt, "")) {
      rawConfig.userPrompt = DEFAULT_USER_PROMPT;
    }
    if (!readString(rawConfig.systemPrompt, "")) {
      rawConfig.systemPrompt = DEFAULT_SYSTEM_PROMPT;
    }
  }

  if (brickType === "cluster") {
    const clusterAgents = readClusterAgents(rawConfig.clusterAgents).map((agent, agentIndex) => ({
      ...agent,
      userPrompt: readString(agent.userPrompt, "") || DEFAULT_CLUSTER_USER_PROMPT,
      systemPrompt: readString(agent.systemPrompt, "") || DEFAULT_SYSTEM_PROMPT,
      output: createClusterAgentOutputVariable(id, agentIndex),
    }));
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
      configStatus: brickType === "trigger" ? "complete" : "incomplete",
      runState: template.runState,
      outputMode: template.outputMode,
      toolCount: template.toolCount,
      allowQuestion: template.allowQuestion,
      allowUserEdit: false,
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

function createClusterAgent(index: number, nodeId = "cluster"): ClusterAgentConfig {
  return {
    id: `cluster_agent_${Date.now().toString(36)}_${index}`,
    name: `子智能体 ${index + 1}`,
    agentAssetId: "custom",
    promptTemplateId: "none",
    systemPromptTemplateId: "none",
    userPromptTemplateId: "none",
    skillIds: [],
    mcpIds: [],
    systemPrompt: DEFAULT_SYSTEM_PROMPT,
    userPrompt: DEFAULT_CLUSTER_USER_PROMPT,
    output: createClusterAgentOutputVariable(nodeId, index),
    allowUserEdit: false,
    allowQuestion: false,
  };
}

function createClusterAgentOutputVariable(nodeId: string, agentIndex: number) {
  const nodePrefix = normalizeVariableName(nodeId) || "cluster";
  return `${nodePrefix}_agent_${agentIndex + 1}_output`;
}

function uniqueVariableName(value: string, existingVariables: Set<string>) {
  const base = normalizeVariableName(value) || "agent_output";
  let candidate = base;
  let suffix = 2;
  while (existingVariables.has(candidate)) {
    candidate = `${base}_${suffix}`;
    suffix += 1;
  }
  return candidate;
}

function ensureSystemTrigger(nextNodes: WorkflowEditorNode[], catalog: WorkflowDesignerCatalog) {
  if (nextNodes.some((node) => node.id === SYSTEM_TRIGGER_ID)) {
    return nextNodes;
  }

  return [createNodeFromTemplate(catalog.systemTrigger, 0, []), ...nextNodes];
}

function toEditorNode(node: WorkflowNodeDraft): WorkflowEditorNode {
  const config = (node.config ?? {}) as Record<string, unknown>;
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
      configStatus: readLiteral(config.configStatus, ["complete", "incomplete"], fallback.configStatus),
      runState: readLiteral(config.runState, ["未开始", "等待输入", "执行中", "等待审核", "已完成", "待配置"], fallback.runState),
      outputMode: readLiteral(config.outputMode, ["一次性输出", "追问确认"], fallback.outputMode),
      toolCount: readNumber(config.toolCount, fallback.toolCount),
      allowQuestion: readBoolean(config.allowQuestion, fallback.allowQuestion),
      allowUserEdit: readBoolean(
        config.allowUserEdit,
        config.outputMode === "追问确认" ? true : fallback.allowUserEdit,
      ),
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
  const normalizedConfig = normalizeWorkflowNodeConfig(
    node.data.nodeType,
    (node.data.rawConfig ?? {}) as Record<string, unknown>,
  );
  return {
    nodeId: node.id,
    nodeType: node.data.nodeType,
    name: node.data.label,
    positionX: node.position.x,
    positionY: node.position.y,
    inputVariables: node.data.inputVariables,
    outputVariables: node.data.outputVariables,
    config: {
      ...normalizedConfig,
      typeLabel: node.data.typeLabel,
      summary: node.data.summary,
      configStatus: node.data.configStatus,
      runState: node.data.runState,
      outputMode: node.data.outputMode,
      toolCount: node.data.toolCount,
      allowQuestion: node.data.allowQuestion,
      allowUserEdit: node.data.allowUserEdit,
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
    configStatus: "incomplete",
    runState: "待配置",
    outputMode: "一次性输出",
    toolCount: 0,
    allowUserEdit: false,
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
  const brickType = getBrickType(node);
  const references = new Set<string>();
  collectRuntimeTemplateTextFields(node.data.rawConfig, brickType).forEach((field) => {
    extractTemplateVariableNames(field.text).forEach((variable) => {
      if (previousVariables.has(variable)) {
        references.add(variable);
      }
    });
  });
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
        systemPrompt: readString(agent.systemPrompt, DEFAULT_SYSTEM_PROMPT),
        userPrompt: readString(agent.userPrompt, DEFAULT_CLUSTER_USER_PROMPT),
        maxTokens: readOptionalInt(agent.maxTokens),
        allowUserEdit: readBoolean(agent.allowUserEdit, false),
        allowQuestion: readBoolean(agent.allowQuestion, false),
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

function readOptionalInt(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return Math.trunc(value);
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number.parseInt(value.trim(), 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
  }
  return undefined;
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

function parseNumberInput(value: string, fallback: number): number {
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
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

function readDocumentDeliveryStyle(rawStyle: unknown, capabilityConfig?: Record<string, unknown>): DocumentDeliveryStyleDraft {
  const configDefaultStyle = capabilityConfig?.defaultStyle;
  const defaultStyle = isRecord(configDefaultStyle) ? configDefaultStyle : {};
  const nodeStyle = isRecord(rawStyle) ? rawStyle : {};
  const merged = {
    ...DEFAULT_WORD_DOCUMENT_STYLE,
    ...defaultStyle,
    ...nodeStyle,
  };
  return {
    chineseFont: readString(merged.chineseFont, String(DEFAULT_WORD_DOCUMENT_STYLE.chineseFont)),
    latinFont: readString(merged.latinFont, String(DEFAULT_WORD_DOCUMENT_STYLE.latinFont)),
    bodyFontSize: readFontSizeLike(merged.bodyFontSize, DEFAULT_WORD_DOCUMENT_STYLE.bodyFontSize),
    heading1FontSize: readFontSizeLike(merged.heading1FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading1FontSize),
    heading2FontSize: readFontSizeLike(merged.heading2FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading2FontSize),
    heading3FontSize: readFontSizeLike(merged.heading3FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading3FontSize),
    heading1ChineseFont: readOptionalString(merged.heading1ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading1ChineseFont),
    heading1LatinFont: readOptionalString(merged.heading1LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading1LatinFont),
    heading2ChineseFont: readOptionalString(merged.heading2ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading2ChineseFont),
    heading2LatinFont: readOptionalString(merged.heading2LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading2LatinFont),
    heading3ChineseFont: readOptionalString(merged.heading3ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading3ChineseFont),
    heading3LatinFont: readOptionalString(merged.heading3LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading3LatinFont),
    tableChineseFont: readOptionalString(merged.tableChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.tableChineseFont),
    tableLatinFont: readOptionalString(merged.tableLatinFont, DEFAULT_WORD_DOCUMENT_STYLE.tableLatinFont),
    tableFontSize: readTableFontSizeLike(merged.tableFontSize, DEFAULT_WORD_DOCUMENT_STYLE.tableFontSize),
    tableCellAlignment: readString(merged.tableCellAlignment, DEFAULT_WORD_DOCUMENT_STYLE.tableCellAlignment),
    lineSpacing: readNumberLike(merged.lineSpacing, Number(DEFAULT_WORD_DOCUMENT_STYLE.lineSpacing)),
    firstLineIndentChars: readNumberLike(merged.firstLineIndentChars, Number(DEFAULT_WORD_DOCUMENT_STYLE.firstLineIndentChars)),
    paragraphSpacingBefore: readNumberLike(merged.paragraphSpacingBefore, Number(DEFAULT_WORD_DOCUMENT_STYLE.paragraphSpacingBefore)),
    paragraphSpacingAfter: readNumberLike(merged.paragraphSpacingAfter, Number(DEFAULT_WORD_DOCUMENT_STYLE.paragraphSpacingAfter)),
    marginTopCm: readNumberLike(merged.marginTopCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginTopCm)),
    marginBottomCm: readNumberLike(merged.marginBottomCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginBottomCm)),
    marginLeftCm: readNumberLike(merged.marginLeftCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginLeftCm)),
    marginRightCm: readNumberLike(merged.marginRightCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginRightCm)),
    titleCentered: readBooleanLike(merged.titleCentered, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.titleCentered)),
    headingFirstLineIndent: readBooleanLike(merged.headingFirstLineIndent, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.headingFirstLineIndent)),
  };
}

function readFontSizeLike(value: unknown, fallback: string | number | boolean): string | number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  return typeof fallback === "boolean" ? 12 : fallback;
}

function readTableFontSizeLike(value: unknown, fallback: string | number): string | number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value <= 0 ? 0 : value;
  }
  if (typeof value === "string") {
    const text = value.trim();
    if (!text || text === "0") {
      return 0;
    }
    return text;
  }
  return fallback;
}

function readOptionalString(value: unknown, fallback: string): string {
  if (typeof value !== "string") {
    return fallback;
  }
  return value.trim();
}

function readNumberLike(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number.parseFloat(value.trim());
    return Number.isFinite(parsed) ? parsed : fallback;
  }
  return fallback;
}

function readBooleanLike(value: unknown, fallback: boolean): boolean {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (["true", "1", "yes", "是", "开启"].includes(normalized)) return true;
    if (["false", "0", "no", "否", "关闭"].includes(normalized)) return false;
  }
  return fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function variableToReferenceItem(variable: WorkflowVariable): VariableReferenceItem {
  const flags = [
    variable.deliverable ? "交付可见" : "",
    variable.sensitive ? "敏感变量" : "",
  ].filter(Boolean).join("，");
  return {
    name: variable.name,
    sourceLabel: `节点「${variable.sourceNodeName}」输出变量（${variable.sourceNodeId}）`,
    typeLabel: workflowVariableTypeLabel(variable.type),
    description: [variable.description || "可引用该节点输出内容。", flags].filter(Boolean).join("；"),
  };
}

function workflowVariableTypeLabel(type: WorkflowVariable["type"]): string {
  return ({
    string: "文本",
    number: "数字",
    object: "对象",
    array: "列表",
    boolean: "布尔",
    decision: "决策",
    file: "文件",
  })[type];
}

function appendFileNameToken(template: string, token: string): string {
  if (template.includes(token)) {
    return template;
  }
  const current = template.trim() || "交付文档.docx";
  const hasDocxSuffix = current.toLowerCase().endsWith(".docx");
  const base = hasDocxSuffix ? current.slice(0, -5) : current;
  const separator = base.endsWith("-") || base.endsWith("_") || base.endsWith(" ") ? "" : "-";
  return `${base}${separator}${token}${hasDocxSuffix ? ".docx" : ""}`;
}

function normalizeWordFileNameTemplate(template: string): string {
  return template.replace(/\{\{\s*runId\s*\}\}/g, "{{runNumber}}");
}

function renderDesignTemplate(template: string, variables: WorkflowVariable[]) {
  const sampleValues = new Map<string, string>([
    ["runNumber", "RUN-20260615-001"],
    ["nodeRunId", "preview_node"],
    ["date", "2026-06-15"],
    ["dateCompact", "20260615"],
    ["started_at", "2026-06-15"],
  ]);
  variables.forEach((variable) => {
    sampleValues.set(variable.name, `${variable.sourceNodeName}-${variable.name}`);
  });
  return template.replace(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g, (_match, variable: string) => sampleValues.get(variable) ?? variable);
}

function ensureDocxFileName(value: string) {
  const sanitized = value.trim().replace(/[\\/:*?"<>|]/g, "_") || "交付文档";
  return sanitized.toLowerCase().endsWith(".docx") ? sanitized : `${sanitized}.docx`;
}

function downloadFile(file: FileDownloadResponse) {
  const url = window.URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => window.URL.revokeObjectURL(url), 30_000);
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
