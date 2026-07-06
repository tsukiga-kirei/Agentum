import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { App, Drawer, Select, Tooltip } from "antd";
import {
  AlertTriangle,
  AlignCenter,
  ArrowDown,
  ArrowUp,
  Bold,
  Bot,
  Boxes,
  BrainCircuit,
  ChevronDown,
  ChevronLeft,
  CheckCircle2,
  Clock3,
  Columns3,
  Copy,
  DatabaseZap,
  FileText,
  Filter,
  Hash,
  Layers3,
  ListChecks,
  PackageCheck,
  Palette,
  Pin,
  Plus,
  Rows3,
  Save,
  Search,
  ServerCog,
  Settings2,
  Sigma,
  Tag,
  TextCursorInput,
  Type,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { WorkbenchGlobalActions } from "../../components/workbench/SurfacePageLayout";
import { SysImpactConfirmModal } from "../../components/common/SysImpactConfirmModal";
import { SysModalMask } from "../../components/common/SysModalMask";
import { DocumentDeliveryStyleSections } from "../../components/document/DocumentDeliveryStyleSections";
import { readLineSpacingMode, readSpacingUnit, type DocumentDeliveryStyleValues, type ParagraphRule } from "../../constants/documentDeliveryStyleOptions";
import { AgentumApiError, assetApi, workflowApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import { formatTemplateVariable, insertTemplateToken } from "../../utils/templateTextInsertion";
import type { AssetType, MyAssetRow, SystemCapabilityAssetRow } from "../../types/asset";
import type {
  AgentRuntimeLimits,
  WorkflowBrickTemplate,
  WorkflowDesignerCatalog,
  WorkflowModelOption,
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
  canInsertWorkflowBrick,
  canMoveWorkflowNode,
  collectRuntimeTemplateTextFields,
  collectRuntimeTemplateVariableNames,
  describeDeleteNodeVariableImpact,
  describeMoveNodeVariableImpact,
  extractTemplateVariableNames,
  summarizeValidationIssues,
  validateWorkflowDeliveryPlacement,
  WORKFLOW_SYSTEM_TEMPLATE_VARIABLES,
  type WorkflowNodeValidationIssue,
} from "./workflowNodeValidation";
import type { InputFieldConfig } from "../../types/runtime-types";
import {
  createInputFieldOption,
  createInputField,
  getInputFieldTypeLabel,
  normalizeInputField,
  normalizeInputFieldOptions,
  readInputFields,
  shouldSyncInputFieldOptionValue,
  validateInputFieldDraft,
  WORKFLOW_INPUT_FIELD_TYPE_OPTIONS,
  type WorkflowInputFieldType,
} from "../../utils/workflowInputField";

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
type ClusterExecutionMode = "collaborative" | "relay" | "intent";
type IntentFallbackMode = "fail" | "agent" | "fixed_reply";
type IntentSelectionMode = "single" | "multiple";

type IntentRouteConfig = {
  id: string;
  intentCode: string;
  intentName: string;
  intentDescription: string;
  agentId: string;
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
  maxAgentIterationsPerTurn: number;
  allowUserEdit: boolean;
  allowQuestion: boolean;
  modelProviderId: string;
  modelName: string;
  enableThinking: boolean;
  intentCode: string;
  intentName: string;
  intentDescription: string;
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
type DeliveryConfigMode = "single" | "multiple";
type DeliveryExecutionPolicy = "all" | "conditional";
type DeliveryTriggerType = "always" | "cluster_agent_matched" | "input_field_equals" | "agent_output_exists";

type DeliveryTriggerRuleDraft = {
  type: DeliveryTriggerType;
  clusterNodeId: string;
  agentId: string;
  inputNodeId: string;
  agentNodeId: string;
  variableName: string;
  expectedValue: string;
};

type DeliveryItemDraft = {
  id: string;
  name: string;
  enabled: boolean;
  triggerRule: DeliveryTriggerRuleDraft;
  config: Record<string, unknown>;
};

type ExcelCellType = "text" | "number" | "currency" | "percent" | "date" | "datetime" | "boolean";

type ExcelTableStyleDraft = {
  headerBold: boolean;
  freezeHeader: boolean;
  autoFilter: boolean;
  fontName: string;
  fontSize: number;
  horizontalAlignment: string;
};

type ExcelColumnRuleDraft = {
  id: string;
  target: "letter" | "name";
  letter: string;
  name: string;
  applyFromRow: number;
  type: ExcelCellType;
  format: string;
  width: number;
  bold: boolean;
  backgroundColor: string;
  horizontalAlignment: string;
};

type ExcelRowRuleDraft = {
  id: string;
  target: "header" | "last" | "index" | "contains";
  index: number;
  text: string;
  bold: boolean;
  backgroundColor: string;
};

type ExcelCellRuleDraft = {
  id: string;
  cell: string;
  type: ExcelCellType;
  format: string;
  bold: boolean;
  backgroundColor: string;
  horizontalAlignment: string;
  allowFormula: boolean;
};

type ExcelSheetDraft = {
  id: string;
  name: string;
  startCell: string;
  defaultCellType: ExcelCellType;
  bodyTemplate: string;
  tableStyle: ExcelTableStyleDraft;
  columnRules: ExcelColumnRuleDraft[];
  rowRules: ExcelRowRuleDraft[];
  cellRules: ExcelCellRuleDraft[];
};

type ClusterAgentTriggerOption = {
  value: string;
  label: string;
  clusterNodeId: string;
  clusterName: string;
  agentId: string;
  agentName: string;
  variableName: string;
};

type InputFieldTriggerOption = {
  value: string;
  label: string;
  inputNodeId: string;
  inputNodeName: string;
  fieldLabel: string;
  variableName: string;
};

type AgentOutputTriggerOption = {
  value: string;
  label: string;
  agentNodeId: string;
  agentNodeName: string;
  variableName: string;
};

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
const SYSTEM_RUNTIME_VARIABLE_SOURCE_ID = "__system_runtime__";
const workflowSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const workflowSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;
const DEFAULT_WORD_DOCUMENT_STYLE: DocumentDeliveryStyleDraft = {
  chineseFont: "宋体",
  latinFont: "Times New Roman",
  numberFont: "Times New Roman",
  bodyFontSize: 12,
  bodyAlignment: "left",
  heading1FontSize: 16,
  heading2FontSize: 14,
  heading3FontSize: 13,
  heading4FontSize: 0,
  heading5FontSize: 0,
  heading1ChineseFont: "",
  heading1LatinFont: "",
  heading1NumberFont: "",
  heading2ChineseFont: "",
  heading2LatinFont: "",
  heading2NumberFont: "",
  heading3ChineseFont: "",
  heading3LatinFont: "",
  heading3NumberFont: "",
  heading4ChineseFont: "",
  heading4LatinFont: "",
  heading4NumberFont: "",
  heading5ChineseFont: "",
  heading5LatinFont: "",
  heading5NumberFont: "",
  heading1Bold: true,
  heading2Bold: true,
  heading3Bold: true,
  heading4Bold: true,
  heading5Bold: true,
  tableChineseFont: "",
  tableLatinFont: "",
  tableNumberFont: "",
  tableFontSize: 0,
  tableCellAlignment: "left",
  tableCellVerticalAlignment: "center",
  tableCellPaddingVerticalPt: 1.5,
  tableHeaderBold: false,
  tableBorders: true,
  tableBorderWidthPt: 0.5,
  tableLineSpacingMode: "multiple",
  tableLineSpacing: 1,
  tableLineSpacingPt: 12,
  lineSpacingMode: "multiple",
  lineSpacing: 1.5,
  lineSpacingPt: 18,
  firstLineIndentMode: "chars",
  firstLineIndentChars: 2,
  firstLineIndentCm: 0.75,
  paragraphSpacingUnit: "pt",
  paragraphSpacingBefore: 0,
  paragraphSpacingAfter: 6,
  marginTopCm: 2.54,
  marginBottomCm: 2.54,
  marginLeftCm: 3.18,
  marginRightCm: 3.18,
  titleCentered: false,
  headingFirstLineIndent: false,
  paragraphRules: [],
};

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

const EXCEL_FILE_NAME_VARIABLES = WORD_FILE_NAME_VARIABLES;

const EXCEL_CELL_TYPE_OPTIONS: Array<{ value: ExcelCellType; label: string }> = [
  { value: "text", label: "纯文本" },
  { value: "number", label: "数字" },
  { value: "currency", label: "金额" },
  { value: "percent", label: "百分比" },
  { value: "date", label: "日期" },
  { value: "datetime", label: "日期时间" },
  { value: "boolean", label: "布尔值" },
];

const EXCEL_COLUMN_TARGET_OPTIONS = [
  { value: "letter", label: "列字母 (A/B/C)" },
  { value: "name", label: "按列名称" },
];

const EXCEL_ROW_TARGET_OPTIONS = [
  { value: "header", label: "表头行" },
  { value: "last", label: "最后一行" },
  { value: "index", label: "指定行号" },
  { value: "contains", label: "包含关键字的行" },
];

const EXCEL_ALIGNMENT_OPTIONS = [
  { value: "", label: "默认左对齐" },
  { value: "left", label: "左对齐" },
  { value: "center", label: "居中" },
  { value: "right", label: "右对齐" },
];

const EXCEL_COLOR_OPTIONS: Array<{ value: string; label: string; color?: string }> = [
  { value: "", label: "不设置底色" },
  { value: "grey", label: "浅灰", color: "#E7E6E6" },
  { value: "yellow", label: "浅黄", color: "#FFF2CC" },
  { value: "green", label: "浅绿", color: "#E2EFDA" },
  { value: "blue", label: "浅蓝", color: "#DDEBF7" },
  { value: "red", label: "浅红", color: "#FCE4D6" },
];

const EXCEL_FONT_OPTIONS = [
  { value: "", label: "默认字体" },
  { value: "微软雅黑", label: "微软雅黑" },
  { value: "宋体", label: "宋体" },
  { value: "仿宋", label: "仿宋" },
  { value: "仿宋_GB2312", label: "仿宋_GB2312" },
  { value: "等线", label: "等线" },
  { value: "Arial", label: "Arial" },
  { value: "Calibri", label: "Calibri" },
];

const EXCEL_FONT_SIZE_OPTIONS = [
  { value: "0", label: "默认字号" },
  { value: "9", label: "9" },
  { value: "10", label: "10" },
  { value: "11", label: "11" },
  { value: "12", label: "12" },
  { value: "14", label: "14" },
  { value: "16", label: "16" },
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
    description: "编排多个智能体协同、接力或按意图分派",
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
        const nextNodes = hasPersistedGraph
          ? ensureSystemTrigger(detail.nodes.map((node) => toEditorNode(node, catalog.agentRuntimeLimits)), catalog)
          : [createNodeFromTemplate(catalog.systemTrigger, 0, [])];
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
  const systemRuntimeVariables = useMemo(
    () => buildSystemRuntimeVariables(designerCatalog?.variableMetadata),
    [designerCatalog?.variableMetadata],
  );
  const businessVariables = workflowVariables.filter((variable) => variable.sourceNodeId !== SYSTEM_TRIGGER_ID);
  const availableVariables = workflowVariables.filter((variable) => {
    if (!selectedNode) {
      return true;
    }
    const sourceIndex = visibleNodes.findIndex((node) => node.id === variable.sourceNodeId);

    return sourceIndex >= 0 && sourceIndex < selectedNodeIndex;
  });
  const availableTemplateVariables = useMemo(
    () => [...systemRuntimeVariables, ...availableVariables],
    [availableVariables, systemRuntimeVariables],
  );
  const nodeValidationMap = useMemo(
    () => buildWorkflowNodeValidationMap(visibleNodes),
    [visibleNodes],
  );
  const incompleteNodes = useMemo(
    () => visibleNodes.filter((node) => (nodeValidationMap.get(node.id)?.length ?? 0) > 0),
    [visibleNodes, nodeValidationMap],
  );
  const deliveryPlacementIssues = useMemo(
    () => validateWorkflowDeliveryPlacement(visibleNodes),
    [visibleNodes],
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
      setDeclaredVariables(toWorkflowVariables(
        mergedDetail.variables,
        mergedDetail.nodes.map((node) => toEditorNode(node, designerCatalog.agentRuntimeLimits)),
      ));
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
    const insertAfterNodeId = selectedNodeId || visibleNodes[visibleNodes.length - 1]?.id;
    const insertError = canInsertWorkflowBrick(visibleNodes, brickType, insertAfterNodeId);
    if (insertError) {
      messageApi.warning(insertError);
      return;
    }
    const template = designerCatalog.brickTemplates.find((item) => item.brickType === brickType);
    if (!template) {
      messageApi.error("当前积木模板不存在，请刷新后重试");
      return;
    }
    const insertAfterIndex = insertAfterNodeId ? visibleNodes.findIndex((node) => node.id === insertAfterNodeId) : -1;
    const insertIndex = insertAfterIndex >= 0 ? insertAfterIndex + 1 : visibleNodes.length;
    const previousOutputs = insertAfterIndex >= 0 ? visibleNodes[insertAfterIndex].data.outputVariables : [];
    const existingVariables = new Set(visibleNodes.flatMap((node) => node.data.outputVariables).filter(Boolean));
    const nextNode = uniquifyNewNodeOutputVariables(createNodeFromTemplate(template, insertIndex + 1, previousOutputs), existingVariables);
    const nextVisibleNodes = [...visibleNodes];
    nextVisibleNodes.splice(insertIndex, 0, nextNode);
    commitVisibleNodes(nextVisibleNodes, nextNode.id);
    setIsAddBrickModalOpen(false);
  }

  function handleMoveNode(nodeId: string, direction: -1 | 1) {
    const moveError = canMoveWorkflowNode(visibleNodes, nodeId, direction);
    if (moveError) {
      messageApi.warning(moveError);
      return;
    }
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
          {selectedNode && designerCatalog ? (
            <NodeConfigPanel
              node={selectedNode}
              workflowNodes={visibleNodes}
              availableVariables={availableTemplateVariables}
              workflowVariables={workflowVariables}
              capabilities={capabilityOptions}
              capabilitiesLoading={capabilitiesLoading}
              capabilityError={capabilityError}
              agentRuntimeLimits={designerCatalog.agentRuntimeLimits}
              modelOptions={designerCatalog.modelOptions}
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
              deliveryPlacementIssues={deliveryPlacementIssues}
              onSelectNode={setSelectedNodeId}
              onOpenAddBrick={() => setIsAddBrickModalOpen(true)}
            />
          )}
        </div>
      </div>

      {isAddBrickModalOpen ? (
        <AddBrickModal
          templates={designerCatalog?.brickTemplates ?? []}
          visibleNodes={visibleNodes}
          insertAfterNodeId={selectedNodeId || visibleNodes[visibleNodes.length - 1]?.id}
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
            canMoveUp={index > 0 && canMoveWorkflowNode(nodes, node.id, -1) === null}
            canMoveDown={index < nodes.length - 1 && canMoveWorkflowNode(nodes, node.id, 1) === null}
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
  visibleNodes,
  insertAfterNodeId,
  onClose,
  onSelect,
}: {
  templates: WorkflowBrickTemplate[];
  visibleNodes: WorkflowEditorNode[];
  insertAfterNodeId?: string;
  onClose: () => void;
  onSelect: (brickType: VisibleWorkflowBrickType) => void;
}) {
  const hasDelivery = visibleNodes.some((node) => getBrickType(node) === "delivery");
  const insertAfterNode = insertAfterNodeId ? visibleNodes.find((node) => node.id === insertAfterNodeId) : null;
  const insertLabel = insertAfterNode ? `添加到「${insertAfterNode.data.label}」后` : "添加第一个积木";

  return (
    <SysModalMask onClose={onClose}>
      <section className="sys-modal" style={{ maxWidth: 720 }} aria-labelledby="add-brick-title">
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>{insertLabel}</div>
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
              const disabledReason = canInsertWorkflowBrick(visibleNodes, brickType, insertAfterNodeId);
              const Icon = definition.icon;

              return (
                <button
                  key={brickType}
                  type="button"
                  disabled={Boolean(disabledReason)}
                  onClick={() => onSelect(brickType)}
                  className={`flex min-h-[116px] items-start gap-3 rounded-[var(--radius-lg)] border p-4 text-left transition ${
                    disabledReason
                      ? "cursor-not-allowed border-[var(--color-border-light)] bg-[var(--color-bg-hover)] opacity-60"
                      : "border-[var(--color-border-light)] bg-[var(--color-bg-hover)] hover:border-[var(--color-primary)]"
                  }`}
                >
                  <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-md ${definition.accentClass}`}>
                    <Icon className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold text-[var(--color-text-primary)]">{template.label}</span>
                    <span className="mt-2 block text-sm leading-6 text-[var(--color-text-secondary)]">
                      {disabledReason ?? template.description}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
          {hasDelivery ? (
            <p className="mt-3 text-xs text-[var(--color-text-tertiary)]">
              交付节点必须位于流程最后一步；已有交付节点时，只能在它前面的步骤后继续插入普通积木。
            </p>
          ) : null}
          {templates.length === 0 ? (
            <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">
              暂未加载到可添加积木模板，请稍后刷新。
            </p>
          ) : null}
        </div>
      </section>
    </SysModalMask>
  );
}
function NodeConfigPanel({
  node,
  workflowNodes,
  availableVariables,
  workflowVariables,
  capabilities,
  capabilitiesLoading,
  capabilityError,
  agentRuntimeLimits,
  modelOptions,
  onUpdateNode,
  onUpdateConfig,
  onSyncAgentConfig,
}: {
  node: WorkflowEditorNode;
  workflowNodes: WorkflowEditorNode[];
  availableVariables: WorkflowVariable[];
  workflowVariables: WorkflowVariable[];
  capabilities: WorkflowCapabilityOption[];
  capabilitiesLoading: boolean;
  capabilityError: string;
  agentRuntimeLimits: AgentRuntimeLimits;
  modelOptions: WorkflowModelOption[];
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
            <SingleAgentBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} agentRuntimeLimits={agentRuntimeLimits} modelOptions={modelOptions} onSyncConfig={onSyncAgentConfig} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "cluster" ? (
            <AgentClusterBrickConfig node={node} availableVariables={availableVariables} capabilityState={capabilityState} agentRuntimeLimits={agentRuntimeLimits} modelOptions={modelOptions} onUpdateConfig={onUpdateConfig} onUpdateNode={onUpdateNode} />
          ) : null}

          {brickType === "delivery" ? (
            <DeliveryBrickConfig node={node} workflowNodes={workflowNodes} workflowVariables={availableVariables} capabilityState={capabilityState} onUpdateConfig={onUpdateConfig} />
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
  deliveryPlacementIssues,
  onSelectNode,
  onOpenAddBrick,
}: {
  nodes: WorkflowEditorNode[];
  variables: WorkflowVariable[];
  incompleteNodes: WorkflowEditorNode[];
  nodeValidationMap: Map<string, WorkflowNodeValidationIssue[]>;
  deliveryPlacementIssues: WorkflowNodeValidationIssue[];
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
        {deliveryPlacementIssues.length > 0 ? (
          <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/20 dark:text-amber-300">
            {summarizeValidationIssues(deliveryPlacementIssues)}
          </div>
        ) : null}
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
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">输入字段</span>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={() => setEditingField(createInputField(fields.length, "text"))} className="agent-button h-8 px-3 text-xs">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增文本框
          </button>
          <button type="button" onClick={() => setEditingField(createInputField(fields.length, "select"))} className="agent-button h-8 px-3 text-xs">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增下拉框
          </button>
        </div>
      </div>
      <div className="space-y-3">
        {fields.map((field, index) => (
          <article key={field.id} className="workflow-input-field-row">
            <span className="workflow-inline-card-icon">
              {field.fieldType === "select"
                ? <ChevronDown className="h-4 w-4" aria-hidden="true" />
                : <TextCursorInput className="h-4 w-4" aria-hidden="true" />}
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium text-[var(--color-text-tertiary)]">{getInputFieldTypeLabel(field.fieldType)} {index + 1}</span>
                <TinyBadge>{field.variable}</TinyBadge>
                {field.fieldType === "select" ? <TinyBadge tone="info">选项 {field.options?.length ?? 0}</TinyBadge> : null}
                {field.required ? <TinyBadge tone="warning">必填</TinyBadge> : <TinyBadge>选填</TinyBadge>}
              </div>
              <p className="mt-1 truncate text-sm font-semibold text-[var(--color-text-primary)]">{field.label}</p>
              <p className="mt-1 line-clamp-1 text-xs text-[var(--color-text-secondary)]">
                {field.fieldType === "select"
                  ? (field.options?.map((option) => option.label).join(" / ") || "未配置下拉选项")
                  : (field.placeholder || "未设置占位提示")}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-1">
              <button type="button" onClick={() => setEditingField(field)} className="agent-button h-8 px-2 text-xs">编辑</button>
              <IconButton label="删除输入框" icon={Trash2} tone="danger" onClick={() => commitFields(fields.filter((item) => item.id !== field.id))} />
            </div>
          </article>
        ))}
        {fields.length === 0 ? (
          <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">暂无输入字段</p>
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
  agentRuntimeLimits,
  modelOptions,
  onSyncConfig,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  agentRuntimeLimits: AgentRuntimeLimits;
  modelOptions: WorkflowModelOption[];
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
                    modelProviderId: modelOptions[0]?.providerId ?? "",
                    modelName: modelOptions[0]?.modelName ?? "",
                    enableThinking: false,
                    maxAgentIterationsPerTurn: agentRuntimeLimits.suggestedIterationsPerTurn,
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
          agentRuntimeLimits={agentRuntimeLimits}
          modelOptions={modelOptions}
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
  agentRuntimeLimits,
  modelOptions,
  onUpdateConfig,
  onUpdateNode,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  agentRuntimeLimits: AgentRuntimeLimits;
  modelOptions: WorkflowModelOption[];
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
  onUpdateNode: (patch: Partial<EditorNodeData>) => void;
}) {
  const config = node.data.rawConfig ?? {};
  const [editingAgent, setEditingAgent] = useState<ClusterAgentConfig | null>(null);
  const [intentDrawerOpen, setIntentDrawerOpen] = useState(false);
  const themeMode = useAuthStore((state) => state.themeMode);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode, "workflow-agent-drawer");
  const agents = readClusterAgents(config.clusterAgents, agentRuntimeLimits);
  const intentRoutes = readIntentRoutes(config.intentRoutes, agents);
  const fallbackMode = readIntentFallbackMode(config.intentFallbackMode);
  const intentSelectionMode = readIntentSelectionMode(config.intentSelectionMode);
  const executionMode = readClusterExecutionMode(config.executionMode);
  const clusterOutputVariable = readClusterOutputVariable(config.clusterOutputVariable);
  const mergeRule = readString(config.mergeRule, buildDefaultClusterMergeRule(agents));
  const agentAssets = filterCapabilities(capabilityState.capabilities, "agent_template");
  const promptAssets = filterCapabilities(capabilityState.capabilities, "prompt_template");
  const mcpAssets = filterCapabilities(capabilityState.capabilities, "mcp");
  const skillAssets = filterCapabilities(capabilityState.capabilities, "skill");
  const intentModel = modelOptions.find((model) => model.providerId === readString(config.intentModelProviderId, ""))
    ?? modelOptions[0];

  function commitAgents(nextAgents: ClusterAgentConfig[]) {
    onUpdateConfig({ clusterAgents: nextAgents });
    onUpdateNode({ toolCount: nextAgents.length, outputVariables: buildClusterOutputVariables(executionMode, nextAgents, clusterOutputVariable) });
  }

  function handleExecutionModeChange(value: string) {
    const nextMode = readClusterExecutionMode(value);
    onUpdateConfig({
      executionMode: nextMode,
      intentSelectionMode,
      intentFallbackMode: readString(config.intentFallbackMode, "fail"),
      intentRoutes: readIntentRoutes(config.intentRoutes, agents),
      intentInputTemplate: readString(config.intentInputTemplate, ""),
      clusterOutputVariable,
      mergeRule,
      intentModelProviderId: readString(config.intentModelProviderId, intentModel?.providerId ?? ""),
      intentModelName: readString(config.intentModelName, intentModel?.modelName ?? ""),
      intentEnableThinking: readBoolean(config.intentEnableThinking, false),
    });
    onUpdateNode({ outputVariables: buildClusterOutputVariables(nextMode, agents, clusterOutputVariable) });
  }

  function moveAgent(agentId: string, direction: -1 | 1) {
    const index = agents.findIndex((item) => item.id === agentId);
    const nextIndex = index + direction;
    if (index < 0 || nextIndex < 0 || nextIndex >= agents.length) {
      return;
    }
    const nextAgents = [...agents];
    [nextAgents[index], nextAgents[nextIndex]] = [nextAgents[nextIndex], nextAgents[index]];
    commitAgents(nextAgents);
  }

  function duplicateAgent(agent: ClusterAgentConfig) {
    const sourceIndex = agents.findIndex((item) => item.id === agent.id);
    const insertIndex = sourceIndex >= 0 ? sourceIndex + 1 : agents.length;
    const newAgentId = `cluster_agent_${Date.now().toString(36)}_${agents.length + 1}`;
    const usedNames = new Set(agents.map((item) => item.name).filter(Boolean));
    const usedOutputs = new Set(agents.map((item) => item.output).filter(Boolean));
    const usedIntentCodes = new Set(agents.map((item) => item.intentCode).filter(Boolean));
    const copiedAgent: ClusterAgentConfig = {
      ...agent,
      id: newAgentId,
      name: uniqueDisplayName(`${agent.name || "子智能体"} 副本`, usedNames),
      output: uniqueVariableName(agent.output ? `${agent.output}_copy` : createClusterAgentOutputVariable(node.id, agents.length), usedOutputs),
      intentCode: uniqueVariableName(agent.intentCode ? `${agent.intentCode}_copy` : `intent_${agents.length + 1}`, usedIntentCodes),
      intentName: uniqueDisplayName(`${agent.intentName || agent.name || "意图"} 副本`, new Set(agents.map((item) => item.intentName).filter(Boolean))),
    };
    const nextAgents = [...agents];
    nextAgents.splice(insertIndex, 0, copiedAgent);
    const existingRoutes = readIntentRoutes(config.intentRoutes, agents);
    const routeToCopy = existingRoutes.find((route) => route.agentId === agent.id);
    const nextConfig: Record<string, unknown> = { clusterAgents: nextAgents };
    if (routeToCopy) {
      const routeInsertIndex = existingRoutes.findIndex((route) => route.id === routeToCopy.id) + 1;
      const copiedRoute: IntentRouteConfig = {
        ...routeToCopy,
        id: `intent_route_${newAgentId}`,
        agentId: newAgentId,
        intentCode: copiedAgent.intentCode,
        intentName: copiedAgent.intentName,
      };
      const nextRoutes = [...existingRoutes];
      nextRoutes.splice(Math.max(0, routeInsertIndex), 0, copiedRoute);
      nextConfig.intentRoutes = nextRoutes;
    }
    onUpdateConfig(nextConfig);
    onUpdateNode({ toolCount: nextAgents.length, outputVariables: buildClusterOutputVariables(executionMode, nextAgents, clusterOutputVariable) });
  }

  function agentVariables(agent: ClusterAgentConfig) {
    return clusterAgentAvailableVariables(availableVariables, agents, agent.id, executionMode);
  }

  return (
    <PanelGroup title="智能体集群配置" icon={Layers3} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <div className="workflow-config-list-box">
        <div className="workflow-config-list-header">
          <span>集群智能体</span>
          <button type="button" onClick={() => setEditingAgent(createClusterAgent(agents.length, node.id, agentRuntimeLimits.suggestedIterationsPerTurn, modelOptions[0]))} className="agent-button agent-button-primary h-8 px-3 text-xs">
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增智能体
          </button>
        </div>
        <SelectLikeField
          label="执行方式"
          icon={Layers3}
          value={executionMode}
          options={[
            { value: "collaborative", label: "协同处理（全部子智能体独立产出）" },
            { value: "relay", label: "接力处理（按顺序传递上一步输出）" },
            { value: "intent", label: "意图分派（先分类，再执行命中智能体）" },
          ]}
          onChange={handleExecutionModeChange}
        />
        {executionMode === "intent" ? (
          <IntentRoutingSummary
            routes={intentRoutes}
            agents={agents}
            selectionMode={intentSelectionMode}
            fallbackMode={fallbackMode}
            fallbackAgentId={readString(config.fallbackAgentId, "")}
            fallbackReply={readString(config.fallbackReply, "")}
            onOpen={() => setIntentDrawerOpen(true)}
          />
        ) : null}
          <ClusterOutputConfig
          executionMode={executionMode}
          agents={agents}
          outputVariable={clusterOutputVariable}
          mergeRule={mergeRule}
          onChange={(patch) => {
            const nextOutputVariable = readClusterOutputVariable(patch.clusterOutputVariable ?? clusterOutputVariable);
            onUpdateConfig(patch);
            onUpdateNode({ outputVariables: buildClusterOutputVariables(executionMode, agents, nextOutputVariable) });
          }}
        />
        <div className="workflow-cluster-agent-list">
          {agents.map((agent, index) => {
            const variableIssues = clusterAgentVariableIssues(agent, index, agents, executionMode, availableVariables);
            return (
            <article key={agent.id} className="workflow-cluster-agent-row">
              <div className="workflow-cluster-agent-index">
                <Bot className="h-4 w-4" aria-hidden="true" />
              </div>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{agent.name}</p>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  <TinyBadge>智能体 {index + 1}</TinyBadge>
                  {executionMode === "intent" ? <TinyBadge tone="info">意图 {intentRoutes.filter((route) => route.agentId === agent.id).length}</TinyBadge> : <TinyBadge>{agent.output}</TinyBadge>}
                  <TinyBadge tone="info">Skill {agent.skillIds.length}</TinyBadge>
                  <TinyBadge tone="info">MCP {agent.mcpIds.length}</TinyBadge>
                  {variableIssues.length > 0 ? <TinyBadge tone="warning">变量需处理</TinyBadge> : null}
                  <AgentInteractionFeatureBadges
                    allowUserEdit={agent.allowUserEdit}
                    allowQuestion={agent.allowQuestion}
                  />
                </div>
                {variableIssues.length > 0 ? (
                  <p className="mt-2 text-[11px] leading-4 text-amber-700 dark:text-amber-300">{variableIssues.join("；")}</p>
                ) : null}
              </div>
              <div className="flex shrink-0 items-center gap-1">
                <IconButton label="上移智能体" icon={ArrowUp} disabled={index === 0} onClick={() => moveAgent(agent.id, -1)} />
                <IconButton label="下移智能体" icon={ArrowDown} disabled={index === agents.length - 1} onClick={() => moveAgent(agent.id, 1)} />
                <IconButton label="复制智能体" icon={Copy} onClick={() => duplicateAgent(agent)} />
                <button type="button" onClick={() => setEditingAgent(agent)} className="agent-button h-8 px-2 text-xs">编辑</button>
                <IconButton label="删除智能体" icon={Trash2} tone="danger" onClick={() => commitAgents(agents.filter((item) => item.id !== agent.id))} />
              </div>
            </article>
            );
          })}
          {agents.length === 0 ? (
            <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] bg-[var(--color-bg-card)] px-3 py-4 text-center text-sm text-[var(--color-text-tertiary)]">暂无智能体</p>
          ) : null}
        </div>
      </div>
      {editingAgent ? (
        <ClusterAgentModal
          agent={editingAgent}
          availableVariables={agentVariables(editingAgent)}
          agentAssets={agentAssets}
          promptAssets={promptAssets}
          mcpAssets={mcpAssets}
          skillAssets={skillAssets}
          agentRuntimeLimits={agentRuntimeLimits}
          modelOptions={modelOptions}
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
      {intentDrawerOpen ? (
        <IntentRoutingDrawer
          routes={intentRoutes}
          agents={agents}
          availableVariables={availableVariables}
          selectionMode={intentSelectionMode}
          fallbackMode={fallbackMode}
          fallbackAgentId={readString(config.fallbackAgentId, "")}
          fallbackReply={readString(config.fallbackReply, "")}
          intentInputTemplate={readString(config.intentInputTemplate, "")}
          modelOptions={modelOptions}
          intentModelProviderId={readString(config.intentModelProviderId, intentModel?.providerId ?? "")}
          intentEnableThinking={readBoolean(config.intentEnableThinking, false)}
          rootClassName={drawerRootClassName}
          onClose={() => setIntentDrawerOpen(false)}
          onSave={(next) => {
            onUpdateConfig({
              intentRoutes: next.routes,
              intentSelectionMode: next.selectionMode,
              intentInputTemplate: next.intentInputTemplate,
              intentFallbackMode: next.fallbackMode,
              fallbackAgentId: next.fallbackAgentId,
              fallbackReply: next.fallbackReply,
              intentModelProviderId: next.intentModelProviderId,
              intentModelName: next.intentModelName,
              intentEnableThinking: next.intentEnableThinking,
            });
            setIntentDrawerOpen(false);
          }}
        />
      ) : null}
    </PanelGroup>
  );
}

function IntentRoutingSummary({
  routes,
  agents,
  selectionMode,
  fallbackMode,
  fallbackAgentId,
  fallbackReply,
  onOpen,
}: {
  routes: IntentRouteConfig[];
  agents: ClusterAgentConfig[];
  selectionMode: IntentSelectionMode;
  fallbackMode: IntentFallbackMode;
  fallbackAgentId: string;
  fallbackReply: string;
  onOpen: () => void;
}) {
  const fallbackAgent = agents.find((agent) => agent.id === fallbackAgentId);
  const fallbackText = fallbackMode === "agent"
    ? `其他情况转交：${fallbackAgent?.name ?? "未选择智能体"}`
    : fallbackMode === "fixed_reply"
      ? `其他情况回复：${fallbackReply || "未填写话术"}`
      : "其他情况：中止并提示";

  return (
    <section className="workflow-intent-summary">
      <div className="workflow-intent-summary-head">
        <div>
          <p className="text-sm font-semibold text-[var(--color-text-primary)]">意图路由</p>
          <p className="mt-1 text-xs leading-5 text-[var(--color-text-tertiary)]">运行时只执行命中的智能体；{selectionMode === "single" ? "只取一个最匹配意图。" : "多个意图命中时按下方顺序写入输出模板。"}</p>
        </div>
        <button type="button" className="agent-button agent-button-primary h-8 px-3 text-xs" onClick={onOpen}>
          <Settings2 className="h-3.5 w-3.5" aria-hidden="true" />
          配置意图
        </button>
      </div>
      <div className="workflow-intent-route-list">
        {routes.length > 0 ? routes.map((route, index) => {
          const agent = agents.find((item) => item.id === route.agentId);
          return (
            <div key={route.id} className="workflow-intent-route-row">
              <span className="workflow-intent-route-index">{index}</span>
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium text-[var(--color-text-primary)]">{route.intentName || route.intentCode}</span>
                <span className="mt-0.5 block truncate text-xs text-[var(--color-text-tertiary)]">到 {agent?.name ?? "未选择智能体"} · {route.intentDescription || "未填写命中说明"}</span>
              </span>
            </div>
          );
        }) : (
          <p className="rounded bg-[var(--color-bg-card)] px-3 py-3 text-sm text-[var(--color-text-tertiary)] ring-1 ring-[var(--color-border-light)]">还没有配置意图。</p>
        )}
      </div>
      <p className="workflow-intent-fallback">{fallbackText}</p>
    </section>
  );
}

function ClusterOutputConfig({
  executionMode,
  agents,
  outputVariable,
  mergeRule,
  onChange,
}: {
  executionMode: ClusterExecutionMode;
  agents: ClusterAgentConfig[];
  outputVariable: string;
  mergeRule: string;
  onChange: (patch: Record<string, unknown>) => void;
}) {
  const childVariables = agents
    .map((agent): WorkflowVariable | null => {
      if (!agent.output) {
        return null;
      }
      return {
        name: agent.output,
        sourceNodeId: agent.id,
        sourceNodeName: `子智能体「${agent.name}」`,
        type: "string",
        sensitive: false,
        deliverable: false,
        description: executionMode === "intent"
          ? "意图命中该子智能体时写入；未命中时在输出模板中按空值处理"
          : "子智能体完成后写入的文本结果",
      };
    })
    .filter((item): item is WorkflowVariable => item !== null);

  return (
    <section className="workflow-intent-summary">
      <div className="workflow-intent-summary-head">
        <div>
          <p className="text-sm font-semibold text-[var(--color-text-primary)]">输出配置</p>
          <p className="mt-1 text-xs leading-5 text-[var(--color-text-tertiary)]">
            默认把子智能体结果拼成最终输出；意图未命中的子智能体变量会按空值处理。
          </p>
        </div>
      </div>
      <div className="grid gap-4">
        <OutcomeVariableField
          label="最终输出变量"
          value={outputVariable}
          placeholder="cluster_result"
          onChange={(value) => onChange({ clusterOutputVariable: normalizeVariableName(value) || "cluster_result" })}
        />
        <PromptEditor
          label="输出内容模板"
          value={mergeRule}
          availableVariables={childVariables}
          placeholder={buildDefaultClusterMergeRule(agents)}
          onChange={(value) => onChange({ mergeRule: value })}
        />
      </div>
    </section>
  );
}

function IntentRoutingDrawer({
  routes,
  agents,
  availableVariables,
  selectionMode,
  fallbackMode,
  fallbackAgentId,
  fallbackReply,
  intentInputTemplate,
  modelOptions,
  intentModelProviderId,
  intentEnableThinking,
  rootClassName,
  onClose,
  onSave,
}: {
  routes: IntentRouteConfig[];
  agents: ClusterAgentConfig[];
  availableVariables: WorkflowVariable[];
  selectionMode: IntentSelectionMode;
  fallbackMode: IntentFallbackMode;
  fallbackAgentId: string;
  fallbackReply: string;
  intentInputTemplate: string;
  modelOptions: WorkflowModelOption[];
  intentModelProviderId: string;
  intentEnableThinking: boolean;
  rootClassName: string;
  onClose: () => void;
  onSave: (next: {
    routes: IntentRouteConfig[];
    selectionMode: IntentSelectionMode;
    intentInputTemplate: string;
    fallbackMode: IntentFallbackMode;
    fallbackAgentId: string;
    fallbackReply: string;
    intentModelProviderId: string;
    intentModelName: string;
    intentEnableThinking: boolean;
  }) => void;
}) {
  const { message } = App.useApp();
  const [draftRoutes, setDraftRoutes] = useState<IntentRouteConfig[]>(routes);
  const [draftSelectionMode, setDraftSelectionMode] = useState<IntentSelectionMode>(selectionMode);
  const [draftIntentInputTemplate, setDraftIntentInputTemplate] = useState(intentInputTemplate);
  const [draftFallbackMode, setDraftFallbackMode] = useState<IntentFallbackMode>(fallbackMode);
  const [draftFallbackAgentId, setDraftFallbackAgentId] = useState(fallbackAgentId);
  const [draftFallbackReply, setDraftFallbackReply] = useState(fallbackReply);
  const initialIntentModel = modelOptions.find((model) => model.providerId === intentModelProviderId) ?? modelOptions[0];
  const [draftIntentModel, setDraftIntentModel] = useState({
    modelProviderId: initialIntentModel?.providerId ?? "",
    modelName: initialIntentModel?.modelName ?? "",
    enableThinking: initialIntentModel?.reasoningModel ? intentEnableThinking : false,
  });
  const agentOptions = agents.map((agent) => ({ value: agent.id, label: agent.name }));

  function updateRoute(routeId: string, patch: Partial<IntentRouteConfig>) {
    setDraftRoutes((current) => current.map((route) => route.id === routeId ? { ...route, ...patch } : route));
  }

  function addRoute() {
    const nextIndex = draftRoutes.length + 1;
    setDraftRoutes((current) => [
      ...current,
      {
        id: `intent_route_${Date.now().toString(36)}_${nextIndex}`,
        intentCode: uniqueVariableName(`intent_${nextIndex}`, new Set(current.map((route) => route.intentCode))),
        intentName: `意图 ${nextIndex}`,
        intentDescription: "",
        agentId: agents[0]?.id ?? "",
      },
    ]);
  }

  function moveRoute(routeId: string, direction: -1 | 1) {
    const index = draftRoutes.findIndex((route) => route.id === routeId);
    const nextIndex = index + direction;
    if (index < 0 || nextIndex < 0 || nextIndex >= draftRoutes.length) {
      return;
    }
    const nextRoutes = [...draftRoutes];
    [nextRoutes[index], nextRoutes[nextIndex]] = [nextRoutes[nextIndex], nextRoutes[index]];
    setDraftRoutes(nextRoutes);
  }

  return (
    <Drawer
      title="配置意图路由"
      placement="right"
      width={WORKFLOW_AGENT_DRAWER_WIDTH}
      open
      destroyOnClose
      onClose={onClose}
      rootClassName={rootClassName}
    >
      <div className="sys-drawer-section sys-drawer-section-enter workflow-agent-drawer-body">
        <p className="workflow-agent-drawer-kicker">意图分派</p>
        <div className="workflow-modal-section grid gap-3 md:grid-cols-2">
          <ModelSelectField
            modelOptions={modelOptions}
            modelProviderId={draftIntentModel.modelProviderId}
            enableThinking={draftIntentModel.enableThinking}
            onChange={setDraftIntentModel}
          />
          <SelectLikeField
            label="命中数量"
            icon={ListChecks}
            value={draftSelectionMode}
            options={[
              { value: "single", label: "单意图（只执行一个智能体）" },
              { value: "multiple", label: "多意图（可执行多个智能体）" },
            ]}
            onChange={(value) => setDraftSelectionMode(readIntentSelectionMode(value))}
          />
          <div className="md:col-span-2">
            <AgentBehaviorCapsules
              allowUserEdit={false}
              allowQuestion={false}
              enableThinking={draftIntentModel.enableThinking}
              showThinking={(modelOptions.find((model) => model.providerId === draftIntentModel.modelProviderId) ?? modelOptions[0])?.reasoningModel}
              showAllowUserEdit={false}
              showAllowQuestion={false}
              onChange={(patch) => setDraftIntentModel({ ...draftIntentModel, ...patch })}
            />
          </div>
          <div className="md:col-span-2">
            <PromptEditor
              label="待判断内容"
              value={draftIntentInputTemplate}
              availableVariables={availableVariables}
              placeholder="例如：请根据 {{input_1}} 判断本次需要执行哪些报告智能体。"
              onChange={setDraftIntentInputTemplate}
            />
          </div>
        </div>
        <div className="workflow-intent-drawer-toolbar">
          <button type="button" className="agent-button agent-button-primary h-8 px-3 text-xs" onClick={addRoute}>
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            新增意图
          </button>
        </div>
        <div className="workflow-intent-drawer-list">
          {draftRoutes.map((route, index) => (
            <article key={route.id} className="workflow-intent-editor-card">
              <div className="workflow-intent-editor-card-head">
                <span className="workflow-intent-route-index">{index}</span>
                <div className="flex items-center gap-1">
                  <IconButton label="上移意图" icon={ArrowUp} disabled={index === 0} onClick={() => moveRoute(route.id, -1)} />
                  <IconButton label="下移意图" icon={ArrowDown} disabled={index === draftRoutes.length - 1} onClick={() => moveRoute(route.id, 1)} />
                  <IconButton label="删除意图" icon={Trash2} tone="danger" onClick={() => setDraftRoutes((current) => current.filter((item) => item.id !== route.id))} />
                </div>
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <TextInputField
                  label="意图名称"
                  icon={Type}
                  value={route.intentName}
                  placeholder="月报生成"
                  onChange={(value) => updateRoute(route.id, { intentName: value })}
                />
                <label className="sys-field md:col-span-2">
                  <span className="sys-field-label">目标智能体</span>
                  <Select
                    className="agent-admin-select w-full"
                    classNames={workflowSelectClassNames}
                    prefix={<Bot className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={workflowSelectSuffixIcon}
                    value={route.agentId || undefined}
                    placeholder="选择命中后要执行的智能体"
                    options={agentOptions}
                    onChange={(value) => updateRoute(route.id, { agentId: value })}
                  />
                </label>
                <div className="md:col-span-2">
                  <PromptEditor
                    label="命中条件"
                    value={route.intentDescription}
                    availableVariables={availableVariables}
                    placeholder="例如：{{input_1}} 中要求生成某个月份的经营月报、月度金融业务分析或月度监管材料。"
                    onChange={(value) => updateRoute(route.id, { intentDescription: value })}
                  />
                </div>
              </div>
            </article>
          ))}
          {draftRoutes.length === 0 ? (
            <p className="rounded-[var(--radius-md)] border border-dashed border-[var(--color-border-light)] px-3 py-5 text-center text-sm text-[var(--color-text-tertiary)]">还没有意图，先新增一个。</p>
          ) : null}
        </div>
        <div className="workflow-modal-section grid gap-4 md:grid-cols-2">
          <SelectLikeField
            label="其他情况"
            icon={AlertTriangle}
            value={draftFallbackMode}
            options={[
              { value: "fail", label: "中止并提示" },
              { value: "agent", label: "转交一个智能体" },
              { value: "fixed_reply", label: "回复固定话术" },
            ]}
            onChange={(value) => setDraftFallbackMode(readIntentFallbackMode(value))}
          />
          {draftFallbackMode === "agent" ? (
            <label className="sys-field">
              <span className="sys-field-label">其他情况智能体</span>
              <Select
                className="agent-admin-select w-full"
                classNames={workflowSelectClassNames}
                prefix={<Bot className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                suffixIcon={workflowSelectSuffixIcon}
                value={draftFallbackAgentId || undefined}
                placeholder="选择智能体"
                options={agentOptions}
                onChange={setDraftFallbackAgentId}
              />
            </label>
          ) : null}
          {draftFallbackMode === "fixed_reply" ? (
            <label className="sys-field md:col-span-2">
              <span className="sys-field-label">固定话术</span>
              <textarea
                value={draftFallbackReply}
                onChange={(event) => setDraftFallbackReply(event.target.value)}
                className="sys-field-textarea"
                placeholder="暂时无法判断该需求应该交给哪个智能体处理，请补充更明确的信息。"
              />
            </label>
          ) : null}
        </div>
      </div>
      <div className="sys-drawer-footer">
        <div className="sys-drawer-footer-right">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button
            type="button"
            className="sys-btn sys-btn--primary"
            onClick={() => {
              const normalizedRoutes = draftRoutes.map((route, index) => ({
                ...route,
                intentCode: normalizeIntentCode(route.intentCode) || `intent_${index + 1}`,
                intentName: route.intentName.trim() || `意图 ${index + 1}`,
                intentDescription: route.intentDescription.trim(),
              }));
              if (!draftIntentInputTemplate.trim()) {
                message.error("请先填写待判断内容，并引用需要用于意图判断的上游变量");
                return;
              }
              const duplicateCode = normalizedRoutes.find((route, index) => normalizedRoutes.some((item, itemIndex) => itemIndex !== index && item.intentCode === route.intentCode));
              if (duplicateCode) {
                message.error(`存在重复的意图代码：${duplicateCode.intentCode}`);
                return;
              }
              const invalidRoute = normalizedRoutes.find((route) => !route.agentId || !route.intentDescription);
              if (invalidRoute) {
                message.error("每个意图都需要选择目标智能体，并说明什么时候命中");
                return;
              }
              if (draftFallbackMode === "agent" && !draftFallbackAgentId) {
                message.error("其他情况需要选择目标智能体");
                return;
              }
              if (draftFallbackMode === "fixed_reply" && !draftFallbackReply.trim()) {
                message.error("其他情况固定话术不能为空");
                return;
              }
              onSave({
                routes: normalizedRoutes,
                selectionMode: draftSelectionMode,
                intentInputTemplate: draftIntentInputTemplate.trim(),
                fallbackMode: draftFallbackMode,
                fallbackAgentId: draftFallbackMode === "agent" ? draftFallbackAgentId : "",
                fallbackReply: draftFallbackMode === "fixed_reply" ? draftFallbackReply.trim() : "",
                intentModelProviderId: draftIntentModel.modelProviderId,
                intentModelName: draftIntentModel.modelName,
                intentEnableThinking: draftIntentModel.enableThinking,
              });
            }}
          >
            保存
          </button>
        </div>
      </div>
    </Drawer>
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
  excelSheets: [],
} as const;

const DELIVERY_TRIGGER_OPTIONS = [
  { value: "always", label: "始终触发" },
  { value: "cluster_agent_matched", label: "命中智能体集群子智能体" },
  { value: "input_field_equals", label: "输入字段等于固定值" },
  { value: "agent_output_exists", label: "单智能体有输出内容" },
];

function DeliveryBrickConfig({
  node,
  workflowNodes,
  workflowVariables,
  capabilityState,
  onUpdateConfig,
}: {
  node: WorkflowEditorNode;
  workflowNodes: WorkflowEditorNode[];
  workflowVariables: WorkflowVariable[];
  capabilityState: WorkflowCapabilityState;
  onUpdateConfig: (nextConfig: Record<string, unknown>) => void;
}) {
  const themeMode = useAuthStore((state) => state.themeMode);
  const config = node.data.rawConfig ?? {};
  const deliveryMode = readString(config.deliveryMode, "direct");
  const isDirectDelivery = deliveryMode === "direct" || readString(config.deliveryType, "") === "direct";
  const deliveryConfigMode = readDeliveryConfigMode(config.deliveryConfigMode);
  const deliveryExecutionPolicy = readDeliveryExecutionPolicy(config.deliveryExecutionPolicy);
  const deliveryAssets = filterCapabilities(capabilityState.capabilities, "delivery");
  const wordDeliveryAssets = deliveryAssets.filter((option) => isWordDocumentDeliveryCapability(option));
  const excelDeliveryAssets = deliveryAssets.filter((option) => isExcelWorkbookDeliveryCapability(option));
  const defaultWordDeliveryCapability = wordDeliveryAssets[0];
  const defaultExcelDeliveryCapability = excelDeliveryAssets[0];
  const defaultDocumentDeliveryCapability = defaultWordDeliveryCapability ?? defaultExcelDeliveryCapability ?? deliveryAssets[0];
  const rawSelectedCapabilityId = readString(config.deliveryCapabilityId, "");
  const selectedCapabilityId = rawSelectedCapabilityId === "none" ? "" : rawSelectedCapabilityId;
  const effectiveSelectedCapabilityId = selectedCapabilityId || defaultDocumentDeliveryCapability?.id || "";
  const selectedDeliveryCapability = deliveryAssets.find((option) => option.id === selectedCapabilityId)
    ?? deliveryAssets.find((option) => option.id === effectiveSelectedCapabilityId);
  const defaultMarkdownVariable = workflowVariables.find((variable) => variable.deliverable)?.name ?? workflowVariables[workflowVariables.length - 1]?.name ?? "";
  const defaultDirectTemplate = defaultMarkdownVariable
    ? `# 交付结果\n\n{{${defaultMarkdownVariable}}}`
    : "# 交付结果\n\n请在这里编写最终交付内容。";
  const defaultMarkdownTemplate = defaultMarkdownVariable
    ? `# 交付文档\n\n{{${defaultMarkdownVariable}}}`
    : "# 交付文档\n\n请在这里编写最终 Markdown 交付正文。";
  const defaultExcelSheetTemplate = defaultMarkdownVariable
    ? `{{${defaultMarkdownVariable}}}`
    : "| 项目 | 内容 |\n| --- | --- |\n| 示例 | 请替换为上游模型输出 |\n";
  const deliveryItems = readDeliveryItems(config.deliveryItems, deliveryMode);
  const clusterAgentTriggerOptions = buildClusterAgentTriggerOptions(workflowNodes);
  const inputFieldTriggerOptions = buildInputFieldTriggerOptions(workflowNodes);
  const agentOutputTriggerOptions = buildAgentOutputTriggerOptions(workflowNodes);
  const [editingTarget, setEditingTarget] = useState<"single" | string | null>(null);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode, "workflow-agent-drawer");

  useEffect(() => {
    if (isDirectDelivery) {
      return;
    }
    if (deliveryConfigMode !== "single") {
      return;
    }
    if (!rawSelectedCapabilityId && defaultDocumentDeliveryCapability) {
      const defaultCapabilityConfig = buildCapabilityConfig(defaultDocumentDeliveryCapability, config);
      onUpdateConfig({
        ...defaultCapabilityConfig,
      });
    }
  }, [isDirectDelivery, deliveryConfigMode, rawSelectedCapabilityId, defaultDocumentDeliveryCapability?.id]);

  function buildDirectDeliveryConfig(sourceConfig: Record<string, unknown> = config) {
    return {
      deliveryMode: "direct",
      deliveryType: "direct",
      deliveryCapabilityId: "none",
      documentKind: "",
      ...STALE_WORD_DELIVERY_FIELDS,
      deliveryContent: readString(sourceConfig.deliveryContent, readString(sourceConfig.deliveryTarget, readString(sourceConfig.body, defaultDirectTemplate))),
    };
  }

  function buildDeliveryItemConfig(sourceConfig: Record<string, unknown> = config) {
    const sourceDeliveryMode = readString(sourceConfig.deliveryMode, deliveryMode);
    if (sourceDeliveryMode === "direct" || readString(sourceConfig.deliveryType, "") === "direct") {
      return buildDirectDeliveryConfig(sourceConfig);
    }
    const capabilityId = readString(sourceConfig.deliveryCapabilityId, "");
    const capability = deliveryAssets.find((option) => option.id === capabilityId) ?? selectedDeliveryCapability ?? defaultDocumentDeliveryCapability;
    return buildCapabilityConfig(capability, sourceConfig);
  }

  function handleDeliveryModeChange(value: string) {
    if (value === "direct") {
      const nextItems = deliveryConfigMode === "multiple"
        ? (deliveryItems.length > 0
          ? deliveryItems.map((item) => ({ ...item, config: buildDirectDeliveryConfig(item.config) }))
          : [createDeliveryItemDraft(1, buildDirectDeliveryConfig({}), "交付项 1")])
        : [];
      onUpdateConfig({
        deliveryMode: "direct",
        deliveryType: "direct",
        deliveryCapabilityId: "none",
        documentKind: "",
        deliveryConfigMode,
        deliveryItems: nextItems,
        ...STALE_WORD_DELIVERY_FIELDS,
        deliveryContent: readString(config.deliveryContent, defaultDirectTemplate),
      });
      return;
    }
    const capability = defaultDocumentDeliveryCapability;
    const nextItems = deliveryConfigMode === "multiple"
      ? (deliveryItems.length > 0
        ? deliveryItems.map((item) => ({ ...item, config: buildCapabilityConfig(capability, item.config) }))
        : [createDeliveryItemDraft(1, buildCapabilityConfig(capability, {}), "交付项 1")])
      : [];
    if (capability && (isWordDocumentDeliveryCapability(capability) || isExcelWorkbookDeliveryCapability(capability))) {
      onUpdateConfig({
        ...buildCapabilityConfig(capability, config),
        deliveryConfigMode,
        deliveryItems: nextItems,
      });
      return;
    }
    onUpdateConfig({
      deliveryMode: "capability",
      deliveryCapabilityId: capability?.id ?? "",
      deliveryType: capability ? readString(capability.config?.deliveryChannel, readString(capability.config?.sourceType, "")) : "",
      documentKind: "",
      deliveryConfigMode,
      deliveryItems: nextItems,
    });
  }

  function buildCapabilityConfig(capability?: WorkflowCapabilityOption, sourceConfig: Record<string, unknown> = config) {
    if (capability && isWordDocumentDeliveryCapability(capability)) {
      return {
        deliveryMode: "capability",
        deliveryCapabilityId: capability.id,
        deliveryType: "word_document",
        documentKind: "word",
        excelSheets: [],
        ...STALE_DIRECT_DELIVERY_FIELDS,
        fileNameTemplate: normalizeWordFileNameTemplate(readString(sourceConfig.fileNameTemplate, "交付文档-{{runNumber}}.docx")),
        markdownContent: readString(sourceConfig.markdownContent, defaultMarkdownTemplate),
        documentStyle: readDocumentDeliveryStyle(sourceConfig.documentStyle, capability.config),
      };
    }
    if (capability && isExcelWorkbookDeliveryCapability(capability)) {
      return {
        deliveryMode: "capability",
        deliveryCapabilityId: capability.id,
        deliveryType: "excel_workbook",
        documentKind: "excel",
        markdownContent: "",
        ...STALE_DIRECT_DELIVERY_FIELDS,
        fileNameTemplate: normalizeExcelFileNameTemplate(readString(sourceConfig.fileNameTemplate, "交付表格-{{runNumber}}.xlsx")),
        excelSheets: readExcelSheets(sourceConfig.excelSheets, defaultExcelSheetTemplate),
      };
    }
    return {
      deliveryMode: "capability",
      deliveryCapabilityId: capability?.id ?? "",
      deliveryType: capability ? readString(capability.config?.deliveryChannel, readString(capability.config?.sourceType, "")) : "",
      documentKind: "",
    };
  }

  function handleSingleCapabilityChange(value: string) {
    const capability = deliveryAssets.find((option) => option.id === value);
    onUpdateConfig(buildCapabilityConfig(capability));
  }

  function handleDeliveryConfigModeChange(value: string) {
    const nextMode = value === "multiple" ? "multiple" : "single";
    if (nextMode === "single") {
      const firstItem = deliveryItems[0];
      onUpdateConfig({
        deliveryMode,
        deliveryConfigMode: "single",
        deliveryItems: [],
        ...(firstItem?.config ?? {}),
      });
      return;
    }
    const items = deliveryItems.length > 0
      ? deliveryItems
      : [createDeliveryItemDraft(1, buildDeliveryItemConfig(config), "交付项 1")];
    onUpdateConfig({
      deliveryMode,
      deliveryConfigMode: "multiple",
      deliveryExecutionPolicy: "all",
      deliveryItems: items,
    });
  }

  function updateDeliveryItem(itemId: string, patch: Partial<DeliveryItemDraft>) {
    const nextItems = deliveryItems.map((item) => item.id === itemId ? { ...item, ...patch } : item);
    onUpdateConfig({ deliveryItems: nextItems });
  }

  function updateDeliveryItemConfig(itemId: string, patch: Record<string, unknown>) {
    const nextItems = deliveryItems.map((item) => item.id === itemId ? { ...item, config: { ...item.config, ...patch } } : item);
    onUpdateConfig({ deliveryItems: nextItems });
  }

  function updateDeliveryItemMode(itemId: string, value: string) {
    const nextItems = deliveryItems.map((item) => {
      if (item.id !== itemId) {
        return item;
      }
      if (value === "direct") {
        return { ...item, config: buildDirectDeliveryConfig(item.config) };
      }
      const capabilityId = readString(item.config.deliveryCapabilityId, "");
      const capability = deliveryAssets.find((option) => option.id === capabilityId) ?? defaultDocumentDeliveryCapability;
      return { ...item, config: buildCapabilityConfig(capability, item.config) };
    });
    onUpdateConfig({ deliveryItems: nextItems });
  }

  function addDeliveryItem() {
    const nextItem = createDeliveryItemDraft(deliveryItems.length + 1, buildDirectDeliveryConfig({}), `交付项 ${deliveryItems.length + 1}`);
    onUpdateConfig({
      deliveryConfigMode: "multiple",
      deliveryExecutionPolicy: deliveryExecutionPolicy,
      deliveryItems: [...deliveryItems, nextItem],
    });
    setEditingTarget(nextItem.id);
  }

  function duplicateDeliveryItem(item: DeliveryItemDraft) {
    const sourceIndex = deliveryItems.findIndex((current) => current.id === item.id);
    const insertIndex = sourceIndex >= 0 ? sourceIndex + 1 : deliveryItems.length;
    const copiedItem: DeliveryItemDraft = {
      id: `delivery_item_${Date.now().toString(36)}_${deliveryItems.length + 1}`,
      name: uniqueDisplayName(`${item.name || "交付项"} 副本`, new Set(deliveryItems.map((current) => current.name).filter(Boolean))),
      enabled: item.enabled,
      triggerRule: { ...item.triggerRule },
      config: cloneRecord(item.config),
    };
    const nextItems = [...deliveryItems];
    nextItems.splice(insertIndex, 0, copiedItem);
    onUpdateConfig({
      deliveryConfigMode: "multiple",
      deliveryExecutionPolicy,
      deliveryItems: nextItems,
    });
    setEditingTarget(copiedItem.id);
  }

  function removeDeliveryItem(itemId: string) {
    const nextItems = deliveryItems.filter((item) => item.id !== itemId);
    onUpdateConfig({ deliveryItems: nextItems });
    if (editingTarget === itemId) {
      setEditingTarget(null);
    }
  }

  const editingItem = deliveryItems.find((item) => item.id === editingTarget) ?? null;

  function renderDeliveryItemSummary(itemConfig: Record<string, unknown>) {
    if (readString(itemConfig.deliveryMode, deliveryMode) === "direct" || readString(itemConfig.deliveryType, "") === "direct") {
      return "直接交付 · 节点内模板";
    }
    const capabilityId = readString(itemConfig.deliveryCapabilityId, "");
    const capability = deliveryAssets.find((option) => option.id === capabilityId);
    const typeText = isWordDocumentDeliveryCapability(capability) || readString(itemConfig.deliveryType, "") === "word_document"
      ? "Word 文档交付"
      : isExcelWorkbookDeliveryCapability(capability) || readString(itemConfig.deliveryType, "") === "excel_workbook"
        ? "Excel 工作簿交付"
        : capability ? "能力交付" : "未选择能力";
    return `${typeText}${capability ? ` · ${capability.name} · ${capability.version}` : ""}`;
  }

  return (
    <PanelGroup title="交付配置" icon={PackageCheck} className="xl:col-span-2">
      <CapabilityStateBanner state={capabilityState} />
      <SelectLikeField
        label="交付项数量"
        icon={ListChecks}
        value={deliveryConfigMode}
        options={[
          { value: "single", label: "单个交付项" },
          { value: "multiple", label: "多个交付项" },
        ]}
        onChange={handleDeliveryConfigModeChange}
      />
      {deliveryConfigMode === "single" ? (
        <SelectLikeField
          label="交付方式"
          icon={PackageCheck}
          value={deliveryMode}
          options={[
            { value: "direct", label: "直接交付（节点内配置，无需分配能力）" },
            { value: "capability", label: "能力交付（Word / Excel / 邮件等系统能力）" },
          ]}
          onChange={handleDeliveryModeChange}
        />
      ) : null}

      {deliveryConfigMode === "single" ? (
        <div className="mt-4 rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-secondary)] p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">单个交付项</h4>
              <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{renderDeliveryItemSummary(config)}</p>
            </div>
            <button type="button" className="agent-button h-9 px-3 text-xs" onClick={() => setEditingTarget("single")}>
              <Settings2 size={14} />
              配置交付项
            </button>
          </div>
        </div>
      ) : (
        <div className="mt-4 space-y-4">
          <SelectLikeField
            label="执行策略"
            icon={ListChecks}
            value={deliveryExecutionPolicy}
            options={[
              { value: "all", label: "全部交付项都执行" },
              { value: "conditional", label: "按触发规则执行" },
            ]}
            onChange={(value) => onUpdateConfig({ deliveryExecutionPolicy: value === "conditional" ? "conditional" : "all" })}
          />
          <div className="space-y-3">
            {deliveryItems.map((item, index) => (
              <div key={item.id} className="rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-secondary)] p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-[var(--color-text-tertiary)]">#{index + 1}</span>
                      <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">{item.name}</h4>
                      {!item.enabled ? <TinyBadge>停用</TinyBadge> : null}
                    </div>
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{renderDeliveryItemSummary(item.config)}</p>
                    <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">{deliveryExecutionPolicy === "conditional" ? describeDeliveryTrigger(item.triggerRule) : "执行策略：全部执行"}</p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <button type="button" className="agent-button h-8 px-3 text-xs" onClick={() => setEditingTarget(item.id)}>
                      <Settings2 size={14} />
                      配置
                    </button>
                    <button type="button" className="agent-icon-button h-8 w-8" title="复制交付项" onClick={() => duplicateDeliveryItem(item)}>
                      <Copy size={14} />
                    </button>
                    <button type="button" className="agent-icon-button h-8 w-8" title="删除交付项" onClick={() => removeDeliveryItem(item.id)}>
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
              </div>
            ))}
            <button type="button" className="agent-button h-9 px-3 text-xs" onClick={addDeliveryItem}>
              <Plus size={14} />
              添加交付项
            </button>
          </div>
        </div>
      )}

      <DeliveryCapabilityConfigDrawer
        open={editingTarget === "single"}
        rootClassName={drawerRootClassName}
        title="配置单个交付项"
        config={config}
        workflowVariables={workflowVariables}
        deliveryAssets={deliveryAssets}
        clusterAgentTriggerOptions={clusterAgentTriggerOptions}
        inputFieldTriggerOptions={inputFieldTriggerOptions}
        agentOutputTriggerOptions={agentOutputTriggerOptions}
        defaultDirectTemplate={defaultDirectTemplate}
        defaultMarkdownTemplate={defaultMarkdownTemplate}
        defaultExcelSheetTemplate={defaultExcelSheetTemplate}
        onCapabilityChange={handleSingleCapabilityChange}
        onConfigChange={onUpdateConfig}
        onClose={() => setEditingTarget(null)}
      />
      {editingItem ? (
        <DeliveryCapabilityConfigDrawer
          open
          rootClassName={drawerRootClassName}
          title="配置交付项"
          itemName={editingItem.name}
          enabled={editingItem.enabled}
          triggerRule={editingItem.triggerRule}
          showTrigger={deliveryExecutionPolicy === "conditional"}
          config={editingItem.config}
          workflowVariables={workflowVariables}
          deliveryAssets={deliveryAssets}
          clusterAgentTriggerOptions={clusterAgentTriggerOptions}
          inputFieldTriggerOptions={inputFieldTriggerOptions}
          agentOutputTriggerOptions={agentOutputTriggerOptions}
          defaultDirectTemplate={defaultDirectTemplate}
          defaultMarkdownTemplate={defaultMarkdownTemplate}
          defaultExcelSheetTemplate={defaultExcelSheetTemplate}
          showDeliveryModeSelector
          onDeliveryModeChange={(value) => updateDeliveryItemMode(editingItem.id, value)}
          onItemNameChange={(name) => updateDeliveryItem(editingItem.id, { name })}
          onEnabledChange={(enabled) => updateDeliveryItem(editingItem.id, { enabled })}
          onTriggerRuleChange={(triggerRule) => updateDeliveryItem(editingItem.id, { triggerRule })}
          onCapabilityChange={(capabilityId) => {
            const capability = deliveryAssets.find((option) => option.id === capabilityId);
            updateDeliveryItemConfig(editingItem.id, buildCapabilityConfig(capability, editingItem.config));
          }}
          onConfigChange={(patch) => updateDeliveryItemConfig(editingItem.id, patch)}
          onClose={() => setEditingTarget(null)}
        />
      ) : null}
    </PanelGroup>
  );
}

function DeliveryCapabilityConfigDrawer({
  open,
  rootClassName,
  title,
  itemName,
  enabled,
  triggerRule,
  showTrigger = false,
  config,
  workflowVariables,
  deliveryAssets,
  clusterAgentTriggerOptions,
  inputFieldTriggerOptions,
  agentOutputTriggerOptions,
  defaultDirectTemplate,
  defaultMarkdownTemplate,
  defaultExcelSheetTemplate,
  showDeliveryModeSelector = false,
  onDeliveryModeChange,
  onItemNameChange,
  onEnabledChange,
  onTriggerRuleChange,
  onCapabilityChange,
  onConfigChange,
  onClose,
}: {
  open: boolean;
  rootClassName: string;
  title: string;
  itemName?: string;
  enabled?: boolean;
  triggerRule?: DeliveryTriggerRuleDraft;
  showTrigger?: boolean;
  config: Record<string, unknown>;
  workflowVariables: WorkflowVariable[];
  deliveryAssets: WorkflowCapabilityOption[];
  clusterAgentTriggerOptions: ClusterAgentTriggerOption[];
  inputFieldTriggerOptions: InputFieldTriggerOption[];
  agentOutputTriggerOptions: AgentOutputTriggerOption[];
  defaultDirectTemplate: string;
  defaultMarkdownTemplate: string;
  defaultExcelSheetTemplate: string;
  showDeliveryModeSelector?: boolean;
  onDeliveryModeChange?: (value: string) => void;
  onItemNameChange?: (name: string) => void;
  onEnabledChange?: (enabled: boolean) => void;
  onTriggerRuleChange?: (rule: DeliveryTriggerRuleDraft) => void;
  onCapabilityChange?: (capabilityId: string) => void;
  onConfigChange: (patch: Record<string, unknown>) => void;
  onClose: () => void;
}) {
  const deliveryMode = readString(config.deliveryMode, "direct");
  const isDirectDelivery = deliveryMode === "direct" || readString(config.deliveryType, "") === "direct";
  const selectedCapabilityId = readString(config.deliveryCapabilityId, "");
  const selectedCapability = deliveryAssets.find((option) => option.id === selectedCapabilityId);
  const isWordDelivery = isWordDocumentDeliveryCapability(selectedCapability)
    || readString(config.deliveryType, "") === "word_document"
    || readString(config.documentKind, "") === "word";
  const isExcelDelivery = isExcelWorkbookDeliveryCapability(selectedCapability)
    || readString(config.deliveryType, "") === "excel_workbook"
    || readString(config.documentKind, "") === "excel";
  const documentStyle = readDocumentDeliveryStyle(config.documentStyle, selectedCapability?.config);
  const rawFileNameTemplate = readString(config.fileNameTemplate, isExcelDelivery ? "交付表格-{{runNumber}}.xlsx" : "交付文档-{{runNumber}}.docx");
  const fileNameTemplate = isExcelDelivery ? normalizeExcelFileNameTemplate(rawFileNameTemplate) : normalizeWordFileNameTemplate(rawFileNameTemplate);
  const excelSheets = readExcelSheets(config.excelSheets, defaultExcelSheetTemplate);
  const effectiveTriggerRule = triggerRule ?? defaultDeliveryTriggerRule();
  const selectedClusterAgentValue = effectiveTriggerRule.clusterNodeId && effectiveTriggerRule.agentId
    ? `${effectiveTriggerRule.clusterNodeId}::${effectiveTriggerRule.agentId}`
    : "";

  function updateDocumentStyle<K extends keyof DocumentDeliveryStyleDraft>(key: K, value: DocumentDeliveryStyleDraft[K]) {
    onConfigChange({
      documentStyle: {
        ...documentStyle,
        [key]: value,
      },
    });
  }

  function updateDocumentStyles(updates: Partial<DocumentDeliveryStyleDraft>) {
    onConfigChange({
      documentStyle: {
        ...documentStyle,
        ...updates,
      },
    });
  }

  function updateTriggerRule(patch: Partial<DeliveryTriggerRuleDraft>) {
    onTriggerRuleChange?.({ ...effectiveTriggerRule, ...patch });
  }

  function handleTriggerTypeChange(value: string) {
    updateTriggerRule({
      ...defaultDeliveryTriggerRule(),
      type: readDeliveryTriggerType(value),
    });
  }

  useEffect(() => {
    if ((isWordDelivery || isExcelDelivery) && rawFileNameTemplate !== fileNameTemplate) {
      onConfigChange({ fileNameTemplate });
    }
  }, [isWordDelivery, isExcelDelivery, rawFileNameTemplate, fileNameTemplate]);

  return (
    <Drawer
      open={open}
      title={title}
      width={760}
      onClose={onClose}
      rootClassName={`${rootClassName} workflow-delivery-drawer`}
      styles={{
        body: {
          padding: 0,
          overflow: "hidden",
        },
      }}
      destroyOnClose
    >
      <div className="h-full max-h-[calc(100vh-56px)] overflow-y-auto px-6 py-5">
      <div className="space-y-5 pb-4">
        {onItemNameChange ? (
          <TextInputField
            label="交付项名称"
            icon={Tag}
            value={itemName ?? ""}
            placeholder="例如：合同审查报告"
            onChange={onItemNameChange}
          />
        ) : null}
        {typeof enabled === "boolean" && onEnabledChange ? (
          <label className="flex items-center justify-between rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-secondary)] px-4 py-3 text-sm">
            <span className="font-semibold text-[var(--color-text-primary)]">启用这个交付项</span>
            <input type="checkbox" checked={enabled} onChange={(event) => onEnabledChange(event.target.checked)} />
          </label>
        ) : null}
        {showDeliveryModeSelector ? (
          <SelectLikeField
            label="交付方式"
            icon={PackageCheck}
            value={isDirectDelivery ? "direct" : "capability"}
            options={[
              { value: "direct", label: "直接交付（节点内模板）" },
              { value: "capability", label: "能力交付（Word / Excel / 邮件等系统能力）" },
            ]}
            onChange={(value) => onDeliveryModeChange?.(value)}
          />
        ) : null}
        {showTrigger ? (
          <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-5 space-y-4">
            <SelectLikeField
              label="触发规则"
              icon={ListChecks}
              value={effectiveTriggerRule.type}
              options={DELIVERY_TRIGGER_OPTIONS}
              onChange={handleTriggerTypeChange}
            />
            {effectiveTriggerRule.type === "cluster_agent_matched" ? (
              <SelectLikeField
                label="智能体集群子智能体"
                icon={Bot}
                value={selectedClusterAgentValue}
                options={clusterAgentTriggerOptions.map((option) => ({
                  value: option.value,
                  label: option.label,
                }))}
                onChange={(value) => {
                  const option = clusterAgentTriggerOptions.find((item) => item.value === value);
                  if (!option) {
                    updateTriggerRule({ clusterNodeId: "", agentId: "", variableName: "" });
                    return;
                  }
                  updateTriggerRule({
                    clusterNodeId: option.clusterNodeId,
                    agentId: option.agentId,
                    variableName: option.variableName,
                  });
                }}
              />
            ) : null}
            {effectiveTriggerRule.type === "input_field_equals" ? (
              <>
                <SelectLikeField
                  label="输入节点字段"
                  icon={TextCursorInput}
                  value={effectiveTriggerRule.inputNodeId && effectiveTriggerRule.variableName
                    ? `${effectiveTriggerRule.inputNodeId}::${effectiveTriggerRule.variableName}`
                    : ""}
                  options={inputFieldTriggerOptions.map((option) => ({
                    value: option.value,
                    label: option.label,
                  }))}
                  onChange={(value) => {
                    const option = inputFieldTriggerOptions.find((item) => item.value === value);
                    if (!option) {
                      updateTriggerRule({ inputNodeId: "", variableName: "" });
                      return;
                    }
                    updateTriggerRule({
                      inputNodeId: option.inputNodeId,
                      variableName: option.variableName,
                    });
                  }}
                />
                <TextInputField
                  label="固定值"
                  icon={Hash}
                  value={effectiveTriggerRule.expectedValue}
                  placeholder="输入字段等于这个值时触发"
                  onChange={(value) => updateTriggerRule({ expectedValue: value })}
                />
              </>
            ) : null}
            {effectiveTriggerRule.type === "agent_output_exists" ? (
              <SelectLikeField
                label="单智能体输出"
                icon={Bot}
                value={effectiveTriggerRule.agentNodeId && effectiveTriggerRule.variableName
                  ? `${effectiveTriggerRule.agentNodeId}::${effectiveTriggerRule.variableName}`
                  : ""}
                options={agentOutputTriggerOptions.map((option) => ({
                  value: option.value,
                  label: option.label,
                }))}
                onChange={(value) => {
                  const option = agentOutputTriggerOptions.find((item) => item.value === value);
                  if (!option) {
                    updateTriggerRule({ agentNodeId: "", variableName: "" });
                    return;
                  }
                  updateTriggerRule({
                    agentNodeId: option.agentNodeId,
                    variableName: option.variableName,
                  });
                }}
              />
            ) : null}
            {effectiveTriggerRule.type === "cluster_agent_matched" && clusterAgentTriggerOptions.length === 0 ? (
              <p className="workflow-capability-state workflow-capability-state--warning">
                当前流程还没有可用于触发的智能体集群子智能体。请先在上游添加智能体集群，并配置子智能体输出。
              </p>
            ) : null}
            {effectiveTriggerRule.type === "input_field_equals" && inputFieldTriggerOptions.length === 0 ? (
              <p className="workflow-capability-state workflow-capability-state--warning">
                当前流程还没有可用于触发的输入节点字段。请先在上游添加输入节点，并配置输入字段。
              </p>
            ) : null}
            {effectiveTriggerRule.type === "agent_output_exists" && agentOutputTriggerOptions.length === 0 ? (
              <p className="workflow-capability-state workflow-capability-state--warning">
                当前流程还没有可用于触发的单智能体输出。请先在上游添加单智能体节点，并配置输出变量。
              </p>
            ) : null}
          </div>
        ) : null}
        {isDirectDelivery ? (
          <div className="space-y-4">
            <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-5">
              <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">直接交付</h4>
              <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                运行时将模板与上游变量拼接为最终交付内容，用户在交付节点核对后可复制，无需系统管理员分配交付能力。
              </p>
            </div>
            <PromptEditor
              label="交付内容模板"
              value={readString(config.deliveryContent, defaultDirectTemplate)}
              availableVariables={workflowVariables}
              onChange={(value) => onConfigChange({ deliveryContent: value })}
              placeholder="支持 Markdown，可用 {{输出内容标识}} 引用之前步骤内容"
            />
          </div>
        ) : (
          <div className="space-y-4">
            <CapabilitySelectField
              label="交付能力"
              icon={PackageCheck}
              value={selectedCapabilityId}
              options={deliveryAssets}
              placeholder="请选择交付能力"
              onChange={(value) => onCapabilityChange?.(value)}
            />
            {!selectedCapabilityId ? (
              <p className="workflow-capability-state workflow-capability-state--warning">
                当前主体还没有可用的交付能力。请先由系统管理员开放对应能力，再由租户管理员分配给当前用户、部门或角色。
              </p>
            ) : null}
            {isWordDelivery ? (
              <>
                <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-5">
                  <div className="mb-3">
                    <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">Word 文档交付</h4>
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)]">交付正文模板会作为最终 Markdown，运行时将模板和变量拼接后转换为 docx 文件。</p>
                  </div>
                  <VariableTemplateInputField
                    label="文件名模板"
                    icon={FileText}
                    value={fileNameTemplate}
                    variableItems={WORD_FILE_NAME_VARIABLES}
                    placeholder="交付文档-{{runNumber}}-{{dateCompact}}.docx"
                    onChange={(value) => onConfigChange({ fileNameTemplate: value })}
                  />
                </div>
                <DocumentDeliveryStyleSections
                  style={documentStyle}
                  onFieldChange={updateDocumentStyle}
                  onFieldsChange={updateDocumentStyles}
                />
                <PromptEditor
                  label="交付正文模板"
                  value={readString(config.markdownContent, defaultMarkdownTemplate)}
                  availableVariables={workflowVariables}
                  onChange={(value) => onConfigChange({ markdownContent: value })}
                  placeholder="最终转换为 Word 的 Markdown，可用 {{输出内容标识}} 引用之前步骤内容"
                />
              </>
            ) : isExcelDelivery ? (
              <>
                <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-5">
                  <div className="mb-3">
                    <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">Excel 工作簿交付</h4>
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)]">每个 Sheet 都会先渲染正文模板，再宽容识别 Markdown 表格、列表、键值块或普通文本并写入 xlsx。</p>
                  </div>
                  <VariableTemplateInputField
                    label="文件名模板"
                    icon={FileText}
                    value={fileNameTemplate}
                    variableItems={EXCEL_FILE_NAME_VARIABLES}
                    placeholder="交付表格-{{runNumber}}-{{dateCompact}}.xlsx"
                    onChange={(value) => onConfigChange({ fileNameTemplate: value })}
                  />
                </div>
                <ExcelDeliverySheetSections
                  sheets={excelSheets}
                  workflowVariables={workflowVariables}
                  onChange={(nextSheets) => onConfigChange({ excelSheets: nextSheets })}
                />
              </>
            ) : (
              <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/20 dark:text-amber-300">
                当前交付节点按文件交付设计，请选择系统内置 Word 文档或 Excel 工作簿交付能力。
              </div>
            )}
          </div>
        )}
      </div>
      </div>
    </Drawer>
  );
}

function ExcelDeliverySheetSections({
  sheets,
  workflowVariables,
  onChange,
}: {
  sheets: ExcelSheetDraft[];
  workflowVariables: WorkflowVariable[];
  onChange: (sheets: ExcelSheetDraft[]) => void;
}) {
  function updateSheet(sheetId: string, patch: Partial<ExcelSheetDraft>) {
    onChange(sheets.map((sheet) => sheet.id === sheetId ? { ...sheet, ...patch } : sheet));
  }

  function updateTableStyle(sheetId: string, patch: Partial<ExcelTableStyleDraft>) {
    onChange(sheets.map((sheet) => sheet.id === sheetId ? { ...sheet, tableStyle: { ...sheet.tableStyle, ...patch } } : sheet));
  }

  function addSheet() {
    onChange([...sheets, createExcelSheetDraft(sheets.length + 1)]);
  }

  function removeSheet(sheetId: string) {
    if (sheets.length <= 1) {
      return;
    }
    onChange(sheets.filter((sheet) => sheet.id !== sheetId));
  }

  function updateColumnRule(sheetId: string, ruleId: string, patch: Partial<ExcelColumnRuleDraft>) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, columnRules: sheet.columnRules.map((rule) => rule.id === ruleId ? { ...rule, ...patch } : rule) }
      : sheet));
  }

  function addColumnRule(sheetId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, columnRules: [...sheet.columnRules, createExcelColumnRuleDraft(sheet.columnRules.length + 1)] }
      : sheet));
  }

  function removeColumnRule(sheetId: string, ruleId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, columnRules: sheet.columnRules.filter((rule) => rule.id !== ruleId) }
      : sheet));
  }

  function updateRowRule(sheetId: string, ruleId: string, patch: Partial<ExcelRowRuleDraft>) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, rowRules: sheet.rowRules.map((rule) => rule.id === ruleId ? { ...rule, ...patch } : rule) }
      : sheet));
  }

  function addRowRule(sheetId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, rowRules: [...sheet.rowRules, createExcelRowRuleDraft(sheet.rowRules.length + 1)] }
      : sheet));
  }

  function removeRowRule(sheetId: string, ruleId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, rowRules: sheet.rowRules.filter((rule) => rule.id !== ruleId) }
      : sheet));
  }

  function updateCellRule(sheetId: string, ruleId: string, patch: Partial<ExcelCellRuleDraft>) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, cellRules: sheet.cellRules.map((rule) => rule.id === ruleId ? { ...rule, ...patch } : rule) }
      : sheet));
  }

  function addCellRule(sheetId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, cellRules: [...sheet.cellRules, createExcelCellRuleDraft(sheet.cellRules.length + 1)] }
      : sheet));
  }

  function removeCellRule(sheetId: string, ruleId: string) {
    onChange(sheets.map((sheet) => sheet.id === sheetId
      ? { ...sheet, cellRules: sheet.cellRules.filter((rule) => rule.id !== ruleId) }
      : sheet));
  }

  return (
    <div className="space-y-4">
      {sheets.map((sheet, index) => (
        <div key={sheet.id} className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-hover)] p-5">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">Sheet {index + 1}</h4>
              <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{sheet.name}</p>
            </div>
            <button type="button" className="agent-icon-button h-8 w-8" title="删除 Sheet" disabled={sheets.length <= 1} onClick={() => removeSheet(sheet.id)}>
              <Trash2 size={14} />
            </button>
          </div>
          <div className="grid gap-4 md:grid-cols-3">
            <TextInputField label="Sheet 名称" icon={FileText} value={sheet.name} placeholder="风险明细" onChange={(value) => updateSheet(sheet.id, { name: value })} />
            <TextInputField label="起始单元格" icon={Hash} value={sheet.startCell} placeholder="A1" maxLength={12} onChange={(value) => updateSheet(sheet.id, { startCell: value })} />
            <SelectLikeField
              label="默认单元格类型"
              icon={Type}
              value={sheet.defaultCellType}
              options={EXCEL_CELL_TYPE_OPTIONS}
              onChange={(value) => updateSheet(sheet.id, { defaultCellType: readExcelCellType(value) })}
            />
          </div>
          <div className="mt-4 grid gap-3 md:grid-cols-3">
            <SelectLikeField
              label="字体"
              icon={Type}
              value={sheet.tableStyle.fontName}
              options={EXCEL_FONT_OPTIONS}
              onChange={(value) => updateTableStyle(sheet.id, { fontName: value })}
            />
            <SelectLikeField
              label="字号"
              icon={Hash}
              value={String(sheet.tableStyle.fontSize || 0)}
              options={EXCEL_FONT_SIZE_OPTIONS}
              onChange={(value) => updateTableStyle(sheet.id, { fontSize: Number(value) })}
            />
            <SelectLikeField
              label="对齐方式"
              icon={AlignCenter}
              value={sheet.tableStyle.horizontalAlignment}
              options={EXCEL_ALIGNMENT_OPTIONS}
              onChange={(value) => updateTableStyle(sheet.id, { horizontalAlignment: value })}
            />
          </div>
          <div className="mt-4 grid gap-3 md:grid-cols-3">
            <ExcelToggleField label="表头加粗" icon={Bold} checked={sheet.tableStyle.headerBold} onChange={(checked) => updateTableStyle(sheet.id, { headerBold: checked })} />
            <ExcelToggleField label="冻结表头" icon={Pin} checked={sheet.tableStyle.freezeHeader} onChange={(checked) => updateTableStyle(sheet.id, { freezeHeader: checked })} />
            <ExcelToggleField label="自动筛选" icon={Filter} checked={sheet.tableStyle.autoFilter} onChange={(checked) => updateTableStyle(sheet.id, { autoFilter: checked })} />
          </div>
          <div className="mt-4">
            <PromptEditor
              label="Sheet 正文模板"
              value={sheet.bodyTemplate}
              availableVariables={workflowVariables}
              onChange={(value) => updateSheet(sheet.id, { bodyTemplate: value })}
              placeholder="可直接写 Markdown 表格、编号列表、键值块，也可用 {{输出内容标识}} 引用模型输出"
              textareaClassName="min-h-[180px]"
            />
          </div>
          <ExcelRuleSection title="列格式规则" onAdd={() => addColumnRule(sheet.id)}>
            {sheet.columnRules.map((rule) => (
              <ExcelRuleCard key={rule.id} removeTitle="删除列规则" onRemove={() => removeColumnRule(sheet.id, rule.id)}>
                <SelectLikeField
                  label="目标列"
                  icon={Columns3}
                  value={rule.target}
                  options={EXCEL_COLUMN_TARGET_OPTIONS}
                  onChange={(value) => updateColumnRule(sheet.id, rule.id, { target: readExcelColumnTarget(value) })}
                />
                {rule.target === "letter" ? (
                  <TextInputField
                    label="列字母"
                    icon={Hash}
                    value={rule.letter}
                    placeholder="A / B / C"
                    maxLength={4}
                    onChange={(value) => updateColumnRule(sheet.id, rule.id, { letter: value.toUpperCase().replace(/[^A-Z]/g, "") })}
                  />
                ) : (
                  <TextInputField
                    label="列名称"
                    icon={Tag}
                    value={rule.name}
                    placeholder="合同金额"
                    onChange={(value) => updateColumnRule(sheet.id, rule.id, { name: value })}
                  />
                )}
                <NumberInputField
                  label="应用起始行"
                  value={rule.applyFromRow}
                  min={1}
                  max={9999}
                  step={1}
                  onChange={(value) => updateColumnRule(sheet.id, rule.id, { applyFromRow: value })}
                />
                <SelectLikeField label="类型" icon={Type} value={rule.type} options={EXCEL_CELL_TYPE_OPTIONS} onChange={(value) => updateColumnRule(sheet.id, rule.id, { type: readExcelCellType(value) })} />
                <TextInputField label="格式" icon={Hash} value={rule.format} placeholder="#,##0.00" onChange={(value) => updateColumnRule(sheet.id, rule.id, { format: value })} />
                <NumberInputField label="列宽" value={rule.width} min={0} max={80} step={1} onChange={(value) => updateColumnRule(sheet.id, rule.id, { width: value })} />
                <SelectLikeField label="对齐" icon={AlignCenter} value={rule.horizontalAlignment} options={EXCEL_ALIGNMENT_OPTIONS} onChange={(value) => updateColumnRule(sheet.id, rule.id, { horizontalAlignment: value })} />
                <ExcelColorSelectField label="底色" value={rule.backgroundColor} onChange={(value) => updateColumnRule(sheet.id, rule.id, { backgroundColor: value })} />
                <ExcelToggleField label="加粗" icon={Bold} checked={rule.bold} onChange={(checked) => updateColumnRule(sheet.id, rule.id, { bold: checked })} />
              </ExcelRuleCard>
            ))}
          </ExcelRuleSection>
          <ExcelRuleSection title="行格式规则" onAdd={() => addRowRule(sheet.id)}>
            {sheet.rowRules.map((rule) => (
              <ExcelRuleCard key={rule.id} removeTitle="删除行规则" onRemove={() => removeRowRule(sheet.id, rule.id)}>
                <SelectLikeField label="目标行" icon={Rows3} value={rule.target} options={EXCEL_ROW_TARGET_OPTIONS} onChange={(value) => updateRowRule(sheet.id, rule.id, { target: readExcelRowTarget(value) })} />
                {rule.target === "index" ? (
                  <NumberInputField label="指定行号" value={rule.index} min={1} max={9999} step={1} onChange={(value) => updateRowRule(sheet.id, rule.id, { index: value })} />
                ) : null}
                {rule.target === "contains" ? (
                  <TextInputField label="关键字" icon={Search} value={rule.text} placeholder="合计 / 小计" onChange={(value) => updateRowRule(sheet.id, rule.id, { text: value })} />
                ) : null}
                <ExcelColorSelectField label="底色" value={rule.backgroundColor} onChange={(value) => updateRowRule(sheet.id, rule.id, { backgroundColor: value })} />
                <ExcelToggleField label="加粗" icon={Bold} checked={rule.bold} onChange={(checked) => updateRowRule(sheet.id, rule.id, { bold: checked })} />
              </ExcelRuleCard>
            ))}
          </ExcelRuleSection>
          <ExcelRuleSection title="单元格格式规则" onAdd={() => addCellRule(sheet.id)}>
            {sheet.cellRules.map((rule) => (
              <ExcelRuleCard key={rule.id} removeTitle="删除单元格规则" onRemove={() => removeCellRule(sheet.id, rule.id)}>
                <TextInputField label="单元格" icon={Hash} value={rule.cell} placeholder="A1" maxLength={12} onChange={(value) => updateCellRule(sheet.id, rule.id, { cell: value.toUpperCase() })} />
                <SelectLikeField label="类型" icon={Type} value={rule.type} options={EXCEL_CELL_TYPE_OPTIONS} onChange={(value) => updateCellRule(sheet.id, rule.id, { type: readExcelCellType(value) })} />
                <TextInputField label="格式" icon={Hash} value={rule.format} placeholder="yyyy-mm-dd" onChange={(value) => updateCellRule(sheet.id, rule.id, { format: value })} />
                <ExcelColorSelectField label="底色" value={rule.backgroundColor} onChange={(value) => updateCellRule(sheet.id, rule.id, { backgroundColor: value })} />
                <SelectLikeField label="对齐" icon={AlignCenter} value={rule.horizontalAlignment} options={EXCEL_ALIGNMENT_OPTIONS} onChange={(value) => updateCellRule(sheet.id, rule.id, { horizontalAlignment: value })} />
                <ExcelToggleField label="加粗" icon={Bold} checked={rule.bold} onChange={(checked) => updateCellRule(sheet.id, rule.id, { bold: checked })} />
                <ExcelToggleField label="允许公式" icon={Sigma} checked={rule.allowFormula} onChange={(checked) => updateCellRule(sheet.id, rule.id, { allowFormula: checked })} />
              </ExcelRuleCard>
            ))}
          </ExcelRuleSection>
        </div>
      ))}
      <button type="button" className="agent-button h-9 px-3 text-xs" onClick={addSheet}>
        <Plus size={14} />
        添加 Sheet
      </button>
    </div>
  );
}

function ExcelRuleSection({ title, children, onAdd }: { title: string; children: ReactNode; onAdd: () => void }) {
  return (
    <div className="mt-4 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h5 className="text-xs font-semibold text-[var(--color-text-secondary)]">{title}</h5>
        <button type="button" className="agent-button h-8 px-3 text-xs" onClick={onAdd}>
          <Plus size={13} />
          添加规则
        </button>
      </div>
      {children}
    </div>
  );
}

function ExcelRuleCard({
  children,
  removeTitle,
  onRemove,
}: {
  children: ReactNode;
  removeTitle: string;
  onRemove: () => void;
}) {
  return (
    <div className="relative rounded-lg border border-[var(--color-border-light)] bg-[var(--color-bg-secondary)] p-3 pr-12">
      <button
        type="button"
        className="agent-icon-button absolute right-3 top-3 h-8 w-8"
        title={removeTitle}
        onClick={onRemove}
      >
        <Trash2 size={14} />
      </button>
      <div className="grid gap-3 md:grid-cols-4">{children}</div>
    </div>
  );
}

function ExcelToggleField({
  label,
  icon: Icon,
  checked,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className="sys-field mb-0">
      <span className="sys-field-label">{label}</span>
      <div className={`sys-field-input-wrap excel-rule-toggle-wrap ${Icon ? "" : "excel-rule-toggle-wrap--no-icon"}`}>
        {Icon ? <Icon size={16} className="sys-field-prefix" aria-hidden="true" /> : null}
        <button
          type="button"
          role="switch"
          aria-checked={checked}
          aria-label={`${label}：${checked ? "开启" : "关闭"}`}
          className={`excel-rule-toggle ${checked ? "is-active" : ""}`}
          onClick={() => onChange(!checked)}
        >
          <span className="excel-rule-toggle-track" aria-hidden="true">
            <span className="excel-rule-toggle-thumb" />
          </span>
        </button>
      </div>
    </div>
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

function ExcelColorSwatch({ color, empty = false }: { color?: string; empty?: boolean }) {
  return (
    <span
      className={`excel-color-swatch${empty || !color ? " excel-color-swatch--empty" : ""}`}
      style={color ? { backgroundColor: color } : undefined}
      aria-hidden="true"
    />
  );
}

function ExcelColorSelectField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const normalizedOptions = EXCEL_COLOR_OPTIONS;
  const effectiveOptions = value && !normalizedOptions.some((option) => option.value === value)
    ? [{ value, label: value }, ...normalizedOptions]
    : normalizedOptions;

  const renderColorOption = (optionValue: string, optionLabel: string) => {
    const option = effectiveOptions.find((item) => item.value === optionValue);
    return (
      <span className="flex min-w-0 items-center gap-2">
        <ExcelColorSwatch color={option?.color} empty={!option?.color} />
        <span className="truncate">{optionLabel}</span>
      </span>
    );
  };

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      <Select
        className="agent-admin-select w-full"
        classNames={workflowSelectClassNames}
        prefix={<Palette className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
        suffixIcon={workflowSelectSuffixIcon}
        showSearch={false}
        value={value}
        options={effectiveOptions.map((option) => ({ value: option.value, label: option.label }))}
        optionRender={(option) => renderColorOption(String(option.value ?? ""), String(option.label ?? ""))}
        onChange={onChange}
      />
    </label>
  );
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
  textareaClassName = "",
}: {
  label: string;
  value: string;
  availableVariables: WorkflowVariable[];
  onChange: (value: string) => void;
  placeholder?: string;
  showVariableBar?: boolean;
  textareaClassName?: string;
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleVariablePick = useCallback((variable: string) => {
    insertTemplateToken(textareaRef.current, value, formatTemplateVariable(variable), onChange);
  }, [onChange, value]);

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      {showVariableBar ? (
        <VariableReferenceBar
          variables={availableVariables}
          onPick={handleVariablePick}
        />
      ) : null}
      <textarea
        ref={textareaRef}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className={`sys-field-textarea workflow-prompt-textarea ${textareaClassName}`.trim()}
        placeholder={placeholder ?? "可以使用 {{输出内容标识}} 引用之前步骤内容"}
      />
    </label>
  );
}

function VariableTemplateInputField({
  label,
  icon: Icon,
  value,
  placeholder,
  maxLength = 160,
  variableItems,
  onChange,
}: {
  label: string;
  icon?: WorkflowIcon;
  value: string;
  placeholder?: string;
  maxLength?: number;
  variableItems: VariableReferenceItem[];
  onChange: (value: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  const handleVariablePick = useCallback((variable: string) => {
    insertTemplateToken(inputRef.current, value, formatTemplateVariable(variable), onChange);
  }, [onChange, value]);

  return (
    <label className="sys-field">
      <span className="sys-field-label">{label}</span>
      {variableItems.length > 0 ? (
        <FileNameVariableBar items={variableItems} onPick={handleVariablePick} />
      ) : null}
      <div className="sys-field-input-wrap">
        {Icon ? <Icon size={16} className="sys-field-prefix" aria-hidden="true" /> : null}
        <input
          ref={inputRef}
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

function VariableReferenceBar({
  variables,
  onPick,
}: {
  variables: WorkflowVariable[];
  onPick: (variable: string) => void;
}) {
  const systemItems = variables.filter(isSystemRuntimeVariable).map(variableToReferenceItem);
  const nodeItems = variables.filter((variable) => !isSystemRuntimeVariable(variable)).map(variableToReferenceItem);

  if (systemItems.length === 0 && nodeItems.length === 0) {
    return null;
  }

  return (
    <div className="workflow-variable-reference-section">
      <span className="workflow-variable-reference-title">可插入变量</span>
      <VariableReferenceGroup label="系统变量" items={systemItems} onPick={onPick} />
      <VariableReferenceGroup label="节点变量" items={nodeItems} onPick={onPick} />
    </div>
  );
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
          zIndex={2800}
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

function VariableReferenceGroup({
  label,
  items,
  onPick,
}: {
  label: string;
  items: VariableReferenceItem[];
  onPick: (variable: string) => void;
}) {
  if (items.length === 0) {
    return null;
  }

  return (
    <div className="workflow-variable-reference-group">
      <span className="workflow-variable-reference-group-label">{label}</span>
      <VariableReferenceItemBar items={items} onPick={onPick} />
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
        textareaClassName="workflow-prompt-textarea--drawer-single"
      />
    </div>
  );
}

function InputFieldOptionsEditor({
  options,
  onChange,
}: {
  options: Array<{ label: string; value: string }>;
  onChange: (options: Array<{ label: string; value: string }>) => void;
}) {
  function updateOption(index: number, patch: Partial<{ label: string; value: string }>) {
    onChange(options.map((option, optionIndex) => {
      if (optionIndex !== index) {
        return option;
      }
      if (patch.label !== undefined && patch.value === undefined && shouldSyncInputFieldOptionValue(option, index)) {
        return { ...option, label: patch.label, value: patch.label };
      }
      return { ...option, ...patch };
    }));
  }

  return (
    <div className="sys-field">
      <span className="sys-field-label sys-field-label--required">下拉选项</span>
      <div className="workflow-input-field-options">
        <div className="workflow-input-field-option-row workflow-input-field-option-row--header" aria-hidden="true">
          <span>业务人员看到</span>
          <span>提交后写入变量</span>
          <span />
        </div>
        {options.map((option, index) => (
          <div key={`${index}-${option.value}`} className="workflow-input-field-option-row">
            <div className="sys-field-input-wrap">
              <Tag size={16} className="sys-field-prefix" aria-hidden="true" />
              <input
                className="sys-field-input"
                value={option.label}
                placeholder="显示文本，例如 2026"
                onChange={(event) => updateOption(index, { label: event.target.value })}
              />
            </div>
            <div className="sys-field-input-wrap">
              <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
              <input
                className="sys-field-input"
                value={option.value}
                placeholder="提交值，例如 2026"
                onChange={(event) => updateOption(index, { value: event.target.value })}
              />
            </div>
            <IconButton
              label="删除选项"
              icon={Trash2}
              tone="danger"
              onClick={() => onChange(options.filter((_, optionIndex) => optionIndex !== index))}
            />
          </div>
        ))}
        <button
          type="button"
          className="agent-button h-8 px-3 text-xs"
          onClick={() => onChange([...options, createInputFieldOption(options.length)])}
        >
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          新增选项
        </button>
      </div>
      <p className="sys-field-hint">占位提示只用于未选择时展示；业务人员提交后，输出变量写入右侧「提交值」。</p>
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
  const { message } = App.useApp();
  const [draft, setDraft] = useState<InputFieldConfig>(() => normalizeInputField(field));
  const isSelectField = draft.fieldType === "select";

  function handleFieldTypeChange(fieldType: WorkflowInputFieldType) {
    if (fieldType === "select") {
      setDraft((current) => ({
        ...current,
        fieldType,
        placeholder: current.placeholder || "请选择",
        options: current.options?.length ? current.options : [createInputFieldOption(0)],
      }));
      return;
    }

    setDraft((current) => ({
      ...current,
      fieldType,
      placeholder: current.placeholder || "请输入内容",
      options: undefined,
    }));
  }

  function commitSelectOptions(options: Array<{ label: string; value: string }>) {
    const normalizedOptions = normalizeInputFieldOptions(options, draft.placeholder);
    setDraft((current) => ({
      ...current,
      options,
      defaultValue: current.defaultValue && normalizedOptions.some((option) => option.value === current.defaultValue)
        ? current.defaultValue
        : "",
    }));
  }

  function handleSave() {
    const normalizedOptions = normalizeInputFieldOptions(draft.options, draft.placeholder);
    const nextField = normalizeInputField({
      ...draft,
      variable: normalizeVariableName(draft.variable) || "input_value",
      options: isSelectField ? normalizedOptions : undefined,
      defaultValue: draft.defaultValue && normalizedOptions.some((option) => option.value === draft.defaultValue)
        ? draft.defaultValue
        : "",
    });
    const validationError = validateInputFieldDraft(nextField);
    if (validationError) {
      message.warning(validationError);
      return;
    }
    onSave(nextField);
  }

  return (
    <SysModalMask onClose={onClose}>
      <section className="sys-modal workflow-config-modal" aria-labelledby="input-field-modal-title">
        <div className="sys-modal-header">
          <div>
            <div className="sys-field-label" style={{ marginBottom: 4 }}>输入信息</div>
            <span id="input-field-modal-title" className="sys-modal-title">
              {isSelectField ? "配置下拉框" : "配置文本框"}
            </span>
          </div>
          <button className="sys-modal-close" onClick={onClose} aria-label="关闭输入字段配置"><X size={18} /></button>
        </div>
        <div className="sys-modal-body">
          <label className="sys-field">
            <span className="sys-field-label sys-field-label--required">字段类型</span>
            <Select
              className="agent-admin-select w-full"
              classNames={workflowSelectClassNames}
              suffixIcon={workflowSelectSuffixIcon}
              value={draft.fieldType ?? "text"}
              options={WORKFLOW_INPUT_FIELD_TYPE_OPTIONS}
              onChange={(value) => handleFieldTypeChange(value as WorkflowInputFieldType)}
            />
          </label>
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
            <span className="sys-field-label">{isSelectField ? "占位提示" : "占位提示"}</span>
            <div className="sys-field-input-wrap">
              {isSelectField
                ? <ChevronDown size={16} className="sys-field-prefix" aria-hidden="true" />
                : <TextCursorInput size={16} className="sys-field-prefix" aria-hidden="true" />}
              <input value={draft.placeholder} onChange={(event) => setDraft({ ...draft, placeholder: event.target.value })} className="sys-field-input" />
            </div>
          </label>
          {isSelectField ? (
            <>
              <InputFieldOptionsEditor
                options={draft.options ?? []}
                onChange={commitSelectOptions}
              />
              <label className="sys-field">
                <span className="sys-field-label">默认选中</span>
                <Select
                  allowClear
                  className="agent-admin-select w-full"
                  classNames={workflowSelectClassNames}
                  suffixIcon={workflowSelectSuffixIcon}
                  placeholder="不预设默认选项"
                  value={draft.defaultValue || undefined}
                  options={normalizeInputFieldOptions(draft.options, draft.placeholder).map((option) => ({ value: option.value, label: option.label }))}
                  onChange={(value) => setDraft({ ...draft, defaultValue: value ?? "" })}
                />
              </label>
            </>
          ) : (
            <PromptEditor
              label="默认内容"
              value={draft.defaultValue ?? ""}
              availableVariables={availableVariables}
              onChange={(value) => setDraft({ ...draft, defaultValue: value })}
              placeholder="可用 {{输出内容标识}} 引用之前步骤内容"
            />
          )}
          <label className="workflow-toggle-row workflow-input-required-toggle">
            <span>
              <span className="block">是否必填</span>
              <span className="mt-1 block text-xs font-normal text-[var(--color-text-tertiary)]">开启后，业务人员必须填写此项才能提交。</span>
            </span>
            <input
              type="checkbox"
              checked={draft.required !== false}
              onChange={(event) => setDraft({ ...draft, required: event.target.checked })}
            />
          </label>
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>取消</button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={handleSave}>保存</button>
        </div>
      </section>
    </SysModalMask>
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
  maxAgentIterationsPerTurn: number;
  allowUserEdit: boolean;
  allowQuestion: boolean;
  modelProviderId: string;
  modelName: string;
  enableThinking: boolean;
};

function buildSingleAgentConfigDraft(node: WorkflowEditorNode, agentRuntimeLimits: AgentRuntimeLimits, modelOptions: WorkflowModelOption[]): SingleAgentConfigDraft {
  const config = node.data.rawConfig ?? {};
  const configuredProviderId = readString(config.modelProviderId, "");
  const selectedModel = modelOptions.find((model) => model.providerId === configuredProviderId) ?? modelOptions[0];
  return {
    agentAssetId: readString(config.agentAssetId, "custom"),
    systemPromptTemplateId: readString(config.systemPromptTemplateId, readString(config.promptTemplateId, "none")),
    userPromptTemplateId: readString(config.userPromptTemplateId, "none"),
    systemPrompt: readString(config.systemPrompt, ""),
    userPrompt: readString(config.userPrompt, ""),
    mcpIds: readStringArray(config.mcpIds ?? config.mcpServices, []),
    skillIds: readStringArray(config.skillIds ?? config.skills, []),
    maxTokens: readOptionalInt(config.maxTokens),
    maxAgentIterationsPerTurn: readAgentIterationsPerTurn(config.maxAgentIterationsPerTurn, agentRuntimeLimits),
    allowUserEdit: node.data.allowUserEdit,
    allowQuestion: node.data.allowQuestion,
    modelProviderId: selectedModel?.providerId ?? "",
    modelName: readString(config.modelName, selectedModel?.modelName ?? ""),
    enableThinking: selectedModel?.reasoningModel ? readBoolean(config.enableThinking, false) : false,
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
    maxAgentIterationsPerTurn: draft.maxAgentIterationsPerTurn,
    modelProviderId: draft.modelProviderId,
    modelName: draft.modelName,
    enableThinking: draft.enableThinking,
  };
}

function buildSingleAgentNodePatch(draft: SingleAgentConfigDraft): Partial<EditorNodeData> {
  return {
    toolCount: draft.mcpIds.length + draft.skillIds.length,
    allowUserEdit: draft.allowUserEdit,
    allowQuestion: draft.allowQuestion,
  };
}

const WORKFLOW_AGENT_DRAWER_WIDTH = 1000;

type AgentConfigSectionId = "basic" | "systemPrompt" | "userPrompt" | "capabilities" | "runtime";

type AgentConfigSectionDef = {
  id: AgentConfigSectionId;
  title: string;
  icon: WorkflowIcon;
};

const AGENT_CONFIG_SECTIONS: AgentConfigSectionDef[] = [
  { id: "basic", title: "基础配置", icon: Bot },
  { id: "systemPrompt", title: "系统提示词", icon: FileText },
  { id: "userPrompt", title: "用户提示词", icon: TextCursorInput },
  { id: "capabilities", title: "能力与工具", icon: ServerCog },
  { id: "runtime", title: "运行策略", icon: Settings2 },
];

function SingleAgentConfigModal({
  node,
  availableVariables,
  agentAssets,
  promptAssets,
  mcpAssets,
  skillAssets,
  agentRuntimeLimits,
  modelOptions,
  onClose,
  onConfigChange,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  agentAssets: WorkflowCapabilityOption[];
  promptAssets: WorkflowCapabilityOption[];
  mcpAssets: WorkflowCapabilityOption[];
  skillAssets: WorkflowCapabilityOption[];
  agentRuntimeLimits: AgentRuntimeLimits;
  modelOptions: WorkflowModelOption[];
  onClose: () => void;
  onConfigChange: (config: Record<string, unknown>, patch: Partial<EditorNodeData>) => void;
}) {
  const { message } = App.useApp();
  const themeMode = useAuthStore((state) => state.themeMode);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode, "workflow-agent-drawer");
  const initialDraftRef = useRef(buildSingleAgentConfigDraft(node, agentRuntimeLimits, modelOptions));
  const [draft, setDraftState] = useState<SingleAgentConfigDraft>(initialDraftRef.current);
  const [activeSection, setActiveSection] = useState<AgentConfigSectionId>("basic");
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
    <Drawer
      title="配置智能体"
      placement="right"
      width={WORKFLOW_AGENT_DRAWER_WIDTH}
      open
      destroyOnClose
      onClose={handleCancel}
      rootClassName={drawerRootClassName}
    >
      <div className="sys-drawer-section sys-drawer-section-enter workflow-agent-drawer-shell">
        <AgentConfigSplitPanel
          sections={AGENT_CONFIG_SECTIONS}
          activeSection={activeSection}
          onSectionChange={setActiveSection}
        >
          {activeSection === "basic" ? (
            <div className="workflow-modal-section grid gap-4 md:grid-cols-2">
              <CapabilitySelectField
                label="智能体模板"
                icon={Bot}
                value={draft.agentAssetId}
                emptyValue="custom"
                emptyLabel="自定义智能体"
                options={agentAssets}
                onChange={(value) => setDraft({ ...draft, agentAssetId: value })}
              />
              <ModelSelectField
                modelOptions={modelOptions}
                modelProviderId={draft.modelProviderId}
                enableThinking={draft.enableThinking}
                onChange={(patch) => setDraft({ ...draft, ...patch })}
              />
            </div>
          ) : null}

          {activeSection === "systemPrompt" ? (
            <div className="workflow-agent-drawer-prompt-single">
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
            </div>
          ) : null}

          {activeSection === "userPrompt" ? (
            <div className="workflow-agent-drawer-prompt-single">
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
            </div>
          ) : null}

          {activeSection === "capabilities" ? (
            <div className="workflow-modal-section grid gap-4 md:grid-cols-2">
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
          ) : null}

          {activeSection === "runtime" ? (
            <div className="workflow-agent-runtime-panel">
              <AgentRuntimeLimitsRow
                maxTokens={draft.maxTokens}
                maxAgentIterationsPerTurn={draft.maxAgentIterationsPerTurn}
                maximumIterations={agentRuntimeLimits.maxIterationsPerTurn}
                onMaxTokensChange={(value) => setDraft({ ...draft, maxTokens: value })}
                onMaxIterationsChange={(value) => setDraft({ ...draft, maxAgentIterationsPerTurn: value })}
              />
              <AgentBehaviorCapsules
                allowUserEdit={draft.allowUserEdit}
                allowQuestion={draft.allowQuestion}
                enableThinking={draft.enableThinking}
                showThinking={(modelOptions.find((model) => model.providerId === draft.modelProviderId) ?? modelOptions[0])?.reasoningModel}
                onChange={(patch) => setDraft({ ...draft, ...patch })}
              />
            </div>
          ) : null}
        </AgentConfigSplitPanel>
      </div>
      <div className="sys-drawer-footer">
        <div className="sys-drawer-footer-right">
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
      </div>
    </Drawer>
  );
}

function ClusterAgentModal({
  agent,
  availableVariables,
  agentAssets,
  promptAssets,
  mcpAssets,
  skillAssets,
  agentRuntimeLimits,
  modelOptions,
  onClose,
  onSave,
}: {
  agent: ClusterAgentConfig;
  availableVariables: WorkflowVariable[];
  agentAssets: WorkflowCapabilityOption[];
  promptAssets: WorkflowCapabilityOption[];
  mcpAssets: WorkflowCapabilityOption[];
  skillAssets: WorkflowCapabilityOption[];
  agentRuntimeLimits: AgentRuntimeLimits;
  modelOptions: WorkflowModelOption[];
  onClose: () => void;
  onSave: (agent: ClusterAgentConfig) => void;
}) {
  const { message } = App.useApp();
  const themeMode = useAuthStore((state) => state.themeMode);
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode, "workflow-agent-drawer");
  const initialModel = modelOptions.find((model) => model.providerId === agent.modelProviderId) ?? modelOptions[0];
  const [draft, setDraft] = useState<ClusterAgentConfig>({
    ...agent,
    modelProviderId: initialModel?.providerId ?? "",
    modelName: initialModel?.modelName ?? "",
    enableThinking: initialModel?.reasoningModel ? agent.enableThinking : false,
  });
  const [activeSection, setActiveSection] = useState<AgentConfigSectionId>("basic");

  return (
    <Drawer
      title="配置集群智能体"
      placement="right"
      width={WORKFLOW_AGENT_DRAWER_WIDTH}
      open
      destroyOnClose
      onClose={onClose}
      rootClassName={drawerRootClassName}
    >
      <div className="sys-drawer-section sys-drawer-section-enter workflow-agent-drawer-shell">
        <AgentConfigSplitPanel
          sections={AGENT_CONFIG_SECTIONS}
          activeSection={activeSection}
          onSectionChange={setActiveSection}
        >
          {activeSection === "basic" ? (
            <div className="workflow-modal-section grid gap-4 md:grid-cols-2">
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
              <ModelSelectField
                modelOptions={modelOptions}
                modelProviderId={draft.modelProviderId}
                enableThinking={draft.enableThinking}
                onChange={(patch) => setDraft({ ...draft, ...patch })}
              />
            </div>
          ) : null}

          {activeSection === "systemPrompt" ? (
            <div className="workflow-agent-drawer-prompt-single">
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
            </div>
          ) : null}

          {activeSection === "userPrompt" ? (
            <div className="workflow-agent-drawer-prompt-single">
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
            </div>
          ) : null}

          {activeSection === "capabilities" ? (
            <div className="workflow-modal-section grid gap-4 md:grid-cols-2">
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
          ) : null}

          {activeSection === "runtime" ? (
            <div className="workflow-agent-runtime-panel">
              <AgentRuntimeLimitsRow
                maxTokens={draft.maxTokens}
                maxAgentIterationsPerTurn={draft.maxAgentIterationsPerTurn}
                maximumIterations={agentRuntimeLimits.maxIterationsPerTurn}
                onMaxTokensChange={(value) => setDraft({ ...draft, maxTokens: value })}
                onMaxIterationsChange={(value) => setDraft({ ...draft, maxAgentIterationsPerTurn: value })}
              />
              <AgentBehaviorCapsules
                allowUserEdit={draft.allowUserEdit}
                allowQuestion={draft.allowQuestion}
                enableThinking={draft.enableThinking}
                showThinking={(modelOptions.find((model) => model.providerId === draft.modelProviderId) ?? modelOptions[0])?.reasoningModel}
                onChange={(patch) => setDraft({ ...draft, ...patch })}
              />
            </div>
          ) : null}
        </AgentConfigSplitPanel>
      </div>
      <div className="sys-drawer-footer">
        <div className="sys-drawer-footer-right">
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
                intentCode: normalizeIntentCode(draft.intentCode),
                intentName: draft.intentName.trim(),
                intentDescription: draft.intentDescription.trim(),
                systemPrompt: draft.systemPrompt.trim(),
                userPrompt: draft.userPrompt.trim(),
              });
            }}
          >
            保存
          </button>
        </div>
      </div>
    </Drawer>
  );
}

function AgentConfigSplitPanel({
  sections,
  activeSection,
  onSectionChange,
  children,
}: {
  sections: AgentConfigSectionDef[];
  activeSection: AgentConfigSectionId;
  onSectionChange: (section: AgentConfigSectionId) => void;
  children: ReactNode;
}) {
  const active = sections.find((section) => section.id === activeSection) ?? sections[0];

  return (
    <div className="workflow-agent-config-split">
      <aside className="workflow-agent-config-sidebar">
        <p className="workflow-agent-config-sidebar-title">配置分类</p>
        <nav className="workflow-agent-config-nav" aria-label="智能体配置分类">
          {sections.map((section) => {
            const Icon = section.icon;
            const isActive = section.id === activeSection;
            return (
              <button
                key={section.id}
                type="button"
                className={`workflow-agent-config-nav-item ${isActive ? "is-active" : ""}`}
                aria-current={isActive ? "page" : undefined}
                onClick={() => onSectionChange(section.id)}
              >
                <span className="workflow-agent-config-nav-icon" aria-hidden="true">
                  <Icon size={15} />
                </span>
                <span className="workflow-agent-config-nav-label">{section.title}</span>
              </button>
            );
          })}
        </nav>
      </aside>
      <div className="workflow-agent-config-content">
        <header className="workflow-agent-config-content-header">
          <h3 className="workflow-agent-config-content-title">{active.title}</h3>
        </header>
        <div className="workflow-agent-config-content-body">
          {children}
        </div>
      </div>
    </div>
  );
}

function AgentConfigCapsuleToggle({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      className={`workflow-agent-capsule-toggle ${checked ? "is-active" : ""}`}
      onClick={() => onChange(!checked)}
    >
      {label}
    </button>
  );
}

function AgentBehaviorCapsules({
  allowUserEdit,
  allowQuestion,
  enableThinking,
  showThinking,
  showAllowUserEdit = true,
  showAllowQuestion = true,
  onChange,
}: {
  allowUserEdit: boolean;
  allowQuestion: boolean;
  enableThinking?: boolean;
  showThinking?: boolean;
  showAllowUserEdit?: boolean;
  showAllowQuestion?: boolean;
  onChange: (patch: Partial<{ allowUserEdit: boolean; allowQuestion: boolean; enableThinking: boolean }>) => void;
}) {
  return (
    <div className="workflow-agent-capsule-row">
      {showThinking ? (
        <AgentConfigCapsuleToggle
          label="深度推理"
          checked={!!enableThinking}
          onChange={(checked) => onChange({ enableThinking: checked })}
        />
      ) : null}
      {showAllowUserEdit ? (
        <AgentConfigCapsuleToggle
          label="允许修改"
          checked={allowUserEdit}
          onChange={(checked) => onChange({ allowUserEdit: checked })}
        />
      ) : null}
      {showAllowQuestion ? (
        <AgentConfigCapsuleToggle
          label="允许追问"
          checked={allowQuestion}
          onChange={(checked) => onChange({ allowQuestion: checked })}
        />
      ) : null}
    </div>
  );
}

function AgentRuntimeLimitsRow({
  maxTokens,
  maxAgentIterationsPerTurn,
  maximumIterations,
  onMaxTokensChange,
  onMaxIterationsChange,
}: {
  maxTokens?: number;
  maxAgentIterationsPerTurn: number;
  maximumIterations: number;
  onMaxTokensChange: (value: number | undefined) => void;
  onMaxIterationsChange: (value: number) => void;
}) {
  return (
    <div className="workflow-agent-limits-row">
      <label className="sys-field sys-field--compact">
        <span className="sys-field-label">最大输出 Token</span>
        <div className="sys-field-input-wrap">
          <Hash size={16} className="sys-field-prefix" aria-hidden="true" />
          <input
            className="sys-field-input"
            type="number"
            min={256}
            max={131072}
            step={256}
            placeholder="沿用供应商默认"
            value={maxTokens ?? ""}
            onChange={(event) => {
              const raw = event.target.value.trim();
              if (!raw) {
                onMaxTokensChange(undefined);
                return;
              }
              const parsed = Number.parseInt(raw, 10);
              onMaxTokensChange(Number.isFinite(parsed) ? parsed : undefined);
            }}
          />
        </div>
        <span className="sys-field-hint">可选；长报告建议 8192+</span>
      </label>
      <label className="sys-field sys-field--compact">
        <span className="sys-field-label">单轮推理次数</span>
        <div className="sys-field-input-wrap">
          <ListChecks size={16} className="sys-field-prefix" aria-hidden="true" />
          <input
            className="sys-field-input"
            type="number"
            min={1}
            max={maximumIterations}
            step={1}
            value={maxAgentIterationsPerTurn}
            onChange={(event) => {
              const parsed = Number.parseInt(event.target.value, 10);
              if (Number.isFinite(parsed)) {
                onMaxIterationsChange(Math.min(maximumIterations, Math.max(1, parsed)));
              }
            }}
          />
        </div>
        <span className="sys-field-hint">1～{maximumIterations} 次，达上限后汇总答案</span>
      </label>
    </div>
  );
}

function ModelSelectField({
  modelOptions,
  modelProviderId,
  enableThinking,
  onChange,
}: {
  modelOptions: WorkflowModelOption[];
  modelProviderId: string;
  enableThinking: boolean;
  onChange: (patch: { modelProviderId: string; modelName: string; enableThinking: boolean }) => void;
}) {
  const selectedModel = modelOptions.find((model) => model.providerId === modelProviderId) ?? modelOptions[0];

  return (
    <SelectLikeField
      label="运行模型"
      icon={DatabaseZap}
      value={selectedModel?.providerId ?? ""}
      options={modelOptions.map((model) => ({
        value: model.providerId,
        label: `${model.providerName} · ${model.modelName}${model.reasoningModel ? " · 推理模型" : ""}`,
      }))}
      onChange={(providerId) => {
        const model = modelOptions.find((item) => item.providerId === providerId);
        onChange({
          modelProviderId: providerId,
          modelName: model?.modelName ?? "",
          enableThinking: model?.reasoningModel ? enableThinking : false,
        });
      }}
    />
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
    <AgentBehaviorCapsules
      allowUserEdit={allowUserEdit}
      allowQuestion={allowQuestion}
      onChange={onChange}
    />
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
  return (channel === "document" && kind !== "excel")
    || channel === "word_document"
    || kind === "word"
    || code.includes("word")
    || name.includes("word");
}

function isExcelWorkbookDeliveryCapability(option?: WorkflowCapabilityOption | null) {
  if (!option || option.assetType !== "delivery") {
    return false;
  }
  const channel = readString(option.config?.deliveryChannel, "").toLowerCase();
  const kind = readString(option.config?.documentKind, "").toLowerCase();
  const code = option.code.toLowerCase();
  const name = option.name.toLowerCase();
  return channel === "excel"
    || kind === "excel"
    || code.includes("excel")
    || code.includes("xlsx")
    || name.includes("excel")
    || name.includes("工作簿");
}

function readDeliveryConfigMode(value: unknown): DeliveryConfigMode {
  return readString(value, "single") === "multiple" ? "multiple" : "single";
}

function readDeliveryExecutionPolicy(value: unknown): DeliveryExecutionPolicy {
  return readString(value, "all") === "conditional" ? "conditional" : "all";
}

function readDeliveryTriggerType(value: unknown): DeliveryTriggerType {
  const text = readString(value, "always");
  if (text === "cluster_agent_matched" || text === "input_field_equals" || text === "agent_output_exists") {
    return text;
  }
  return "always";
}

function defaultDeliveryTriggerRule(): DeliveryTriggerRuleDraft {
  return {
    type: "always",
    clusterNodeId: "",
    agentId: "",
    inputNodeId: "",
    agentNodeId: "",
    variableName: "",
    expectedValue: "",
  };
}

function readDeliveryTriggerRule(value: unknown): DeliveryTriggerRuleDraft {
  const record = typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
  return {
    type: readDeliveryTriggerType(record.type),
    clusterNodeId: readString(record.clusterNodeId, ""),
    agentId: readString(record.agentId, ""),
    inputNodeId: readString(record.inputNodeId, ""),
    agentNodeId: readString(record.agentNodeId, ""),
    variableName: readString(record.variableName, ""),
    expectedValue: readString(record.expectedValue, ""),
  };
}

function readExcelSheets(value: unknown, defaultBodyTemplate: string): ExcelSheetDraft[] {
  if (!Array.isArray(value)) {
    return [createExcelSheetDraft(1, defaultBodyTemplate)];
  }
  const sheets = value
    .filter((item): item is Record<string, unknown> => isRecord(item))
    .map((item, index) => readExcelSheet(item, index + 1, defaultBodyTemplate));
  return sheets.length > 0 ? sheets : [createExcelSheetDraft(1, defaultBodyTemplate)];
}

function readExcelSheet(raw: Record<string, unknown>, index: number, defaultBodyTemplate: string): ExcelSheetDraft {
  const tableStyle = isRecord(raw.tableStyle) ? raw.tableStyle : {};
  return {
    id: readString(raw.id, `sheet_${index}_${Date.now().toString(36)}`),
    name: readString(raw.name, `Sheet${index}`),
    startCell: readString(raw.startCell, "A1").toUpperCase(),
    defaultCellType: readExcelCellType(raw.defaultCellType),
    bodyTemplate: readString(raw.bodyTemplate, readString(raw.markdownContent, readString(raw.body, defaultBodyTemplate))),
    tableStyle: {
      headerBold: readBooleanLike(tableStyle.headerBold, true),
      freezeHeader: readBooleanLike(tableStyle.freezeHeader, true),
      autoFilter: readBooleanLike(tableStyle.autoFilter, true),
      fontName: readString(tableStyle.fontName, ""),
      fontSize: Math.max(0, Math.round(readNumberLike(tableStyle.fontSize, 0))),
      horizontalAlignment: readExcelAlignment(tableStyle.horizontalAlignment),
    },
    columnRules: readExcelColumnRules(raw.columnRules),
    rowRules: readExcelRowRules(raw.rowRules),
    cellRules: readExcelCellRules(raw.cellRules),
  };
}

function createExcelSheetDraft(index: number, bodyTemplate = "| 项目 | 内容 |\n| --- | --- |\n| 示例 | 请替换为上游模型输出 |\n"): ExcelSheetDraft {
  return {
    id: `sheet_${Date.now().toString(36)}_${index}`,
    name: index === 1 ? "分析结果" : `Sheet${index}`,
    startCell: "A1",
    defaultCellType: "text",
    bodyTemplate,
    tableStyle: {
      headerBold: true,
      freezeHeader: true,
      autoFilter: true,
      fontName: "",
      fontSize: 0,
      horizontalAlignment: "",
    },
    columnRules: [],
    rowRules: [
      {
        id: `row_rule_${Date.now().toString(36)}_${index}`,
        target: "header",
        index: 1,
        text: "",
        bold: true,
        backgroundColor: "grey",
      },
    ],
    cellRules: [],
  };
}

function readExcelColumnRules(value: unknown): ExcelColumnRuleDraft[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is Record<string, unknown> => isRecord(item))
    .map((item, index) => {
      const target = inferExcelColumnTarget(item);
      const rawLetter = readString(item.letter, "").toUpperCase().replace(/[^A-Z]/g, "");
      return {
      id: readString(item.id, `column_rule_${index}`),
      target,
      letter: rawLetter || (target === "letter" ? "A" : ""),
      name: readString(item.name, readString(item.match, "")),
      applyFromRow: Math.max(1, Math.round(readNumberLike(item.applyFromRow, 2))),
      type: readExcelCellType(item.type),
      format: readString(item.format, ""),
      width: Math.max(0, Math.round(readNumberLike(item.width, 0))),
      bold: readBooleanLike(item.bold, false),
      backgroundColor: readString(item.backgroundColor, ""),
      horizontalAlignment: readExcelAlignment(item.horizontalAlignment),
    };
    });
}

function createExcelColumnRuleDraft(index: number): ExcelColumnRuleDraft {
  return {
    id: `column_rule_${Date.now().toString(36)}_${index}`,
    target: "letter",
    letter: "A",
    name: "",
    applyFromRow: 2,
    type: "text",
    format: "",
    width: 0,
    bold: false,
    backgroundColor: "",
    horizontalAlignment: "",
  };
}

function readExcelRowRules(value: unknown): ExcelRowRuleDraft[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is Record<string, unknown> => isRecord(item))
    .map((item, index) => ({
      id: readString(item.id, `row_rule_${index}`),
      target: readExcelRowTarget(item.target),
      index: Math.max(1, Math.round(readNumberLike(item.index, 1))),
      text: readString(item.text, ""),
      bold: readBooleanLike(item.bold, false),
      backgroundColor: readString(item.backgroundColor, ""),
    }));
}

function createExcelRowRuleDraft(index: number): ExcelRowRuleDraft {
  return {
    id: `row_rule_${Date.now().toString(36)}_${index}`,
    target: "last",
    index: 1,
    text: "",
    bold: true,
    backgroundColor: "",
  };
}

function readExcelCellRules(value: unknown): ExcelCellRuleDraft[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is Record<string, unknown> => isRecord(item))
    .map((item, index) => ({
      id: readString(item.id, `cell_rule_${index}`),
      cell: readString(item.cell, "A1").toUpperCase(),
      type: readExcelCellType(item.type),
      format: readString(item.format, ""),
      bold: readBooleanLike(item.bold, false),
      backgroundColor: readString(item.backgroundColor, ""),
      horizontalAlignment: readExcelAlignment(item.horizontalAlignment),
      allowFormula: readBooleanLike(item.allowFormula, false),
    }));
}

function createExcelCellRuleDraft(index: number): ExcelCellRuleDraft {
  return {
    id: `cell_rule_${Date.now().toString(36)}_${index}`,
    cell: "A1",
    type: "text",
    format: "",
    bold: true,
    backgroundColor: "",
    horizontalAlignment: "",
    allowFormula: false,
  };
}

function readExcelCellType(value: unknown): ExcelCellType {
  const text = readString(value, "text");
  return text === "number" || text === "currency" || text === "percent" || text === "date" || text === "datetime" || text === "boolean" ? text : "text";
}

function inferExcelColumnTarget(item: Record<string, unknown>): ExcelColumnRuleDraft["target"] {
  const explicit = readString(item.target, "");
  if (explicit === "name" || explicit === "letter") {
    return explicit;
  }
  const hasName = Boolean(readString(item.name, readString(item.match, "")));
  const hasLetter = Boolean(readString(item.letter, "").trim());
  if (hasName && !hasLetter) {
    return "name";
  }
  return "letter";
}

function readExcelColumnTarget(value: unknown): ExcelColumnRuleDraft["target"] {
  const text = readString(value, "letter");
  return text === "name" ? "name" : "letter";
}

function readExcelRowTarget(value: unknown): ExcelRowRuleDraft["target"] {
  const text = readString(value, "header");
  return text === "last" || text === "index" || text === "contains" ? text : "header";
}

function readExcelAlignment(value: unknown): string {
  const text = readString(value, "");
  return text === "left" || text === "center" || text === "right" ? text : "";
}

function readDeliveryItems(value: unknown, parentDeliveryMode = "direct"): DeliveryItemDraft[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
    .map((item, index) => {
      const rawConfig = typeof item.config === "object" && item.config !== null && !Array.isArray(item.config)
        ? { ...(item.config as Record<string, unknown>) }
        : {};
      return {
        id: readString(item.id, `delivery_item_${index + 1}`),
        name: readString(item.name, `交付项 ${index + 1}`),
        enabled: readBoolean(item.enabled, true),
        triggerRule: readDeliveryTriggerRule(item.triggerRule),
        config: {
          ...rawConfig,
          deliveryMode: readString(rawConfig.deliveryMode, parentDeliveryMode),
        },
      };
    });
}

function createDeliveryItemDraft(index: number, config: Record<string, unknown>, name: string): DeliveryItemDraft {
  return {
    id: `delivery_item_${Date.now().toString(36)}_${index}`,
    name,
    enabled: true,
    triggerRule: defaultDeliveryTriggerRule(),
    config,
  };
}

function describeDeliveryTrigger(rule: DeliveryTriggerRuleDraft) {
  switch (rule.type) {
    case "cluster_agent_matched":
      return rule.variableName ? `命中子智能体输出 {{${rule.variableName}}} 时触发` : "命中指定集群子智能体时触发";
    case "input_field_equals":
      return rule.variableName ? `{{${rule.variableName}}} = ${rule.expectedValue || "固定值"} 时触发` : "输入字段等于固定值时触发";
    case "agent_output_exists":
      return rule.variableName ? `单智能体输出 {{${rule.variableName}}} 存在时触发` : "单智能体有输出内容时触发";
    default:
      return "始终触发";
  }
}

function buildClusterAgentTriggerOptions(nodes: WorkflowEditorNode[]): ClusterAgentTriggerOption[] {
  return nodes
    .filter((node) => node.data.nodeType === "parallel_group")
    .flatMap((node) => {
      const rawAgents = node.data.rawConfig?.clusterAgents;
      if (!Array.isArray(rawAgents)) {
        return [];
      }
      return rawAgents
        .filter(isRecord)
        .map((agent, index): ClusterAgentTriggerOption | null => {
          const agentId = readString(agent.id, `agent_${index + 1}`);
          const agentName = readString(agent.name, `子智能体 ${index + 1}`);
          const variableName = normalizeVariableName(readString(agent.output, ""));
          if (!variableName) {
            return null;
          }
          return {
            value: `${node.id}::${agentId}`,
            label: `${node.data.label} / ${agentName} · {{${variableName}}}`,
            clusterNodeId: node.id,
            clusterName: node.data.label,
            agentId,
            agentName,
            variableName,
          };
        })
        .filter((item): item is ClusterAgentTriggerOption => item !== null);
    });
}

function buildInputFieldTriggerOptions(nodes: WorkflowEditorNode[]): InputFieldTriggerOption[] {
  return nodes
    .filter((node) => node.data.nodeType === "user_input")
    .flatMap((node) => {
      const fields = readInputFields(node.data.rawConfig?.inputFields, node.data.outputVariables);
      return fields
        .map((field): InputFieldTriggerOption | null => {
          const variableName = normalizeVariableName(field.variable);
          if (!variableName) {
            return null;
          }
          const fieldLabel = readString(field.label, variableName);
          return {
            value: `${node.id}::${variableName}`,
            label: `${node.data.label} / ${fieldLabel} · {{${variableName}}}`,
            inputNodeId: node.id,
            inputNodeName: node.data.label,
            fieldLabel,
            variableName,
          };
        })
        .filter((item): item is InputFieldTriggerOption => item !== null);
    });
}

function buildAgentOutputTriggerOptions(nodes: WorkflowEditorNode[]): AgentOutputTriggerOption[] {
  return nodes
    .filter((node) => node.data.nodeType === "agent")
    .flatMap((node) => node.data.outputVariables
      .map((variable): AgentOutputTriggerOption | null => {
        const variableName = normalizeVariableName(variable);
        if (!variableName) {
          return null;
        }
        return {
          value: `${node.id}::${variableName}`,
          label: `${node.data.label} · {{${variableName}}}`,
          agentNodeId: node.id,
          agentNodeName: node.data.label,
          variableName,
        };
      })
      .filter((item): item is AgentOutputTriggerOption => item !== null));
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
    // 后端目录已为每个子智能体写入建议循环次数；创建节点时原样继承，不在前端再定义默认值。
    const executionMode = readClusterExecutionMode(rawConfig.executionMode);
    const clusterAgents = (Array.isArray(rawConfig.clusterAgents) ? rawConfig.clusterAgents : [])
      .filter(isClusterAgentConfig)
      .map((agent, agentIndex) => ({
      ...agent,
      userPrompt: readString(agent.userPrompt, "") || DEFAULT_CLUSTER_USER_PROMPT,
      systemPrompt: readString(agent.systemPrompt, "") || DEFAULT_SYSTEM_PROMPT,
      output: createClusterAgentOutputVariable(index, agentIndex),
      intentCode: normalizeIntentCode(readString(agent.intentCode, `intent_${agentIndex + 1}`)),
      intentName: readString(agent.intentName, readString(agent.name, `意图 ${agentIndex + 1}`)),
      intentDescription: readString(agent.intentDescription, "描述这个子智能体适合处理的用户意图。"),
      }));
    rawConfig.clusterAgents = clusterAgents;
    rawConfig.executionMode = normalizeClusterExecutionModeForConfig(rawConfig.executionMode);
    rawConfig.intentSelectionMode = readString(rawConfig.intentSelectionMode, "multiple");
    rawConfig.intentFallbackMode = readString(rawConfig.intentFallbackMode, "fail");
    rawConfig.intentRoutes = readIntentRoutes(rawConfig.intentRoutes, clusterAgents);
    rawConfig.clusterOutputVariable = readClusterOutputVariable(rawConfig.clusterOutputVariable);
    rawConfig.mergeRule = readString(rawConfig.mergeRule, buildDefaultClusterMergeRule(clusterAgents));
    outputVariables = buildClusterOutputVariables(executionMode, clusterAgents, readClusterOutputVariable(rawConfig.clusterOutputVariable));
  }

  if (brickType === "delivery") {
    // 新建交付节点按紧邻上游输出变量生成默认模板，避免沿用 catalog 中不存在的占位变量。
    const upstreamVariable = inputVariables[inputVariables.length - 1];
    rawConfig.deliveryContent = upstreamVariable
      ? `# 交付结果\n\n{{${upstreamVariable}}}`
      : "# 交付结果\n\n请在这里编写最终交付内容。";
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

function createClusterAgent(index: number, nodeId: string, suggestedIterationsPerTurn: number, model?: WorkflowModelOption): ClusterAgentConfig {
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
    maxAgentIterationsPerTurn: suggestedIterationsPerTurn,
    allowUserEdit: false,
    allowQuestion: false,
    modelProviderId: model?.providerId ?? "",
    modelName: model?.modelName ?? "",
    enableThinking: false,
    intentCode: `intent_${index + 1}`,
    intentName: `意图 ${index + 1}`,
    intentDescription: "描述这个子智能体适合处理的用户意图。",
  };
}

function createClusterAgentOutputVariable(clusterKey: number | string, agentIndex: number) {
  const parsedIndex = typeof clusterKey === "number"
    ? clusterKey
    : Number(clusterKey.match(/_(\d+)$/)?.[1] ?? 1);
  return `cluster_${Math.max(1, Number.isFinite(parsedIndex) ? parsedIndex : 1)}_agent_${agentIndex + 1}_output`;
}

function readClusterExecutionMode(value: unknown): ClusterExecutionMode {
  const mode = readString(value, "collaborative");
  return mode === "collaborative" || mode === "relay" || mode === "intent" ? mode : "collaborative";
}

function normalizeClusterExecutionModeForConfig(value: unknown): string {
  const mode = readString(value, "");
  return mode || "collaborative";
}

function readClusterOutputVariable(value: unknown) {
  return normalizeVariableName(readString(value, "cluster_result")) || "cluster_result";
}

function buildClusterOutputVariables(mode: ClusterExecutionMode, agents: ClusterAgentConfig[], outputVariable: string) {
  const clusterOutput = readClusterOutputVariable(outputVariable);
  if (mode === "intent") {
    return [clusterOutput];
  }
  return [clusterOutput, ...agents.map((agent) => agent.output).filter(Boolean)]
    .filter((value, index, values) => values.indexOf(value) === index);
}

function buildDefaultClusterMergeRule(agents: ClusterAgentConfig[]) {
  if (agents.length === 0) {
    return "## 智能体集群结论";
  }
  return [
    "## 智能体集群结论",
    ...agents.map((agent, index) => {
      const output = agent.output || `agent_${index + 1}_output`;
      return `\n### ${agent.name || `子智能体 ${index + 1}`}\n{{${output}}}`;
    }),
  ].join("\n");
}

function normalizeIntentCode(value: string) {
  return normalizeVariableName(value);
}

function readIntentSelectionMode(value: unknown): IntentSelectionMode {
  return value === "single" ? "single" : "multiple";
}

function readIntentFallbackMode(value: unknown): IntentFallbackMode {
  return value === "agent" || value === "fixed_reply" ? value : "fail";
}

function readIntentRoutes(value: unknown, agents: ClusterAgentConfig[]): IntentRouteConfig[] {
  if (Array.isArray(value)) {
    const routes = value
      .filter(isRecord)
      .map((route, index) => {
        const agentId = readString(route.agentId, "");
        const agent = agents.find((item) => item.id === agentId);
        return {
          id: readString(route.id, `intent_route_${index + 1}`),
          intentCode: normalizeIntentCode(readString(route.intentCode, `intent_${index + 1}`)),
          intentName: readString(route.intentName, readString(agent?.name, `意图 ${index + 1}`)),
          intentDescription: readString(route.intentDescription, readString(route.description, "")),
          agentId,
        };
      })
      .filter((route) => route.intentCode);
    if (routes.length > 0) {
      return routes;
    }
  }
  return agents
    .map((agent, index) => ({
      id: `intent_route_${agent.id}`,
      intentCode: normalizeIntentCode(readString(agent.intentCode, `intent_${index + 1}`)),
      intentName: readString(agent.intentName, readString(agent.name, `意图 ${index + 1}`)),
      intentDescription: readString(agent.intentDescription, ""),
      agentId: agent.id,
    }))
    .filter((route) => route.intentCode && route.intentDescription);
}

function clusterAgentAvailableVariables(
  upstreamVariables: WorkflowVariable[],
  agents: ClusterAgentConfig[],
  agentId: string,
  executionMode: ClusterExecutionMode,
): WorkflowVariable[] {
  if (executionMode !== "relay") {
    return upstreamVariables;
  }
  const agentIndex = agents.findIndex((agent) => agent.id === agentId);
  if (agentIndex <= 0) {
    return upstreamVariables;
  }
  const relayVariables = agents.slice(0, agentIndex)
    .map((agent) => agent.output)
    .filter(Boolean)
    .map((name): WorkflowVariable => ({
      name,
      sourceNodeId: agentId,
      sourceNodeName: "前序子智能体",
      type: "string",
      sensitive: false,
      deliverable: false,
      description: "接力处理模式下由前序子智能体产生的输出",
    }));
  return [...upstreamVariables, ...relayVariables];
}

function clusterAgentVariableIssues(
  agent: ClusterAgentConfig,
  agentIndex: number,
  agents: ClusterAgentConfig[],
  executionMode: ClusterExecutionMode,
  upstreamVariables: WorkflowVariable[],
): string[] {
  const available = new Set(upstreamVariables.map((variable) => variable.name));
  if (executionMode === "relay") {
    agents.slice(0, agentIndex).forEach((item) => {
      if (item.output) {
        available.add(item.output);
      }
    });
  }
  const unresolved = new Set<string>();
  [agent.systemPrompt, agent.userPrompt].forEach((text) => {
    extractTemplateVariableNames(text).forEach((name) => {
      if (!WORKFLOW_SYSTEM_TEMPLATE_VARIABLES.has(name) && !available.has(name)) {
        unresolved.add(name);
      }
    });
  });
  return [...unresolved].map((name) => `提示词引用的 {{${name}}} 在当前执行顺序下不可用`);
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

function uniqueDisplayName(value: string, existingNames: Set<string>) {
  const base = value.trim() || "副本";
  let candidate = base;
  let suffix = 2;
  while (existingNames.has(candidate)) {
    candidate = `${base} ${suffix}`;
    suffix += 1;
  }
  return candidate;
}

function uniquifyNewNodeOutputVariables(node: WorkflowEditorNode, existingVariables: Set<string>): WorkflowEditorNode {
  const brickType = getBrickType(node);
  const rawConfig = cloneRecord(node.data.rawConfig ?? {});
  const usedVariables = new Set(existingVariables);

  if (brickType === "input") {
    const fields = readInputFields(rawConfig.inputFields, node.data.outputVariables).map((field, index) => {
      const variable = uniqueVariableName(field.variable || node.data.outputVariables[index] || `input_${index + 1}`, usedVariables);
      usedVariables.add(variable);
      return { ...field, variable };
    });
    return {
      ...node,
      data: {
        ...node.data,
        outputVariables: fields.map((field) => field.variable).filter(Boolean),
        rawConfig: { ...rawConfig, inputFields: fields },
      },
    };
  }

  if (brickType === "cluster") {
    const executionMode = readClusterExecutionMode(rawConfig.executionMode);
    const clusterOutputVariable = uniqueVariableName(readClusterOutputVariable(rawConfig.clusterOutputVariable), usedVariables);
    usedVariables.add(clusterOutputVariable);
    const clusterAgents = Array.isArray(rawConfig.clusterAgents)
      ? rawConfig.clusterAgents
        .filter(isClusterAgentConfig)
        .map((agent, index) => {
          const output = uniqueVariableName(agent.output || `agent_${index + 1}_output`, usedVariables);
          usedVariables.add(output);
          return { ...agent, output };
        })
      : [];
    return {
      ...node,
      data: {
        ...node.data,
        outputVariables: buildClusterOutputVariables(executionMode, clusterAgents, clusterOutputVariable),
        rawConfig: {
          ...rawConfig,
          clusterAgents,
          clusterOutputVariable,
          mergeRule: buildDefaultClusterMergeRule(clusterAgents),
        },
      },
    };
  }

  const outputVariables = node.data.outputVariables.map((variable, index) => {
    const output = uniqueVariableName(variable || `output_${index + 1}`, usedVariables);
    usedVariables.add(output);
    return output;
  });
  return {
    ...node,
    data: {
      ...node.data,
      outputVariables,
      rawConfig: brickType === "agent" && outputVariables[0]
        ? { ...rawConfig, output: outputVariables[0], outputVariable: outputVariables[0] }
        : rawConfig,
    },
  };
}

function ensureSystemTrigger(nextNodes: WorkflowEditorNode[], catalog: WorkflowDesignerCatalog) {
  if (nextNodes.some((node) => node.id === SYSTEM_TRIGGER_ID)) {
    return nextNodes;
  }

  return [createNodeFromTemplate(catalog.systemTrigger, 0, []), ...nextNodes];
}

function toEditorNode(node: WorkflowNodeDraft, agentRuntimeLimits: AgentRuntimeLimits): WorkflowEditorNode {
  const config = { ...(node.config ?? {}) } as Record<string, unknown>;
  if (node.nodeType === "agent" && !readOptionalInt(config.maxAgentIterationsPerTurn)) {
    config.maxAgentIterationsPerTurn = agentRuntimeLimits.suggestedIterationsPerTurn;
  } else if (node.nodeType === "agent") {
    config.maxAgentIterationsPerTurn = readAgentIterationsPerTurn(config.maxAgentIterationsPerTurn, agentRuntimeLimits);
  }
  if (node.nodeType === "parallel_group" && Array.isArray(config.clusterAgents)) {
    config.executionMode = normalizeClusterExecutionModeForConfig(config.executionMode);
    config.clusterAgents = config.clusterAgents.map((agent) => isRecord(agent)
      ? {
        ...agent,
        maxAgentIterationsPerTurn: readAgentIterationsPerTurn(agent.maxAgentIterationsPerTurn, agentRuntimeLimits),
      }
      : agent);
  }
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

function syncAgentOutputConfig(
  nodeType: string,
  outputVariables: string[],
  config: Record<string, unknown>,
): Record<string, unknown> {
  if (nodeType !== "agent" || outputVariables.length === 0) {
    return config;
  }
  const outputName = outputVariables[0]?.trim();
  if (!outputName) {
    return config;
  }
  return {
    ...config,
    output: outputName,
    outputVariable: outputName,
  };
}

function toWorkflowNodeDraft(node: WorkflowEditorNode): WorkflowNodeDraft {
  const normalizedConfig = normalizeWorkflowNodeConfig(
    node.data.nodeType,
    (node.data.rawConfig ?? {}) as Record<string, unknown>,
  );
  const configWithOutput = syncAgentOutputConfig(
    node.data.nodeType,
    node.data.outputVariables,
    normalizedConfig,
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
      ...configWithOutput,
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
  const nextNodes = ensureSystemTrigger(
    detail.nodes.map((node) => toEditorNode(node, catalog.agentRuntimeLimits)),
    catalog,
  );
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

function buildSystemRuntimeVariables(metadataByName: Record<string, WorkflowVariableTemplate> = {}): WorkflowVariable[] {
  return [...WORKFLOW_SYSTEM_TEMPLATE_VARIABLES]
    .filter((name) => metadataByName[name])
    .map((name) => {
      const metadata = metadataByName[name];
      return {
        name,
        sourceNodeId: SYSTEM_RUNTIME_VARIABLE_SOURCE_ID,
        sourceNodeName: "系统运行变量",
        type: metadata.type,
        sensitive: metadata.sensitive,
        deliverable: metadata.deliverable,
        description: metadata.description,
      };
    });
}

function isSystemRuntimeVariable(variable: WorkflowVariable) {
  return variable.sourceNodeId === SYSTEM_RUNTIME_VARIABLE_SOURCE_ID;
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

function readClusterAgents(value: unknown, agentRuntimeLimits: AgentRuntimeLimits): ClusterAgentConfig[] {
  if (Array.isArray(value)) {
    const agents = value.filter(isClusterAgentConfig);
    if (agents.length > 0) {
      return agents.map((agent, index) => ({
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
        maxAgentIterationsPerTurn: readAgentIterationsPerTurn(agent.maxAgentIterationsPerTurn, agentRuntimeLimits),
        allowUserEdit: readBoolean(agent.allowUserEdit, false),
        allowQuestion: readBoolean(agent.allowQuestion, false),
        modelProviderId: readString(agent.modelProviderId, ""),
        modelName: readString(agent.modelName, ""),
        enableThinking: readBoolean(agent.enableThinking, false),
        intentCode: normalizeIntentCode(readString(agent.intentCode, `intent_${index + 1}`)),
        intentName: readString(agent.intentName, readString(agent.name, `意图 ${index + 1}`)),
        intentDescription: readString(agent.intentDescription, "描述这个子智能体适合处理的用户意图。"),
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

function readAgentIterationsPerTurn(value: unknown, limits: AgentRuntimeLimits): number {
  return Math.min(
    readPositiveInt(value, limits.suggestedIterationsPerTurn),
    Math.max(1, limits.maxIterationsPerTurn),
  );
}

function readPositiveInt(value: unknown, fallback: number): number {
  return readOptionalInt(value) ?? fallback;
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
    numberFont: readString(merged.numberFont, String(DEFAULT_WORD_DOCUMENT_STYLE.numberFont)),
    bodyFontSize: readFontSizeLike(merged.bodyFontSize, DEFAULT_WORD_DOCUMENT_STYLE.bodyFontSize),
    bodyAlignment: readString(merged.bodyAlignment, String(DEFAULT_WORD_DOCUMENT_STYLE.bodyAlignment)),
    heading1FontSize: readFontSizeLike(merged.heading1FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading1FontSize),
    heading2FontSize: readFontSizeLike(merged.heading2FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading2FontSize),
    heading3FontSize: readFontSizeLike(merged.heading3FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading3FontSize),
    heading4FontSize: readTableFontSizeLike(merged.heading4FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading4FontSize),
    heading5FontSize: readTableFontSizeLike(merged.heading5FontSize, DEFAULT_WORD_DOCUMENT_STYLE.heading5FontSize),
    heading1ChineseFont: readOptionalString(merged.heading1ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading1ChineseFont),
    heading1LatinFont: readOptionalString(merged.heading1LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading1LatinFont),
    heading1NumberFont: readOptionalString(merged.heading1NumberFont, DEFAULT_WORD_DOCUMENT_STYLE.heading1NumberFont),
    heading2ChineseFont: readOptionalString(merged.heading2ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading2ChineseFont),
    heading2LatinFont: readOptionalString(merged.heading2LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading2LatinFont),
    heading2NumberFont: readOptionalString(merged.heading2NumberFont, DEFAULT_WORD_DOCUMENT_STYLE.heading2NumberFont),
    heading3ChineseFont: readOptionalString(merged.heading3ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading3ChineseFont),
    heading3LatinFont: readOptionalString(merged.heading3LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading3LatinFont),
    heading3NumberFont: readOptionalString(merged.heading3NumberFont, DEFAULT_WORD_DOCUMENT_STYLE.heading3NumberFont),
    heading4ChineseFont: readOptionalString(merged.heading4ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading4ChineseFont),
    heading4LatinFont: readOptionalString(merged.heading4LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading4LatinFont),
    heading4NumberFont: readOptionalString(merged.heading4NumberFont, DEFAULT_WORD_DOCUMENT_STYLE.heading4NumberFont),
    heading5ChineseFont: readOptionalString(merged.heading5ChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.heading5ChineseFont),
    heading5LatinFont: readOptionalString(merged.heading5LatinFont, DEFAULT_WORD_DOCUMENT_STYLE.heading5LatinFont),
    heading5NumberFont: readOptionalString(merged.heading5NumberFont, DEFAULT_WORD_DOCUMENT_STYLE.heading5NumberFont),
    heading1Bold: readBooleanLike(merged.heading1Bold, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.heading1Bold)),
    heading2Bold: readBooleanLike(merged.heading2Bold, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.heading2Bold)),
    heading3Bold: readBooleanLike(merged.heading3Bold, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.heading3Bold)),
    heading4Bold: readBooleanLike(merged.heading4Bold, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.heading4Bold)),
    heading5Bold: readBooleanLike(merged.heading5Bold, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.heading5Bold)),
    tableChineseFont: readOptionalString(merged.tableChineseFont, DEFAULT_WORD_DOCUMENT_STYLE.tableChineseFont),
    tableLatinFont: readOptionalString(merged.tableLatinFont, DEFAULT_WORD_DOCUMENT_STYLE.tableLatinFont),
    tableNumberFont: readOptionalString(merged.tableNumberFont, DEFAULT_WORD_DOCUMENT_STYLE.tableNumberFont),
    tableFontSize: readTableFontSizeLike(merged.tableFontSize, DEFAULT_WORD_DOCUMENT_STYLE.tableFontSize),
    tableCellAlignment: readString(merged.tableCellAlignment, DEFAULT_WORD_DOCUMENT_STYLE.tableCellAlignment),
    tableCellVerticalAlignment: readString(merged.tableCellVerticalAlignment, String(DEFAULT_WORD_DOCUMENT_STYLE.tableCellVerticalAlignment)),
    tableCellPaddingVerticalPt: readNumberLike(merged.tableCellPaddingVerticalPt, Number(DEFAULT_WORD_DOCUMENT_STYLE.tableCellPaddingVerticalPt)),
    tableHeaderBold: readBooleanLike(merged.tableHeaderBold, DEFAULT_WORD_DOCUMENT_STYLE.tableHeaderBold),
    tableBorders: readBooleanLike(merged.tableBorders, DEFAULT_WORD_DOCUMENT_STYLE.tableBorders),
    tableBorderWidthPt: readNumberLike(merged.tableBorderWidthPt, DEFAULT_WORD_DOCUMENT_STYLE.tableBorderWidthPt),
    tableLineSpacingMode: readLineSpacingMode(merged.tableLineSpacingMode ?? DEFAULT_WORD_DOCUMENT_STYLE.tableLineSpacingMode),
    tableLineSpacing: readNumberLike(merged.tableLineSpacing, DEFAULT_WORD_DOCUMENT_STYLE.tableLineSpacing),
    tableLineSpacingPt: readNumberLike(merged.tableLineSpacingPt, DEFAULT_WORD_DOCUMENT_STYLE.tableLineSpacingPt),
    lineSpacingMode: readLineSpacingMode(merged.lineSpacingMode ?? DEFAULT_WORD_DOCUMENT_STYLE.lineSpacingMode),
    lineSpacing: readNumberLike(merged.lineSpacing, Number(DEFAULT_WORD_DOCUMENT_STYLE.lineSpacing)),
    lineSpacingPt: readNumberLike(merged.lineSpacingPt, Number(DEFAULT_WORD_DOCUMENT_STYLE.lineSpacingPt)),
    firstLineIndentMode: (merged.firstLineIndentMode === "cm" ? "cm" : "chars") as "chars" | "cm",
    firstLineIndentChars: readNumberLike(merged.firstLineIndentChars, Number(DEFAULT_WORD_DOCUMENT_STYLE.firstLineIndentChars)),
    firstLineIndentCm: readNumberLike(merged.firstLineIndentCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.firstLineIndentCm)),
    paragraphSpacingUnit: readSpacingUnit(merged.paragraphSpacingUnit ?? DEFAULT_WORD_DOCUMENT_STYLE.paragraphSpacingUnit),
    paragraphSpacingBefore: readNumberLike(merged.paragraphSpacingBefore, Number(DEFAULT_WORD_DOCUMENT_STYLE.paragraphSpacingBefore)),
    paragraphSpacingAfter: readNumberLike(merged.paragraphSpacingAfter, Number(DEFAULT_WORD_DOCUMENT_STYLE.paragraphSpacingAfter)),
    marginTopCm: readNumberLike(merged.marginTopCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginTopCm)),
    marginBottomCm: readNumberLike(merged.marginBottomCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginBottomCm)),
    marginLeftCm: readNumberLike(merged.marginLeftCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginLeftCm)),
    marginRightCm: readNumberLike(merged.marginRightCm, Number(DEFAULT_WORD_DOCUMENT_STYLE.marginRightCm)),
    titleCentered: readBooleanLike(merged.titleCentered, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.titleCentered)),
    headingFirstLineIndent: readBooleanLike(merged.headingFirstLineIndent, Boolean(DEFAULT_WORD_DOCUMENT_STYLE.headingFirstLineIndent)),
    paragraphRules: readParagraphRules(merged.paragraphRules),
  };
}

function readParagraphRules(value: unknown): ParagraphRule[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const allowedTargets = new Set(["index", "first", "second", "third", "last", "secondLast"]);
  const allowedIndentModes = new Set(["", "none", "chars", "cm"]);
  return value
    .filter((item): item is Record<string, unknown> => isRecord(item))
    .map((item, index) => {
      const targetType = typeof item.targetType === "string" && allowedTargets.has(item.targetType)
        ? (item.targetType as ParagraphRule["targetType"])
        : "index";
      const indentMode = typeof item.firstLineIndentMode === "string" && allowedIndentModes.has(item.firstLineIndentMode)
        ? (item.firstLineIndentMode as ParagraphRule["firstLineIndentMode"])
        : "";
      const rawId = typeof item.id === "string" && item.id ? item.id : `rule-${index}`;
      return {
        id: rawId,
        targetType,
        targetIndex: Math.max(1, Math.round(readNumberLike(item.targetIndex, 1))),
        alignment: readString(item.alignment, ""),
        firstLineIndentMode: indentMode,
        firstLineIndentChars: readNumberLike(item.firstLineIndentChars, 2),
        firstLineIndentCm: readNumberLike(item.firstLineIndentCm, 0.75),
        chineseFont: readOptionalString(item.chineseFont, ""),
        latinFont: readOptionalString(item.latinFont, ""),
        numberFont: readOptionalString(item.numberFont, ""),
        fontSize: readTableFontSizeLike(item.fontSize, 0),
        spacingUnit: (typeof item.spacingUnit === "string"
          && ["line", "pt", "cm", "mm"].includes(item.spacingUnit)
          ? item.spacingUnit
          : "") as ParagraphRule["spacingUnit"],
        spacingBefore: readNumberLike(item.spacingBefore, 0),
        spacingAfter: readNumberLike(item.spacingAfter, 0),
        blankLinesBefore: Math.max(0, Math.round(readNumberLike(item.blankLinesBefore, 0))),
        blankLinesAfter: Math.max(0, Math.round(readNumberLike(item.blankLinesAfter, 0))),
      };
    });
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
    sourceLabel: isSystemRuntimeVariable(variable) ? variable.sourceNodeName : `节点「${variable.sourceNodeName}」输出变量`,
    typeLabel: workflowVariableTypeLabel(variable.type),
    description: [variable.description || (isSystemRuntimeVariable(variable) ? "运行时由系统自动注入。" : "可引用该节点输出内容。"), flags].filter(Boolean).join("；"),
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

function normalizeWordFileNameTemplate(template: string): string {
  return template.replace(/\{\{\s*runId\s*\}\}/g, "{{runNumber}}");
}

function normalizeExcelFileNameTemplate(template: string): string {
  const normalized = template.replace(/\{\{\s*runId\s*\}\}/g, "{{runNumber}}");
  return normalized.toLowerCase().endsWith(".xlsx") ? normalized : `${normalized}.xlsx`;
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
