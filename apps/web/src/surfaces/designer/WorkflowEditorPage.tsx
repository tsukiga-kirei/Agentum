import type { MouseEvent, ReactNode } from "react";
import { useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  Handle,
  MiniMap,
  Node,
  NodeProps,
  Position,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ChevronLeft,
  GitMerge,
  Milestone,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  PackageCheck,
  Route,
  Search,
  ShieldCheck,
  Split,
  TextCursorInput,
  Zap,
} from "lucide-react";
import { WorkflowDraft } from "./WorkflowDraftsPage";

type WorkflowNodeType =
  | "trigger"
  | "user_input"
  | "agent"
  | "parallel_group"
  | "merge"
  | "condition"
  | "human_review"
  | "delivery";

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
};

type WorkflowVariable = {
  name: string;
  sourceNode: string;
  type: "string" | "object" | "array" | "boolean" | "decision" | "file";
  sensitive: boolean;
};

type WorkflowEditorPageProps = {
  workflow: WorkflowDraft;
  onBack: () => void;
};

const nodeTypeMeta: Record<WorkflowNodeType, { color: string; icon: typeof Zap }> = {
  trigger: {
    color: "#71717a",
    icon: Zap,
  },
  user_input: {
    color: "#f59e0b",
    icon: TextCursorInput,
  },
  agent: {
    color: "#4f46e5",
    icon: Bot,
  },
  parallel_group: {
    color: "#059669",
    icon: Split,
  },
  merge: {
    color: "#7c3aed",
    icon: GitMerge,
  },
  condition: {
    color: "#ca8a04",
    icon: Route,
  },
  human_review: {
    color: "#dc2626",
    icon: ShieldCheck,
  },
  delivery: {
    color: "#ea580c",
    icon: PackageCheck,
  },
};

const nodeTypes = {
  workflow: WorkflowCanvasNode,
};

// 画布先使用固定节点样例验证交互结构，后续保存时应替换为 WorkflowDefinition.nodes。
const initialNodes: Node<EditorNodeData>[] = [
  {
    id: "trigger_manual",
    type: "workflow",
    position: { x: 40, y: 180 },
    data: {
      label: "手动触发",
      typeLabel: "触发节点",
      nodeType: "trigger",
      summary: "由业务人员手动发起流程。",
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
    id: "input_materials",
    type: "workflow",
    position: { x: 300, y: 110 },
    data: {
      label: "补充业务材料",
      typeLabel: "用户输入节点",
      nodeType: "user_input",
      summary: "收集需求背景、附件和期望交付物。",
      inputVariables: ["starter"],
      outputVariables: ["project_info", "attachments"],
      pausePoint: true,
      configStatus: "complete",
      runState: "等待输入",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
    },
  },
  {
    id: "agent_analysis",
    type: "workflow",
    position: { x: 600, y: 110 },
    data: {
      label: "智能体分析",
      typeLabel: "智能体节点",
      nodeType: "agent",
      summary: "调用需求分析智能体生成范围、风险和追问建议。",
      inputVariables: ["project_info", "attachments"],
      outputVariables: ["analysis_result", "risk_level"],
      pausePoint: true,
      configStatus: "incomplete",
      runState: "待配置",
      outputMode: "分析后暂停",
      toolCount: 2,
      allowQuestion: true,
    },
  },
  {
    id: "parallel_collect",
    type: "workflow",
    position: { x: 900, y: 110 },
    data: {
      label: "并行获取数据",
      typeLabel: "并行节点组",
      nodeType: "parallel_group",
      summary: "并行执行资料核验、知识库检索和外部数据查询。",
      inputVariables: ["analysis_result"],
      outputVariables: ["research_pack"],
      pausePoint: false,
      configStatus: "complete",
      runState: "执行中",
      outputMode: "一次性输出",
      toolCount: 3,
      allowQuestion: false,
    },
  },
  {
    id: "merge_report",
    type: "workflow",
    position: { x: 1180, y: 110 },
    data: {
      label: "合并组装报告",
      typeLabel: "合并节点",
      nodeType: "merge",
      summary: "把并行结果合并为结构化报告草稿。",
      inputVariables: ["research_pack", "analysis_result"],
      outputVariables: ["report_draft"],
      pausePoint: false,
      configStatus: "complete",
      runState: "未开始",
      outputMode: "一次性输出",
      toolCount: 1,
      allowQuestion: false,
    },
  },
  {
    id: "condition_risk",
    type: "workflow",
    position: { x: 1460, y: 110 },
    data: {
      label: "风险判断",
      typeLabel: "条件分支节点",
      nodeType: "condition",
      summary: "根据 risk_level 判断是否需要人工审核。",
      inputVariables: ["risk_level"],
      outputVariables: ["review_required"],
      pausePoint: false,
      configStatus: "complete",
      runState: "未开始",
      outputMode: "一次性输出",
      toolCount: 0,
      allowQuestion: false,
    },
  },
  {
    id: "human_review",
    type: "workflow",
    position: { x: 1740, y: 40 },
    data: {
      label: "人工审核",
      typeLabel: "人工审核节点",
      nodeType: "human_review",
      summary: "高风险流程等待负责人审核并可修改结论。",
      inputVariables: ["report_draft", "risk_level"],
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
    id: "delivery_email",
    type: "workflow",
    position: { x: 2020, y: 110 },
    data: {
      label: "邮件交付",
      typeLabel: "交付节点",
      nodeType: "delivery",
      summary: "把审核后的报告发送给指定收件人并记录交付结果。",
      inputVariables: ["report_draft", "review_decision"],
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

// 边关系对应工作流 MVP 主链路，后续应由 WorkflowDefinition.edges 持久化。
const initialEdges: Edge[] = [
  { id: "e_trigger_input", source: "trigger_manual", target: "input_materials", type: "smoothstep" },
  { id: "e_input_agent", source: "input_materials", target: "agent_analysis", type: "smoothstep" },
  { id: "e_agent_parallel", source: "agent_analysis", target: "parallel_collect", type: "smoothstep" },
  { id: "e_parallel_merge", source: "parallel_collect", target: "merge_report", type: "smoothstep" },
  { id: "e_merge_condition", source: "merge_report", target: "condition_risk", type: "smoothstep" },
  { id: "e_condition_review", source: "condition_risk", target: "human_review", label: "高风险", type: "smoothstep" },
  { id: "e_review_delivery", source: "human_review", target: "delivery_email", type: "smoothstep" },
  { id: "e_condition_delivery", source: "condition_risk", target: "delivery_email", label: "低风险", type: "smoothstep" },
];

// 变量清单由节点输出推导而来，后续应改为读取后端 VariableSnapshot 和契约校验结果。
const workflowVariables: WorkflowVariable[] = [
  { name: "starter", sourceNode: "手动触发", type: "string", sensitive: false },
  { name: "started_at", sourceNode: "手动触发", type: "string", sensitive: false },
  { name: "project_info", sourceNode: "补充业务材料", type: "object", sensitive: false },
  { name: "attachments", sourceNode: "补充业务材料", type: "file", sensitive: true },
  { name: "analysis_result", sourceNode: "智能体分析", type: "object", sensitive: false },
  { name: "risk_level", sourceNode: "智能体分析", type: "decision", sensitive: false },
  { name: "research_pack", sourceNode: "并行获取数据", type: "object", sensitive: false },
  { name: "report_draft", sourceNode: "合并组装报告", type: "object", sensitive: false },
  { name: "review_required", sourceNode: "风险判断", type: "boolean", sensitive: false },
  { name: "review_decision", sourceNode: "人工审核", type: "decision", sensitive: false },
  { name: "delivery_record", sourceNode: "邮件交付", type: "object", sensitive: false },
];

export function WorkflowEditorPage({ workflow, onBack }: WorkflowEditorPageProps) {
  // 节点列表先保存在组件状态中，方便验证本地配置保存；后续会改为调用工作流草稿保存 API。
  const [nodes, setNodes] = useState(initialNodes);
  const [selectedNodeId, setSelectedNodeId] = useState(initialNodes[0].id);
  const [isOutlineCollapsed, setIsOutlineCollapsed] = useState(false);
  const [isConfigCollapsed, setIsConfigCollapsed] = useState(false);
  const [nodeSearchValue, setNodeSearchValue] = useState("");
  const [insertedVariableName, setInsertedVariableName] = useState("");

  const decoratedNodes = useMemo(() => {
    return nodes.map((node) => {
      const meta = nodeTypeMeta[node.data.nodeType];

      return {
        ...node,
        style: {
          border: "none",
          borderRadius: 12,
          boxShadow: selectedNodeId === node.id ? "0 14px 32px rgb(79 70 229 / 0.22)" : "0 6px 18px rgb(15 23 42 / 0.08)",
          minWidth: 220,
          padding: 0,
        },
      };
    });
  }, [nodes, selectedNodeId]);

  const selectedNode = decoratedNodes.find((node) => node.id === selectedNodeId) ?? decoratedNodes[0];
  const incompleteNodes = decoratedNodes.filter((node) => node.data.configStatus === "incomplete");
  const selectedNodeIndex = decoratedNodes.findIndex((node) => node.id === selectedNode.id);
  const availableVariables = workflowVariables.filter((variable) => {
    const sourceIndex = decoratedNodes.findIndex((node) => node.data.label === variable.sourceNode);

    return sourceIndex >= 0 && sourceIndex < selectedNodeIndex;
  });
  const matchedNodes = decoratedNodes.filter((node) => node.data.label.includes(nodeSearchValue.trim()));

  function handleSaveSelectedNode() {
    // 当前保存只更新前端配置状态，后续应提交 NodeDefinition.config 并接入 Zod 表单校验。
    setNodes((currentNodes) =>
      currentNodes.map((node) => {
        if (node.id !== selectedNode.id) {
          return node;
        }

        return {
          ...node,
          data: {
            ...node.data,
            configStatus: "complete",
          },
        };
      }),
    );
  }

  const handleNodeClick = (_event: MouseEvent, node: Node<EditorNodeData>) => {
    // 节点选中态驱动右侧配置面板，后续接入表单保存时也以该 id 定位 NodeDefinition。
    setSelectedNodeId(node.id);
  };

  function handleSearchLocate() {
    const nextNode = matchedNodes[0];

    if (nextNode) {
      // 搜索只负责定位选中态，后续接入自动布局后再同步控制 React Flow viewport。
      setSelectedNodeId(nextNode.id);
    }
  }
  const editorGridClass =
    isOutlineCollapsed && isConfigCollapsed
      ? "xl:grid-cols-[minmax(0,1fr)]"
      : isOutlineCollapsed
        ? "xl:grid-cols-[minmax(0,1fr)_320px]"
        : isConfigCollapsed
          ? "xl:grid-cols-[220px_minmax(0,1fr)]"
          : "xl:grid-cols-[220px_minmax(0,1fr)_320px]";

  return (
    <div className="mx-auto max-w-[1800px] space-y-3 px-4 py-4 lg:px-5">
      <div className="agent-card flex flex-col gap-3 p-3.5 xl:flex-row xl:items-center xl:justify-between">
        <div className="min-w-0">
          <button
            type="button"
            onClick={onBack}
            className="agent-button mb-2 h-8 px-3 text-sm"
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            返回列表
          </button>
          <p className="text-sm font-medium text-[var(--color-primary)]">工作流编辑器</p>
          <h2 className="mt-1 text-xl font-semibold text-[var(--color-text-primary)]">{workflow.name}</h2>
          <p className="agent-muted mt-2 max-w-3xl text-sm leading-6">{workflow.description}</p>
        </div>
        <div className="grid gap-2 sm:grid-cols-3">
          <EditorMetric label="节点" value={String(nodes.length)} />
          <EditorMetric label="暂停点" value={String(nodes.filter((node) => node.data.pausePoint).length)} />
          <EditorMetric label="待配置" value={String(nodes.filter((node) => node.data.configStatus === "incomplete").length)} />
        </div>
      </div>

      <div className="agent-card flex flex-wrap items-center justify-between gap-3 px-4 py-2.5">
        <div>
          <p className="text-sm font-semibold text-[var(--color-text-primary)]">画布工作区</p>
          <p className="agent-muted mt-1 text-xs">以画布为主，可按需收起大纲和配置面板；支持滚轮缩放与拖拽平移。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <label className="relative block w-64">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-tertiary)]" aria-hidden="true" />
            <span className="sr-only">搜索节点</span>
            <input
              value={nodeSearchValue}
              onChange={(event) => setNodeSearchValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  handleSearchLocate();
                }
              }}
              className="agent-input h-9 w-full pl-9 pr-3 text-sm outline-none"
              placeholder="搜索节点并定位"
            />
          </label>
          <button
            type="button"
            onClick={() => setIsOutlineCollapsed((current) => !current)}
            className="agent-button h-9 px-3 text-sm"
          >
            {isOutlineCollapsed ? <PanelLeftOpen className="h-4 w-4" aria-hidden="true" /> : <PanelLeftClose className="h-4 w-4" aria-hidden="true" />}
            {isOutlineCollapsed ? "展开大纲" : "收起大纲"}
          </button>
          <button
            type="button"
            onClick={() => setIsConfigCollapsed((current) => !current)}
            className="agent-button h-9 px-3 text-sm"
          >
            {isConfigCollapsed ? <PanelRightOpen className="h-4 w-4" aria-hidden="true" /> : <PanelRightClose className="h-4 w-4" aria-hidden="true" />}
            {isConfigCollapsed ? "展开配置" : "收起配置"}
          </button>
        </div>
      </div>

      <div className={`grid gap-3 ${editorGridClass}`}>
        {!isOutlineCollapsed ? (
        <section className="agent-card" aria-labelledby="outline-title">
          <div className="px-3 pb-2 pt-3">
            <h3 id="outline-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
              流程大纲
            </h3>
            <p className="agent-muted mt-1 text-xs">快速定位节点和配置状态</p>
          </div>
          <div className="space-y-2 p-2.5">
            {decoratedNodes.map((node, index) => {
              const meta = nodeTypeMeta[node.data.nodeType];
              const Icon = meta.icon;
              const isActive = selectedNodeId === node.id;

              return (
                <button
                  key={node.id}
                  type="button"
                  onClick={() => setSelectedNodeId(node.id)}
                  className={`flex w-full items-start gap-2.5 rounded-lg border px-2.5 py-2.5 text-left transition-colors duration-200 ${
                    isActive ? "border-[var(--color-primary)] bg-[var(--color-primary)] text-white" : "border-[var(--color-border-light)] bg-[var(--color-bg-card)] text-[var(--color-text-primary)] hover:bg-[var(--color-bg-hover)]"
                  }`}
                >
                  <span
                    className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded ${
                      isActive ? "bg-white/15 text-white" : "bg-[var(--color-bg-hover)]"
                    }`}
                  >
                    <Icon className="h-4 w-4" aria-hidden="true" />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-xs font-medium">
                      {index + 1}. {node.data.label}
                    </span>
                    <span className={`mt-1 block text-xs ${isActive ? "text-indigo-100" : "text-[var(--color-text-tertiary)]"}`}>
                      {node.data.typeLabel}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
        </section>
        ) : null}

        <section className="agent-card min-h-[760px] overflow-hidden xl:h-[calc(100vh-196px)]" aria-label="工作流画布">
          <ReactFlow
            nodes={decoratedNodes}
            edges={initialEdges}
            nodeTypes={nodeTypes}
            onNodeClick={handleNodeClick}
            defaultViewport={{ x: 26, y: 145, zoom: 0.78 }}
            nodesDraggable={false}
            panOnScroll
            zoomOnScroll
            zoomOnPinch
            minZoom={0.25}
            maxZoom={2.2}
          >
            <Background color="#cbd5e1" gap={18} />
            <Controls />
            <MiniMap nodeStrokeWidth={3} zoomable pannable />
          </ReactFlow>
        </section>

        {!isConfigCollapsed ? (
          <NodeConfigPanel
            node={selectedNode}
            availableVariables={availableVariables}
            insertedVariableName={insertedVariableName}
            onInsertVariable={setInsertedVariableName}
            onSave={handleSaveSelectedNode}
          />
        ) : null}
      </div>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_420px]">
        <VariableRegistry variables={workflowVariables} />
        <PublishCheckSummary incompleteNodes={incompleteNodes} />
      </div>
    </div>
  );
}

function EditorMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-2.5">
      <p className="text-xs text-[var(--color-text-secondary)]">{label}</p>
      <p className="mt-1 text-xl font-semibold text-[var(--color-text-primary)]">{value}</p>
    </div>
  );
}

function WorkflowCanvasNode({ data, selected }: NodeProps<EditorNodeData>) {
  const meta = nodeTypeMeta[data.nodeType];
  const Icon = meta.icon;

  return (
    <div className={`w-56 overflow-hidden rounded-[var(--radius-lg)] border bg-[var(--color-bg-card)] ${selected ? "border-[var(--color-primary)]" : "border-[var(--color-border-light)]"}`}>
      <Handle type="target" position={Position.Left} className="!h-3 !w-3 !border-2 !border-white" style={{ background: meta.color }} />
      <div className="p-3 pb-2">
        <div className="flex items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg" style={{ backgroundColor: `${meta.color}18`, color: meta.color }}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{data.label}</p>
            <p className="mt-0.5 text-xs text-[var(--color-text-tertiary)]">{data.typeLabel}</p>
          </div>
        </div>
      </div>
      <div className="space-y-3 p-3">
        <p className="line-clamp-2 text-xs leading-5 text-[var(--color-text-secondary)]">{data.summary}</p>
        <p className="text-[11px] font-medium text-[var(--color-text-tertiary)]">
          {data.runState} · {data.outputMode}
        </p>
        <div className="flex flex-wrap gap-1.5">
          <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-[11px] font-medium text-[var(--color-text-secondary)]">
            入 {data.inputVariables.length}
          </span>
          <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-[11px] font-medium text-[var(--color-text-secondary)]">
            出 {data.outputVariables.length}
          </span>
          <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-[11px] font-medium text-[var(--color-text-secondary)]">
            工具 {data.toolCount}
          </span>
          {data.pausePoint ? <span className="rounded bg-amber-100 px-2 py-1 text-[11px] font-medium text-amber-800">暂停</span> : null}
          {data.allowQuestion ? <span className="rounded bg-sky-100 px-2 py-1 text-[11px] font-medium text-sky-800">可追问</span> : null}
        </div>
        <StatusBadge complete={data.configStatus === "complete"} compact />
      </div>
      <Handle type="source" position={Position.Right} className="!h-3 !w-3 !border-2 !border-white" style={{ background: meta.color }} />
    </div>
  );
}

function NodeConfigPanel({
  node,
  availableVariables,
  insertedVariableName,
  onInsertVariable,
  onSave,
}: {
  node: Node<EditorNodeData>;
  availableVariables: WorkflowVariable[];
  insertedVariableName: string;
  onInsertVariable: (variableName: string) => void;
  onSave: () => void;
}) {
  const meta = nodeTypeMeta[node.data.nodeType];
  const Icon = meta.icon;

  return (
    <aside className="agent-card" aria-labelledby="node-config-title">
      <div className="px-4 pb-2 pt-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-lg" style={{ backgroundColor: `${meta.color}18`, color: meta.color }}>
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
        <PanelGroup title="基础信息">
          <p className="agent-muted text-sm leading-6">{node.data.summary}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <StatusBadge complete={node.data.configStatus === "complete"} />
            {node.data.pausePoint ? <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">暂停点</span> : null}
          </div>
        </PanelGroup>

        <PanelGroup title="输入变量">
          <VariableList variables={node.data.inputVariables} emptyText="该节点不需要上游输入" />
        </PanelGroup>

        <PanelGroup title="可引用变量">
          <VariableList
            variables={availableVariables.map((variable) => variable.name)}
            emptyText="当前节点前没有可引用变量"
            onInsertVariable={onInsertVariable}
          />
          {insertedVariableName ? (
            <p className="mt-3 rounded bg-indigo-50 px-2 py-1 text-xs font-medium text-indigo-800">
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

        <PanelGroup title="能力装配">
          <ConfigRows
            rows={[
              ["Skills", node.data.nodeType === "agent" ? "需求拆解、追问澄清、风险识别" : "按节点类型自动隐藏"],
              ["MCP", node.data.toolCount > 0 ? `${node.data.toolCount} 个工具已启用` : "未启用外部工具"],
              ["知识库", node.data.nodeType === "agent" ? "产品制度库" : "未引用"],
            ]}
          />
        </PanelGroup>

        <PanelGroup title="交互模式">
          <ConfigRows
            rows={[
              ["输出模式", node.data.outputMode],
              ["允许追问", node.data.allowQuestion ? "允许用户确认后继续" : "不允许追问"],
              ["暂停策略", node.data.pausePoint ? "写入 waiting_event" : "执行后进入下游"],
            ]}
          />
        </PanelGroup>

        <PanelGroup title="权限与审计">
          <ConfigRows
            rows={[
              ["权限校验", node.data.toolCount > 0 || node.data.pausePoint ? "需要后端复核" : "基础读取"],
              ["审计事件", node.data.toolCount > 0 ? "记录工具调用与脱敏摘要" : "记录节点状态变更"],
            ]}
          />
        </PanelGroup>

        <button
          type="button"
          onClick={onSave}
          className="agent-button agent-button-primary h-10 w-full px-3 text-sm"
        >
          <Milestone className="h-4 w-4" aria-hidden="true" />
          保存节点配置
        </button>
      </div>
    </aside>
  );
}

function NodeTypeConfig({ node }: { node: Node<EditorNodeData> }) {
  // 这里先按节点类型展示 MVP 必填配置，后续替换为 React Hook Form + Zod 校验表单。
  if (node.data.nodeType === "agent") {
    return (
      <ConfigRows
        rows={[
          ["智能体模板", "需求分析智能体"],
          ["输出模式", "分析后暂停"],
          ["模型配置", "待选择"],
          ["输出 Schema", "analysis_result、risk_level"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "human_review") {
    return (
      <ConfigRows
        rows={[
          ["审核角色", "流程负责人"],
          ["审核动作", "通过、驳回、修改后继续"],
          ["超时策略", "待配置"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "delivery") {
    return (
      <ConfigRows
        rows={[
          ["交付方式", "邮件"],
          ["收件人", "待配置"],
          ["失败策略", "失败后可重试"],
        ]}
      />
    );
  }

  if (node.data.nodeType === "user_input") {
    return (
      <ConfigRows
        rows={[
          ["字段数量", "3"],
          ["附件", "允许上传"],
          ["等待对象", "发起人"],
        ]}
      />
    );
  }

  return (
    <ConfigRows
      rows={[
        ["配置状态", node.data.configStatus === "complete" ? "已满足 MVP 字段" : "需要补齐字段"],
        ["后续表单", "按节点协议生成"],
      ]}
    />
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

function StatusBadge({ complete, compact = false }: { complete: boolean; compact?: boolean }) {
  if (complete) {
    return (
      <span className={`inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-1 font-medium text-emerald-800 ${compact ? "text-[11px]" : "text-xs"}`}>
        <CheckCircle2 className={compact ? "h-3 w-3" : "h-3.5 w-3.5"} aria-hidden="true" />
        配置完整
      </span>
    );
  }

  return <span className={`rounded bg-red-100 px-2 py-1 font-medium text-red-700 ${compact ? "text-[11px]" : "text-xs"}`}>待补配置</span>;
}

function VariableRegistry({ variables }: { variables: WorkflowVariable[] }) {
  return (
    <section className="agent-card" aria-labelledby="variable-title">
      <div className="px-4 pb-2 pt-3">
        <h3 id="variable-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
          变量面板
        </h3>
        <p className="agent-muted mt-1 text-xs">展示节点输出变量、来源和敏感标记</p>
      </div>
      <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-3">
        {variables.map((variable) => (
          <article key={variable.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm font-semibold text-[var(--color-text-primary)]">{variable.name}</p>
              <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">{variable.type}</span>
            </div>
            <p className="mt-2 text-xs text-[var(--color-text-tertiary)]">来源：{variable.sourceNode}</p>
            {variable.sensitive ? (
              <p className="mt-2 rounded bg-red-50 px-2 py-1 text-xs font-medium text-red-700">敏感变量，交付前需校验权限</p>
            ) : null}
          </article>
        ))}
      </div>
    </section>
  );
}

function PublishCheckSummary({ incompleteNodes }: { incompleteNodes: Node<EditorNodeData>[] }) {
  return (
    <section className="agent-card" aria-labelledby="publish-check-title">
      <div className="px-4 pb-2 pt-3">
        <h3 id="publish-check-title" className="text-sm font-semibold text-[var(--color-text-primary)]">
          发布校验摘要
        </h3>
        <p className="agent-muted mt-1 text-xs">先做前端提示，后续接入后端发布校验 API</p>
      </div>
      <div className="space-y-3 p-4">
        {incompleteNodes.length > 0 ? (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
            <div className="flex items-center gap-2 text-sm font-medium text-amber-800">
              <AlertTriangle className="h-4 w-4" aria-hidden="true" />
              {incompleteNodes.length} 个节点需要补齐配置
            </div>
            <ul className="mt-3 space-y-2 text-sm text-amber-800">
              {incompleteNodes.map((node) => (
                <li key={node.id}>- {node.data.label}</li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-medium text-emerald-800">
            所有节点已满足当前阶段发布校验。
          </div>
        )}
        <p className="agent-muted text-sm leading-6">
          当前摘要覆盖节点必填配置和敏感变量提示，下一步会补变量可见性规则和节点保存校验。
        </p>
      </div>
    </section>
  );
}
