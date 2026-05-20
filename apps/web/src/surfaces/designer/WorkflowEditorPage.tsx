import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ChevronLeft,
  CircleDot,
  Clock3,
  Database,
  FileCheck2,
  FileText,
  GitMerge,
  ListChecks,
  MessageSquareText,
  PackageCheck,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  Route,
  Search,
  ShieldCheck,
  Split,
  TextCursorInput,
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

type ParallelTask = {
  name: string;
  assignee: string;
  output: string;
  purpose: string;
  mode: "数据采集" | "章节生成" | "组装校验";
};

type MergeMapping = {
  source: string;
  target: string;
  rule: string;
};

type WorkflowStage = {
  id: "input" | "agent" | "review";
  title: string;
  subtitle: string;
  icon: typeof TextCursorInput;
  nodeTypes: WorkflowNodeType[];
};

type WorkflowEditorPageProps = {
  workflow: WorkflowDraft;
  onBack: () => void;
  onDraftSaved: (draft: WorkflowDraft) => void;
};

const nodeTypeMeta: Record<WorkflowNodeType, { icon: typeof Zap; accentClass: string }> = {
  trigger: {
    icon: Zap,
    accentClass: "bg-zinc-100 text-zinc-700 dark:bg-zinc-900/50 dark:text-zinc-300",
  },
  user_input: {
    icon: TextCursorInput,
    accentClass: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  },
  agent: {
    icon: Bot,
    accentClass: "bg-indigo-100 text-indigo-800 dark:bg-indigo-950/50 dark:text-indigo-300",
  },
  parallel_group: {
    icon: Split,
    accentClass: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300",
  },
  merge: {
    icon: GitMerge,
    accentClass: "bg-violet-100 text-violet-800 dark:bg-violet-950/50 dark:text-violet-300",
  },
  condition: {
    icon: Route,
    accentClass: "bg-yellow-100 text-yellow-800 dark:bg-yellow-950/50 dark:text-yellow-300",
  },
  human_review: {
    icon: ShieldCheck,
    accentClass: "bg-red-100 text-red-800 dark:bg-red-950/50 dark:text-red-300",
  },
  delivery: {
    icon: PackageCheck,
    accentClass: "bg-orange-100 text-orange-800 dark:bg-orange-950/50 dark:text-orange-300",
  },
};

const nodeTypeLabels: Record<WorkflowNodeType, string> = {
  trigger: "触发节点",
  user_input: "用户输入节点",
  agent: "智能体节点",
  parallel_group: "并行节点组",
  merge: "合并节点",
  condition: "条件分支节点",
  human_review: "人工审核节点",
  delivery: "交付节点",
};

const workflowStages: WorkflowStage[] = [
  {
    id: "input",
    title: "输入内容",
    subtitle: "发起方式、公司全称、附件与补充说明",
    icon: TextCursorInput,
    nodeTypes: ["trigger", "user_input"],
  },
  {
    id: "agent",
    title: "智能体协作处理",
    subtitle: "MCP 取数、Skill 强化角色、章节并行生成与组装",
    icon: Bot,
    nodeTypes: ["agent", "parallel_group", "merge", "condition"],
  },
  {
    id: "review",
    title: "审查交付",
    subtitle: "人工确认、交付动作与结果固化",
    icon: FileCheck2,
    nodeTypes: ["human_review", "delivery"],
  },
];

// 新草稿默认采用授信报告闭环，强调少量阶段和智能体协作；当前仍复用后端节点、边、变量定义持久化。
const starterNodes: WorkflowEditorNode[] = [
  {
    id: "trigger_manual",
    position: { x: 0, y: 0 },
    data: {
      label: "手动发起授信报告",
      typeLabel: "触发节点",
      nodeType: "trigger",
      summary: "由业务人员从工作台选择授信报告流程并创建一次运行。",
      inputVariables: [],
      outputVariables: ["starter", "started_at"],
      pausePoint: false,
      configStatus: "complete",
      runState: "已完成",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
    },
  },
  {
    id: "input_company",
    position: { x: 260, y: 0 },
    data: {
      label: "输入授信主体",
      typeLabel: "用户输入节点",
      nodeType: "user_input",
      summary: "收集授信公司全称、统一社会信用代码、授信用途和附件材料。",
      inputVariables: ["starter"],
      outputVariables: ["company_full_name", "credit_request", "attachments"],
      pausePoint: true,
      configStatus: "complete",
      runState: "等待输入",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
    },
  },
  {
    id: "agent_intake",
    position: { x: 560, y: 0 },
    data: {
      label: "授信任务规划",
      typeLabel: "智能体节点",
      nodeType: "agent",
      summary: "智能体根据公司名称和授信用途识别缺失信息，必要时形成追问。",
      inputVariables: ["company_full_name", "credit_request", "attachments"],
      outputVariables: ["credit_work_plan", "missing_questions"],
      pausePoint: true,
      configStatus: "incomplete",
      runState: "待配置",
      outputMode: "分析后暂停",
      toolCount: 2,
      allowQuestion: true,
    },
  },
  {
    id: "parallel_data_collect",
    position: { x: 860, y: 0 },
    data: {
      label: "并行获取授信数据",
      typeLabel: "并行节点组",
      nodeType: "parallel_group",
      summary: "多个子智能体同时调用 MCP 获取工商、司法、财务和行业数据。",
      inputVariables: ["credit_work_plan"],
      outputVariables: ["credit_evidence_pack"],
      pausePoint: false,
      configStatus: "complete",
      runState: "执行中",
      outputMode: "一次性输出",
      toolCount: 4,
      allowQuestion: false,
    },
  },
  {
    id: "parallel_chapter_write",
    position: { x: 1160, y: 0 },
    data: {
      label: "章节并行生成",
      typeLabel: "并行节点组",
      nodeType: "parallel_group",
      summary: "经营概况、财务分析、风险判断和授信建议由多个章节智能体并行生成。",
      inputVariables: ["credit_evidence_pack", "credit_work_plan"],
      outputVariables: ["chapter_drafts"],
      pausePoint: false,
      configStatus: "complete",
      runState: "未开始",
      outputMode: "追问确认",
      toolCount: 3,
      allowQuestion: true,
    },
  },
  {
    id: "merge_credit_report",
    position: { x: 1460, y: 0 },
    data: {
      label: "报告组装处理",
      typeLabel: "合并节点",
      nodeType: "merge",
      summary: "报告组装智能体统一口径、去重冲突信息，并生成待审查授信报告。",
      inputVariables: ["chapter_drafts", "credit_evidence_pack"],
      outputVariables: ["credit_report_draft"],
      pausePoint: true,
      configStatus: "complete",
      runState: "未开始",
      outputMode: "追问确认",
      toolCount: 1,
      allowQuestion: true,
    },
  },
  {
    id: "human_review",
    position: { x: 1760, y: 0 },
    data: {
      label: "用户审查确认",
      typeLabel: "人工审核节点",
      nodeType: "human_review",
      summary: "发起人或审核人审查报告草稿，可要求重新生成模型内容或确认交付。",
      inputVariables: ["credit_report_draft"],
      outputVariables: ["review_decision"],
      pausePoint: true,
      configStatus: "incomplete",
      runState: "等待审核",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
    },
  },
  {
    id: "delivery_document",
    position: { x: 2060, y: 0 },
    data: {
      label: "生成并交付文档",
      typeLabel: "交付节点",
      nodeType: "delivery",
      summary: "按租户交付能力生成 Word / PDF，确认后写入交付记录。",
      inputVariables: ["credit_report_draft", "review_decision"],
      outputVariables: ["delivery_record"],
      pausePoint: false,
      configStatus: "incomplete",
      runState: "待配置",
      outputMode: "一次性输出",
      toolCount: 1,
      allowQuestion: false,
    },
  },
];

const starterEdges: WorkflowEditorEdge[] = [
  { id: "e_trigger_input", source: "trigger_manual", target: "input_company" },
  { id: "e_input_agent", source: "input_company", target: "agent_intake" },
  { id: "e_agent_data", source: "agent_intake", target: "parallel_data_collect" },
  { id: "e_data_chapter", source: "parallel_data_collect", target: "parallel_chapter_write" },
  { id: "e_chapter_merge", source: "parallel_chapter_write", target: "merge_credit_report" },
  { id: "e_merge_review", source: "merge_credit_report", target: "human_review" },
  { id: "e_review_delivery", source: "human_review", target: "delivery_document" },
];

const starterVariableMetadata: Record<string, Pick<WorkflowVariable, "type" | "sensitive" | "deliverable" | "description">> = {
  starter: { type: "string", sensitive: false, deliverable: false, description: "流程发起人标识" },
  started_at: { type: "string", sensitive: false, deliverable: false, description: "流程发起时间" },
  company_full_name: { type: "string", sensitive: false, deliverable: false, description: "授信公司全称" },
  credit_request: { type: "object", sensitive: false, deliverable: false, description: "授信用途、金额和期限等申请信息" },
  attachments: { type: "file", sensitive: true, deliverable: false, description: "用户上传附件" },
  credit_work_plan: { type: "object", sensitive: false, deliverable: false, description: "智能体拆解后的授信处理计划" },
  missing_questions: { type: "array", sensitive: false, deliverable: false, description: "需要用户补充的问题" },
  credit_evidence_pack: { type: "object", sensitive: true, deliverable: false, description: "MCP 返回的数据证据包" },
  chapter_drafts: { type: "object", sensitive: false, deliverable: false, description: "多个章节智能体生成的章节草稿" },
  credit_report_draft: { type: "object", sensitive: false, deliverable: true, description: "待审查授信报告草稿" },
  review_decision: { type: "decision", sensitive: false, deliverable: false, description: "人工审查结论" },
  delivery_record: { type: "object", sensitive: false, deliverable: true, description: "交付结果记录" },
};

const parallelTasks: ParallelTask[] = [
  {
    name: "工商与股权信息",
    assignee: "企业信息 MCP",
    output: "company_registry",
    purpose: "核验主体名称、股东、对外投资和经营状态。",
    mode: "数据采集",
  },
  {
    name: "司法与舆情核验",
    assignee: "司法查询 MCP / 舆情检索 Skill",
    output: "risk_events",
    purpose: "收集涉诉、执行、处罚和公开风险事件。",
    mode: "数据采集",
  },
  {
    name: "财务指标提取",
    assignee: "文件读取 MCP / 财务分析 Skill",
    output: "finance_metrics",
    purpose: "从报表和附件中提取资产、负债、收入、现金流等指标。",
    mode: "数据采集",
  },
  {
    name: "章节智能体并行",
    assignee: "经营分析 / 财务分析 / 风险建议智能体",
    output: "chapter_drafts",
    purpose: "按章节并发生成报告正文，再交给组装智能体统一口径。",
    mode: "章节生成",
  },
];

const mergeMappings: MergeMapping[] = [
  { source: "company_registry", target: "credit_report_draft.companyProfile", rule: "保留查询时间和来源摘要，用于审计追溯。" },
  { source: "risk_events", target: "credit_report_draft.riskSection", rule: "风险事件只允许重新获取，不允许用户追问改写事实。" },
  { source: "finance_metrics", target: "credit_report_draft.financeSection", rule: "指标进入结构化表格，异常口径交给审核人确认。" },
  { source: "chapter_drafts", target: "credit_report_draft.sections", rule: "模型文本可重新生成或追问改写，但不得覆盖数据来源。" },
];

export function WorkflowEditorPage({ workflow, onBack, onDraftSaved }: WorkflowEditorPageProps) {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [nodes, setNodes] = useState<WorkflowEditorNode[]>([]);
  const [edges, setEdges] = useState<WorkflowEditorEdge[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState("");
  const [isConfigCollapsed, setIsConfigCollapsed] = useState(false);
  const [nodeSearchValue, setNodeSearchValue] = useState("");
  const [insertedVariableName, setInsertedVariableName] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [saveFeedback, setSaveFeedback] = useState<{ tone: "success" | "error" | "info"; message: string } | null>(null);
  const [usingStarterTemplate, setUsingStarterTemplate] = useState(false);
  const [declaredVariables, setDeclaredVariables] = useState<WorkflowVariable[]>([]);

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

    // 设计页不再要求用户理解坐标和连线，但后端草稿结构仍是事实来源；新草稿才加载授信报告起步模板。
    void workflowApi.getDraft(user.tenantId, workflow.id, token)
      .then((detail) => {
        if (cancelled) {
          return;
        }
        const hasPersistedGraph = detail.nodes.length > 0;
        const nextNodes = hasPersistedGraph ? detail.nodes.map(toEditorNode) : cloneStarterNodes();
        const nextEdges = hasPersistedGraph ? detail.edges.map(toEditorEdge) : cloneStarterEdges();
        const nextVariables = detail.variables.length > 0 ? toWorkflowVariables(detail.variables, nextNodes) : buildWorkflowVariables(nextNodes);
        setNodes(nextNodes);
        setEdges(nextEdges);
        setDeclaredVariables(nextVariables);
        setSelectedNodeId(nextNodes[0]?.id ?? "");
        setUsingStarterTemplate(!hasPersistedGraph);
        if (!hasPersistedGraph) {
          setSaveFeedback({ tone: "info", message: "已载入授信报告起步模板，首次保存后写入草稿。" });
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
  const selectedNode = orderedNodes.find((node) => node.id === selectedNodeId) ?? orderedNodes[0];
  const selectedNodeIndex = selectedNode ? orderedNodes.findIndex((node) => node.id === selectedNode.id) : -1;
  const workflowVariables = useMemo(
    () => declaredVariables.length > 0 ? declaredVariables : buildWorkflowVariables(orderedNodes),
    [declaredVariables, orderedNodes],
  );
  const availableVariables = workflowVariables.filter((variable) => {
    const sourceIndex = orderedNodes.findIndex((node) => node.id === variable.sourceNodeId);

    return sourceIndex >= 0 && sourceIndex < selectedNodeIndex;
  });
  const incompleteNodes = orderedNodes.filter((node) => node.data.configStatus === "incomplete");
  const pausePointCount = orderedNodes.filter((node) => node.data.pausePoint).length;
  const matchedNodes = orderedNodes.filter((node) => node.data.label.includes(nodeSearchValue.trim()));
  const nodesByStage = useMemo(() => groupNodesByStage(orderedNodes), [orderedNodes]);
  const selectedStage = selectedNode ? findStageForNode(selectedNode) : workflowStages[0];

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

  async function handleSaveSelectedNode() {
    if (!selectedNode) {
      return;
    }

    // 当前阶段保存的是积木配置摘要，后续接入表单后应只提交被编辑节点的配置补丁并由后端合并。
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
    setNodes(nextNodes);
    await persistGraph(nextNodes, edges);
  }

  function handleSearchLocate() {
    const nextNode = matchedNodes[0];

    if (nextNode) {
      setSelectedNodeId(nextNode.id);
    }
  }

  if (loading) {
    return <EditorStateShell workflowName={workflow.name} onBack={onBack} icon={<Clock3 className="h-5 w-5" aria-hidden="true" />} message="正在加载工作流草稿" />;
  }

  if (loadError || !selectedNode) {
    return (
      <EditorStateShell
        workflowName={workflow.name}
        onBack={onBack}
        icon={<AlertTriangle className="h-5 w-5" aria-hidden="true" />}
        message={loadError || "当前草稿没有可编辑积木"}
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
          <p className="truncate text-xs text-[var(--color-text-tertiary)]">输入内容、智能体协作处理、审查交付</p>
        </div>
        <ToolbarMetric icon={ListChecks} label="积木" value={orderedNodes.length.toString()} />
        <ToolbarMetric icon={CircleDot} label="暂停" value={pausePointCount.toString()} />
        <ToolbarMetric icon={AlertTriangle} label="待配" value={incompleteNodes.length.toString()} tone={incompleteNodes.length > 0 ? "warning" : "default"} />
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
        <button
          type="button"
          onClick={() => setIsConfigCollapsed((current) => !current)}
          className="agent-button h-7 px-2 text-xs"
          title={isConfigCollapsed ? "展开配置" : "收起配置"}
        >
          {isConfigCollapsed ? <PanelRightOpen className="h-3.5 w-3.5" /> : <PanelRightClose className="h-3.5 w-3.5" />}
        </button>
      </div>

      <div className={`grid min-h-0 flex-1 ${isConfigCollapsed ? "xl:grid-cols-[minmax(0,1fr)]" : "xl:grid-cols-[minmax(0,1fr)_340px]"}`}>
        <main className="min-h-0 overflow-y-auto px-4 py-4">
          <div className="grid gap-4 xl:grid-cols-[260px_minmax(0,1fr)]">
            <StageRail
              stages={workflowStages}
              nodesByStage={nodesByStage}
              selectedStageId={selectedStage.id}
              selectedNodeId={selectedNode.id}
              onSelectNode={setSelectedNodeId}
            />
            <section className="space-y-4" aria-label="流程积木编排">
              <WorkflowIntentPanel usingStarterTemplate={usingStarterTemplate} />
              <StageBoard
                stages={workflowStages}
                nodesByStage={nodesByStage}
                selectedNodeId={selectedNode.id}
                onSelectNode={setSelectedNodeId}
              />
              <CollaborationPreview />
              <div className="grid gap-4 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
                <VariableRegistry variables={workflowVariables} />
                <PublishCheckSummary incompleteNodes={incompleteNodes} />
              </div>
            </section>
          </div>
        </main>

        {!isConfigCollapsed ? (
          <div className="min-h-0 overflow-y-auto border-l border-[var(--color-border-light)] bg-[var(--color-bg-card)]">
            <NodeConfigPanel
              node={selectedNode}
              availableVariables={availableVariables}
              insertedVariableName={insertedVariableName}
              onInsertVariable={setInsertedVariableName}
              onSave={handleSaveSelectedNode}
              saving={saving}
              usingStarterTemplate={usingStarterTemplate}
            />
          </div>
        ) : null}
      </div>
    </div>
  );
}

function StageRail({
  stages,
  nodesByStage,
  selectedStageId,
  selectedNodeId,
  onSelectNode,
}: {
  stages: WorkflowStage[];
  nodesByStage: Record<WorkflowStage["id"], WorkflowEditorNode[]>;
  selectedStageId: WorkflowStage["id"];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  return (
    <aside className="space-y-3" aria-label="阶段导航">
      {stages.map((stage, stageIndex) => {
        const Icon = stage.icon;
        const stageNodes = nodesByStage[stage.id];
        const isActive = selectedStageId === stage.id;

        return (
          <section
            key={stage.id}
            className={`rounded-[var(--radius-lg)] border bg-[var(--color-bg-card)] p-3 shadow-[var(--shadow-sm)] ${
              isActive ? "border-[var(--color-primary)]" : "border-[var(--color-border-light)]"
            }`}
          >
            <div className="flex items-start gap-3">
              <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md ${isActive ? "bg-[var(--color-primary)] text-white" : "bg-[var(--color-bg-hover)] text-[var(--color-text-secondary)]"}`}>
                <Icon className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0">
                <p className="text-xs text-[var(--color-text-tertiary)]">阶段 {stageIndex + 1}</p>
                <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{stage.title}</h3>
              </div>
            </div>
            <p className="mt-2 text-xs leading-5 text-[var(--color-text-secondary)]">{stage.subtitle}</p>
            <div className="mt-3 space-y-1.5">
              {stageNodes.map((node) => (
                <button
                  key={node.id}
                  type="button"
                  onClick={() => onSelectNode(node.id)}
                  className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs transition-colors duration-150 ${
                    selectedNodeId === node.id
                      ? "bg-[var(--color-primary)] text-white"
                      : "text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-hover)]"
                  }`}
                >
                  <span className="truncate">{node.data.label}</span>
                  {node.data.configStatus === "incomplete" ? <AlertTriangle className="ml-auto h-3.5 w-3.5 shrink-0" aria-hidden="true" /> : null}
                </button>
              ))}
            </div>
          </section>
        );
      })}
    </aside>
  );
}

function WorkflowIntentPanel({ usingStarterTemplate }: { usingStarterTemplate: boolean }) {
  return (
    <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-4 shadow-[var(--shadow-sm)]">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="max-w-3xl">
          <div className="flex items-center gap-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-md bg-indigo-100 text-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300">
              <FileText className="h-4 w-4" aria-hidden="true" />
            </span>
            <div>
              <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">授信报告协作流</h3>
              <p className="text-xs text-[var(--color-text-tertiary)]">公司输入、数据核验、章节生成、组装审查、文档交付</p>
            </div>
          </div>
          <div className="mt-4 grid gap-3 text-sm md:grid-cols-3">
            <IntentItem title="大模型角色" detail="授信分析、章节撰写、报告组装" />
            <IntentItem title="外部能力" detail="MCP 取数、Skill 风险识别、模板交付" />
            <IntentItem title="用户参与" detail="输入、追问、重新生成、最终确认" />
          </div>
        </div>
        {usingStarterTemplate ? (
          <span className="rounded bg-sky-100 px-2 py-1 text-xs font-medium text-sky-800 dark:bg-sky-900/40 dark:text-sky-300">
            起步模板
          </span>
        ) : null}
      </div>
    </section>
  );
}

function IntentItem({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] px-3 py-2">
      <p className="text-xs text-[var(--color-text-tertiary)]">{title}</p>
      <p className="mt-1 font-medium text-[var(--color-text-primary)]">{detail}</p>
    </div>
  );
}

function StageBoard({
  stages,
  nodesByStage,
  selectedNodeId,
  onSelectNode,
}: {
  stages: WorkflowStage[];
  nodesByStage: Record<WorkflowStage["id"], WorkflowEditorNode[]>;
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  return (
    <div className="space-y-4">
      {stages.map((stage) => {
        const Icon = stage.icon;
        const stageNodes = nodesByStage[stage.id];

        return (
          <section key={stage.id} className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-4 shadow-[var(--shadow-sm)]" aria-labelledby={`stage-${stage.id}`}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <span className="flex h-9 w-9 items-center justify-center rounded-md bg-[var(--color-bg-hover)] text-[var(--color-primary)]">
                  <Icon className="h-4 w-4" aria-hidden="true" />
                </span>
                <div>
                  <h3 id={`stage-${stage.id}`} className="text-base font-semibold text-[var(--color-text-primary)]">{stage.title}</h3>
                  <p className="text-xs text-[var(--color-text-tertiary)]">{stage.subtitle}</p>
                </div>
              </div>
              <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)]">
                {stageNodes.length} 个积木
              </span>
            </div>
            <div className="grid gap-3 lg:grid-cols-2 2xl:grid-cols-3">
              {stageNodes.map((node) => (
                <WorkflowBlock
                  key={node.id}
                  node={node}
                  selected={selectedNodeId === node.id}
                  onSelect={() => onSelectNode(node.id)}
                />
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function WorkflowBlock({ node, selected, onSelect }: { node: WorkflowEditorNode; selected: boolean; onSelect: () => void }) {
  const meta = nodeTypeMeta[node.data.nodeType];
  const Icon = meta.icon;
  const interaction = getInteractionPolicy(node);

  return (
    <button
      type="button"
      onClick={onSelect}
      className={`flex h-full min-h-[188px] flex-col rounded-[var(--radius-lg)] border bg-[var(--color-bg-card)] p-3 text-left transition duration-150 ${
        selected ? "border-[var(--color-primary)] shadow-[0_16px_36px_rgba(79,70,229,0.18)]" : "border-[var(--color-border-light)] hover:border-[var(--color-primary)]"
      }`}
    >
      <div className="flex items-start gap-3">
        <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-md ${meta.accentClass}`}>
          <Icon className="h-5 w-5" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{node.data.label}</p>
          <p className="mt-0.5 text-xs text-[var(--color-text-tertiary)]">{node.data.typeLabel}</p>
        </div>
      </div>
      <p className="mt-3 line-clamp-3 text-xs leading-5 text-[var(--color-text-secondary)]">{node.data.summary}</p>
      <div className="mt-auto space-y-3 pt-3">
        <div className="flex flex-wrap gap-1.5">
          <TinyBadge>{node.data.runState}</TinyBadge>
          {node.data.pausePoint ? <TinyBadge tone="warning">暂停点</TinyBadge> : null}
          {node.data.toolCount > 0 ? <TinyBadge tone="info">MCP {node.data.toolCount}</TinyBadge> : null}
          {node.data.allowQuestion ? <TinyBadge tone="success">可追问</TinyBadge> : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <ActionPill icon={RefreshCw} label="重新生成" enabled={interaction.canRegenerate} />
          <ActionPill icon={MessageSquareText} label="追问修改" enabled={interaction.canAskFollowUp} />
        </div>
        <StatusBadge complete={node.data.configStatus === "complete"} compact />
      </div>
    </button>
  );
}

function CollaborationPreview() {
  return (
    <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-4 shadow-[var(--shadow-sm)]" aria-labelledby="collaboration-preview-title">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 id="collaboration-preview-title" className="text-base font-semibold text-[var(--color-text-primary)]">智能体协作台</h3>
          <p className="text-xs text-[var(--color-text-tertiary)]">数据取数、章节生成和报告组装分开执行</p>
        </div>
        <span className="rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300">并行优先</span>
      </div>
      <div className="mt-4 grid gap-3 lg:grid-cols-4">
        {parallelTasks.map((task) => {
          const Icon = task.mode === "数据采集" ? Database : task.mode === "章节生成" ? FileText : GitMerge;

          return (
            <article key={task.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
              <div className="flex items-center gap-2">
                <span className="flex h-8 w-8 items-center justify-center rounded-md bg-[var(--color-bg-card)] text-[var(--color-primary)] ring-1 ring-[var(--color-border-light)]">
                  <Icon className="h-4 w-4" aria-hidden="true" />
                </span>
                <div className="min-w-0">
                  <h4 className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{task.name}</h4>
                  <p className="text-xs text-[var(--color-text-tertiary)]">{task.mode}</p>
                </div>
              </div>
              <p className="mt-3 text-xs leading-5 text-[var(--color-text-secondary)]">{task.purpose}</p>
              <p className="mt-2 truncate text-[11px] text-[var(--color-text-tertiary)]">能力：{task.assignee}</p>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function NodeConfigPanel({
  node,
  availableVariables,
  insertedVariableName,
  onInsertVariable,
  onSave,
  saving,
  usingStarterTemplate,
}: {
  node: WorkflowEditorNode;
  availableVariables: WorkflowVariable[];
  insertedVariableName: string;
  onInsertVariable: (variableName: string) => void;
  onSave: () => Promise<void>;
  saving: boolean;
  usingStarterTemplate: boolean;
}) {
  const meta = nodeTypeMeta[node.data.nodeType];
  const Icon = meta.icon;
  const interaction = getInteractionPolicy(node);

  return (
    <aside aria-labelledby="node-config-title">
      <div className="px-4 pb-2 pt-4">
        <div className="flex items-center gap-3">
          <span className={`flex h-10 w-10 items-center justify-center rounded-lg ${meta.accentClass}`}>
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <div>
            <p className="text-xs text-[var(--color-text-tertiary)]">{node.data.typeLabel}</p>
            <h3 id="node-config-title" className="text-base font-semibold text-[var(--color-text-primary)]">
              {node.data.label}
            </h3>
          </div>
        </div>
      </div>

      <div className="space-y-4 p-4">
        <PanelGroup title="业务定位">
          <p className="agent-muted text-sm leading-6">{node.data.summary}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <StatusBadge complete={node.data.configStatus === "complete"} />
            {node.data.pausePoint ? <TinyBadge tone="warning">写入暂停点</TinyBadge> : null}
          </div>
          {usingStarterTemplate ? (
            <p className="mt-3 rounded bg-sky-50 px-2 py-1 text-xs font-medium text-sky-800 dark:bg-sky-950/40 dark:text-sky-200">
              当前为起步模板，保存后写入真实草稿。
            </p>
          ) : null}
        </PanelGroup>

        <PanelGroup title="输入变量">
          <VariableList variables={node.data.inputVariables} emptyText="该积木不需要上游输入" />
        </PanelGroup>

        <PanelGroup title="可引用变量">
          <VariableList
            variables={availableVariables.map((variable) => variable.name)}
            emptyText="当前积木前没有可引用变量"
            onInsertVariable={onInsertVariable}
          />
          {insertedVariableName ? (
            <p className="mt-3 rounded bg-indigo-50 px-2 py-1 text-xs font-medium text-indigo-800 dark:bg-indigo-950/40 dark:text-indigo-200">
              已插入变量占位：{"{{"}{insertedVariableName}{"}}"}
            </p>
          ) : null}
        </PanelGroup>

        <PanelGroup title="输出变量">
          <VariableList variables={node.data.outputVariables} emptyText="暂未声明输出变量" />
        </PanelGroup>

        <PanelGroup title="核心配置">
          <NodeTypeConfig node={node} />
        </PanelGroup>

        {node.data.nodeType === "parallel_group" ? (
          <PanelGroup title={node.id.includes("chapter") ? "章节智能体" : "并行子任务"}>
            <ParallelTaskList tasks={node.id.includes("chapter") ? parallelTasks.filter((task) => task.mode === "章节生成") : parallelTasks.filter((task) => task.mode === "数据采集")} />
          </PanelGroup>
        ) : null}

        {node.data.nodeType === "merge" ? (
          <PanelGroup title="组装映射">
            <MergeMappingList mappings={mergeMappings} />
          </PanelGroup>
        ) : null}

        <PanelGroup title="能力装配">
          <ConfigRows
            rows={[
              ["Skills", node.data.nodeType === "agent" || node.data.nodeType === "merge" || node.data.nodeType === "parallel_group" ? "授信分析、风险识别、报告撰写" : "按积木类型隐藏"],
              ["MCP", node.data.toolCount > 0 ? `${node.data.toolCount} 个工具已启用` : "未启用外部工具"],
              ["提示词模板", node.data.nodeType === "merge" ? "授信报告组装模板" : node.data.nodeType === "agent" ? "授信追问模板" : "按积木类型选择"],
            ]}
          />
        </PanelGroup>

        <PanelGroup title="用户交互">
          <div className="space-y-3">
            <ConfigRows
              rows={[
                ["输出模式", node.data.outputMode],
                ["重新生成", interaction.canRegenerate ? "允许" : "不允许"],
                ["追问修改", interaction.canAskFollowUp ? "允许" : "不允许"],
                ["暂停策略", node.data.pausePoint ? "等待用户确认后继续" : "自动进入下游"],
              ]}
            />
            <div className="grid grid-cols-2 gap-2">
              <button type="button" disabled={!interaction.canRegenerate} className="agent-button h-9 px-2 text-xs">
                <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                重新生成
              </button>
              <button type="button" disabled={!interaction.canAskFollowUp} className="agent-button h-9 px-2 text-xs">
                <MessageSquareText className="h-3.5 w-3.5" aria-hidden="true" />
                追问修改
              </button>
            </div>
          </div>
        </PanelGroup>

        <PanelGroup title="权限与审计">
          <ConfigRows
            rows={[
              ["权限校验", node.data.toolCount > 0 || node.data.pausePoint ? "后端按租户与能力池复核" : "基础读取"],
              ["审计事件", node.data.toolCount > 0 ? "记录工具调用与脱敏摘要" : "记录状态变更"],
            ]}
          />
        </PanelGroup>

        <button
          type="button"
          onClick={() => void onSave()}
          disabled={saving}
          className="agent-button agent-button-primary h-10 w-full px-3 text-sm"
        >
          <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
          {saving ? "保存中" : "保存积木配置"}
        </button>
      </div>
    </aside>
  );
}

function NodeTypeConfig({ node }: { node: WorkflowEditorNode }) {
  if (node.data.nodeType === "agent") {
    return (
      <ConfigRows
        rows={[
          ["智能体模板", "授信任务规划智能体"],
          ["模型配置", "租户默认模型"],
          ["追问条件", "信息缺失或口径冲突"],
          ["输出 Schema", "credit_work_plan、missing_questions"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "parallel_group" && node.id.includes("chapter")) {
    return (
      <ConfigRows
        rows={[
          ["执行方式", "多章节智能体并发"],
          ["章节范围", "主体概况、财务分析、风险判断、授信建议"],
          ["失败策略", "单章节可重试"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "parallel_group") {
    return (
      <ConfigRows
        rows={[
          ["执行方式", "多个 MCP / 子智能体并发"],
          ["数据规则", "事实类结果只允许重新获取"],
          ["失败策略", "单项失败可重试"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "merge") {
    return (
      <ConfigRows
        rows={[
          ["组装方式", "模板组装 + 报告组装智能体"],
          ["冲突处理", "保留来源并交给用户审查"],
          ["输出变量", "credit_report_draft"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "human_review") {
    return (
      <ConfigRows
        rows={[
          ["审核对象", "发起人 / 授信审核人"],
          ["审核动作", "确认交付、退回重做"],
          ["文本调整", "模型内容可追问修改"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "delivery") {
    return (
      <ConfigRows
        rows={[
          ["交付方式", "Word / PDF"],
          ["交付确认", "用户确认后生成正式件"],
          ["失败策略", "失败后可重试"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "user_input") {
    return (
      <ConfigRows
        rows={[
          ["核心字段", "授信公司全称"],
          ["补充字段", "授信用途、金额、期限"],
          ["附件", "允许上传"],
        ]}
      />
    );
  }

  return (
    <ConfigRows
      rows={[
        ["配置状态", node.data.configStatus === "complete" ? "已满足当前字段" : "需要补齐字段"],
        ["节点类型", node.data.typeLabel],
      ]}
    />
  );
}

function ParallelTaskList({ tasks }: { tasks: ParallelTask[] }) {
  return (
    <div className="space-y-2">
      {tasks.map((task) => (
        <article key={task.name} className="rounded bg-[var(--color-bg-card)] px-2 py-2 ring-1 ring-[var(--color-border-light)]">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h5 className="text-xs font-semibold text-[var(--color-text-primary)]">{task.name}</h5>
            <span className="rounded bg-emerald-100 px-2 py-0.5 text-[11px] font-medium text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300">{task.output}</span>
          </div>
          <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{task.purpose}</p>
          <p className="mt-1 text-[11px] text-[var(--color-text-tertiary)]">执行能力：{task.assignee}</p>
        </article>
      ))}
    </div>
  );
}

function MergeMappingList({ mappings }: { mappings: MergeMapping[] }) {
  return (
    <div className="space-y-2">
      {mappings.map((mapping) => (
        <article key={mapping.target} className="rounded bg-[var(--color-bg-card)] px-2 py-2 ring-1 ring-[var(--color-border-light)]">
          <div className="grid gap-1 text-xs md:grid-cols-[minmax(0,0.9fr)_minmax(0,1fr)]">
            <span className="font-medium text-[var(--color-text-secondary)]">{mapping.source}</span>
            <span className="font-semibold text-[var(--color-text-primary)]">{mapping.target}</span>
          </div>
          <p className="mt-1 text-[11px] leading-5 text-[var(--color-text-tertiary)]">{mapping.rule}</p>
        </article>
      ))}
    </div>
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
      <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">{title}</h4>
      <div className="mt-2">{children}</div>
    </section>
  );
}

function VariableList({
  variables,
  emptyText,
  onInsertVariable,
}: {
  variables: string[];
  emptyText: string;
  onInsertVariable?: (variableName: string) => void;
}) {
  if (variables.length === 0) {
    return <p className="text-sm text-[var(--color-text-tertiary)]">{emptyText}</p>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {variables.map((variable) => (
        <button
          key={variable}
          type="button"
          onClick={() => onInsertVariable?.(variable)}
          className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)] transition-colors duration-200 hover:text-[var(--color-primary)]"
          title={onInsertVariable ? "点击插入变量占位符" : undefined}
        >
          {variable}
        </button>
      ))}
    </div>
  );
}

function VariableRegistry({ variables }: { variables: WorkflowVariable[] }) {
  return (
    <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-4 shadow-[var(--shadow-sm)]" aria-labelledby="variable-title">
      <div className="flex items-center gap-2">
        <Wrench className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
        <h3 id="variable-title" className="text-sm font-semibold text-[var(--color-text-primary)]">变量声明</h3>
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        {variables.slice(0, 8).map((variable) => (
          <article key={variable.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
            <div className="flex items-center justify-between gap-3">
              <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{variable.name}</p>
              <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">{variable.type}</span>
            </div>
            <p className="mt-2 truncate text-xs text-[var(--color-text-tertiary)]">来源：{variable.sourceNodeName}</p>
            {variable.sensitive ? <p className="mt-2 rounded bg-red-50 px-2 py-1 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-300">敏感变量</p> : null}
          </article>
        ))}
      </div>
    </section>
  );
}

function PublishCheckSummary({ incompleteNodes }: { incompleteNodes: WorkflowEditorNode[] }) {
  return (
    <section className="rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] p-4 shadow-[var(--shadow-sm)]" aria-labelledby="publish-check-title">
      <div className="flex items-center gap-2">
        <ShieldCheck className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
        <h3 id="publish-check-title" className="text-sm font-semibold text-[var(--color-text-primary)]">发布校验摘要</h3>
      </div>
      <div className="mt-3 space-y-3">
        {incompleteNodes.length > 0 ? (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 dark:border-amber-900/60 dark:bg-amber-950/30">
            <div className="flex items-center gap-2 text-sm font-medium text-amber-800 dark:text-amber-300">
              <AlertTriangle className="h-4 w-4" aria-hidden="true" />
              {incompleteNodes.length} 个积木需要补齐配置
            </div>
            <ul className="mt-3 space-y-2 text-sm text-amber-800 dark:text-amber-300">
              {incompleteNodes.map((node) => (
                <li key={node.id}>- {node.data.label}</li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-medium text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300">
            当前积木已满足前端摘要校验。
          </div>
        )}
        <p className="agent-muted text-sm leading-6">正式发布仍由后端校验节点、变量、连线和版本快照。</p>
      </div>
    </section>
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

function ActionPill({ icon: Icon, label, enabled }: { icon: typeof RefreshCw; label: string; enabled: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1 rounded px-2 py-1 text-[11px] font-medium ${enabled ? "bg-[var(--color-bg-hover)] text-[var(--color-text-secondary)]" : "bg-[var(--color-bg-hover)] text-[var(--color-text-tertiary)] opacity-60"}`}>
      <Icon className="h-3 w-3" aria-hidden="true" />
      {label}
    </span>
  );
}

function StatusBadge({ complete, compact = false }: { complete: boolean; compact?: boolean }) {
  if (complete) {
    return (
      <span className={`inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-1 font-medium text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300 ${compact ? "text-[11px]" : "text-xs"}`}>
        <CheckCircle2 className={compact ? "h-3 w-3" : "h-3.5 w-3.5"} aria-hidden="true" />
        配置完整
      </span>
    );
  }

  return <span className={`rounded bg-red-100 px-2 py-1 font-medium text-red-700 dark:bg-red-950/40 dark:text-red-300 ${compact ? "text-[11px]" : "text-xs"}`}>待补配置</span>;
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
  return starterNodes.map((node) => ({
    ...node,
    position: { ...node.position },
    data: {
      ...node.data,
      inputVariables: [...node.data.inputVariables],
      outputVariables: [...node.data.outputVariables],
      rawConfig: { ...(node.data.rawConfig ?? {}) },
    },
  }));
}

function cloneStarterEdges(): WorkflowEditorEdge[] {
  return starterEdges.map((edge) => ({ ...edge }));
}

function toEditorNode(node: WorkflowNodeDraft): WorkflowEditorNode {
  const preset = starterNodes.find((starter) => starter.data.nodeType === node.nodeType)?.data ?? buildFallbackNodeData(node.nodeType);
  const config = node.config ?? {};

  return {
    id: node.nodeId,
    position: { x: node.positionX, y: node.positionY },
    data: {
      label: node.name,
      typeLabel: readString(config.typeLabel, preset.typeLabel),
      nodeType: node.nodeType,
      summary: readString(config.summary, preset.summary),
      inputVariables: [...(node.inputVariables ?? [])],
      outputVariables: [...(node.outputVariables ?? [])],
      pausePoint: readBoolean(config.pausePoint, preset.pausePoint),
      configStatus: readLiteral(config.configStatus, ["complete", "incomplete"], preset.configStatus),
      runState: readLiteral(config.runState, ["未开始", "等待输入", "执行中", "等待审核", "已完成", "待配置"], preset.runState),
      outputMode: readLiteral(config.outputMode, ["一次性输出", "追问确认", "分析后暂停"], preset.outputMode),
      toolCount: readNumber(config.toolCount, preset.toolCount),
      allowQuestion: readBoolean(config.allowQuestion, preset.allowQuestion),
      rawConfig: { ...config },
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
  const nextNodes = detail.nodes.map(toEditorNode);
  const nextEdges = detail.edges.map(toEditorEdge);
  setNodes(nextNodes);
  setEdges(nextEdges);
  setSelectedNodeId((currentSelection) => nextNodes.some((node) => node.id === currentSelection) ? currentSelection : nextNodes[0]?.id ?? "");
}

function buildWorkflowVariables(nodes: WorkflowEditorNode[]): WorkflowVariable[] {
  return nodes.flatMap((node) =>
    node.data.outputVariables.map((name) => {
      const metadata = starterVariableMetadata[name] ?? {
        type: "string" as const,
        sensitive: false,
        deliverable: false,
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

function buildFallbackNodeData(nodeType: WorkflowNodeType): EditorNodeData {
  return {
    label: "未命名积木",
    typeLabel: nodeTypeLabels[nodeType],
    nodeType,
    summary: "积木配置尚未补充说明。",
    inputVariables: [],
    outputVariables: [],
    pausePoint: ["user_input", "agent", "human_review"].includes(nodeType),
    configStatus: "incomplete",
    runState: "待配置",
    outputMode: "一次性输出",
    toolCount: 0,
    allowQuestion: false,
  };
}

function orderNodesByEdges(nodes: WorkflowEditorNode[], edges: WorkflowEditorEdge[]) {
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const incomingTargets = new Set(edges.map((edge) => edge.target));
  const startNode = nodes.find((node) => !incomingTargets.has(node.id)) ?? nodes[0];
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

function groupNodesByStage(nodes: WorkflowEditorNode[]): Record<WorkflowStage["id"], WorkflowEditorNode[]> {
  return workflowStages.reduce((groups, stage) => {
    groups[stage.id] = nodes.filter((node) => stage.nodeTypes.includes(node.data.nodeType));
    return groups;
  }, { input: [], agent: [], review: [] } as Record<WorkflowStage["id"], WorkflowEditorNode[]>);
}

function findStageForNode(node: WorkflowEditorNode): WorkflowStage {
  return workflowStages.find((stage) => stage.nodeTypes.includes(node.data.nodeType)) ?? workflowStages[0];
}

function getInteractionPolicy(node: WorkflowEditorNode) {
  const isDataNode = node.data.nodeType === "parallel_group" && !node.id.includes("chapter");
  const isModelNode = node.data.nodeType === "agent" || node.data.nodeType === "merge" || node.id.includes("chapter");

  return {
    canRegenerate: node.data.nodeType !== "trigger" && node.data.nodeType !== "delivery",
    canAskFollowUp: node.data.allowQuestion && isModelNode && !isDataNode,
  };
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

function getWorkflowEditorErrorContext(error: unknown, tenantId?: string, workflowId?: string) {
  if (error instanceof AgentumApiError) {
    return { code: error.code, requestId: error.requestId, tenantId, workflowId };
  }

  return { message: error instanceof Error ? error.message : "unknown", tenantId, workflowId };
}
