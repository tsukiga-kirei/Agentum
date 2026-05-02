import type { MouseEvent, ReactNode } from "react";
import { useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  MiniMap,
  Node,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ChevronLeft,
  GitMerge,
  Milestone,
  PackageCheck,
  Route,
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
    color: "#0284c7",
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

// 画布先使用固定节点样例验证交互结构，后续保存时应替换为 WorkflowDefinition.nodes。
const initialNodes: Node<EditorNodeData>[] = [
  {
    id: "trigger_manual",
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
    },
  },
  {
    id: "input_materials",
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
    },
  },
  {
    id: "agent_analysis",
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
    },
  },
  {
    id: "parallel_collect",
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
    },
  },
  {
    id: "merge_report",
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
    },
  },
  {
    id: "condition_risk",
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
    },
  },
  {
    id: "human_review",
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
    },
  },
  {
    id: "delivery_email",
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
    },
  },
];

// 边关系对应工作流 MVP 主链路，后续应由 WorkflowDefinition.edges 持久化。
const initialEdges: Edge[] = [
  { id: "e_trigger_input", source: "trigger_manual", target: "input_materials" },
  { id: "e_input_agent", source: "input_materials", target: "agent_analysis" },
  { id: "e_agent_parallel", source: "agent_analysis", target: "parallel_collect" },
  { id: "e_parallel_merge", source: "parallel_collect", target: "merge_report" },
  { id: "e_merge_condition", source: "merge_report", target: "condition_risk" },
  { id: "e_condition_review", source: "condition_risk", target: "human_review", label: "高风险" },
  { id: "e_review_delivery", source: "human_review", target: "delivery_email" },
  { id: "e_condition_delivery", source: "condition_risk", target: "delivery_email", label: "低风险" },
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
  const [selectedNodeId, setSelectedNodeId] = useState(initialNodes[0].id);

  const decoratedNodes = useMemo(() => {
    return initialNodes.map((node) => {
      const meta = nodeTypeMeta[node.data.nodeType];

      return {
        ...node,
        style: {
          border: selectedNodeId === node.id ? `2px solid ${meta.color}` : "1px solid #d4d4d8",
          borderRadius: 8,
          boxShadow: selectedNodeId === node.id ? "0 8px 24px rgb(24 24 27 / 0.14)" : "none",
          minWidth: 190,
          padding: 0,
        },
      };
    });
  }, [selectedNodeId]);

  const selectedNode = decoratedNodes.find((node) => node.id === selectedNodeId) ?? decoratedNodes[0];
  const incompleteNodes = decoratedNodes.filter((node) => node.data.configStatus === "incomplete");

  const handleNodeClick = (_event: MouseEvent, node: Node<EditorNodeData>) => {
    // 节点选中态驱动右侧配置面板，后续接入表单保存时也以该 id 定位 NodeDefinition。
    setSelectedNodeId(node.id);
  };

  return (
    <div className="mx-auto max-w-[1600px] space-y-4 px-5 py-6 lg:px-8">
      <div className="flex flex-col gap-4 rounded-lg border border-zinc-200 bg-white p-4 xl:flex-row xl:items-center xl:justify-between">
        <div className="min-w-0">
          <button
            type="button"
            onClick={onBack}
            className="mb-3 inline-flex h-9 items-center gap-2 rounded-lg border border-zinc-300 px-3 text-sm font-medium text-zinc-800 hover:bg-zinc-50"
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            返回列表
          </button>
          <p className="text-sm text-zinc-500">工作流编辑器</p>
          <h2 className="mt-1 text-lg font-semibold text-zinc-950">{workflow.name}</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-zinc-600">{workflow.description}</p>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <EditorMetric label="节点" value={String(initialNodes.length)} />
          <EditorMetric label="暂停点" value={String(initialNodes.filter((node) => node.data.pausePoint).length)} />
          <EditorMetric label="待配置" value={String(initialNodes.filter((node) => node.data.configStatus === "incomplete").length)} />
        </div>
      </div>

      <div className="grid gap-4 xl:grid-cols-[280px_minmax(0,1fr)_360px]">
        <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="outline-title">
          <div className="border-b border-zinc-200 px-4 py-3">
            <h3 id="outline-title" className="text-sm font-semibold">
              流程大纲
            </h3>
            <p className="mt-1 text-xs text-zinc-500">快速定位节点和配置状态</p>
          </div>
          <div className="space-y-2 p-3">
            {decoratedNodes.map((node, index) => {
              const meta = nodeTypeMeta[node.data.nodeType];
              const Icon = meta.icon;
              const isActive = selectedNodeId === node.id;

              return (
                <button
                  key={node.id}
                  type="button"
                  onClick={() => setSelectedNodeId(node.id)}
                  className={`flex w-full items-start gap-3 rounded-lg border px-3 py-3 text-left transition ${
                    isActive ? "border-zinc-950 bg-zinc-950 text-white" : "border-zinc-200 bg-white text-zinc-800 hover:bg-zinc-50"
                  }`}
                >
                  <span
                    className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded ${
                      isActive ? "bg-white/15 text-white" : "bg-zinc-100"
                    }`}
                  >
                    <Icon className="h-4 w-4" aria-hidden="true" />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-xs font-medium">
                      {index + 1}. {node.data.label}
                    </span>
                    <span className={`mt-1 block text-xs ${isActive ? "text-zinc-300" : "text-zinc-500"}`}>
                      {node.data.typeLabel}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
        </section>

        <section className="min-h-[620px] overflow-hidden rounded-lg border border-zinc-200 bg-white" aria-label="工作流画布">
          <ReactFlow
            nodes={decoratedNodes}
            edges={initialEdges}
            onNodeClick={handleNodeClick}
            fitView
            fitViewOptions={{ padding: 0.22 }}
            nodesDraggable={false}
          >
            <Background color="#d4d4d8" gap={18} />
            <Controls />
            <MiniMap nodeStrokeWidth={3} zoomable pannable />
          </ReactFlow>
        </section>

        <NodeConfigPanel node={selectedNode} />
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
    <div className="rounded-lg border border-zinc-200 px-4 py-3">
      <p className="text-xs text-zinc-500">{label}</p>
      <p className="mt-1 text-xl font-semibold text-zinc-950">{value}</p>
    </div>
  );
}

function NodeConfigPanel({ node }: { node: Node<EditorNodeData> }) {
  const meta = nodeTypeMeta[node.data.nodeType];
  const Icon = meta.icon;

  return (
    <aside className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="node-config-title">
      <div className="border-b border-zinc-200 px-4 py-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-lg" style={{ backgroundColor: `${meta.color}18`, color: meta.color }}>
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <div>
            <p className="text-xs text-zinc-500">{node.data.typeLabel}</p>
            <h3 id="node-config-title" className="text-base font-semibold text-zinc-950">
              {node.data.label}
            </h3>
          </div>
        </div>
      </div>

      <div className="space-y-5 p-4">
        <PanelGroup title="基础信息">
          <p className="text-sm leading-6 text-zinc-600">{node.data.summary}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <StatusBadge complete={node.data.configStatus === "complete"} />
            {node.data.pausePoint ? <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">暂停点</span> : null}
          </div>
        </PanelGroup>

        <PanelGroup title="输入变量">
          <VariableList variables={node.data.inputVariables} emptyText="该节点不需要上游输入" />
        </PanelGroup>

        <PanelGroup title="输出变量">
          <VariableList variables={node.data.outputVariables} emptyText="暂未声明输出变量" />
        </PanelGroup>

        <PanelGroup title="核心配置">
          <NodeTypeConfig node={node} />
        </PanelGroup>

        <button
          type="button"
          className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-zinc-950 px-3 text-sm font-medium text-white hover:bg-zinc-800"
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
          <dt className="text-zinc-500">{label}</dt>
          <dd className="min-w-0 font-medium text-zinc-800">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function PanelGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h4 className="text-sm font-semibold text-zinc-950">{title}</h4>
      <div className="mt-2">{children}</div>
    </section>
  );
}

function VariableList({ variables, emptyText }: { variables: string[]; emptyText: string }) {
  if (variables.length === 0) {
    return <p className="text-sm text-zinc-500">{emptyText}</p>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {variables.map((variable) => (
        <span key={variable} className="rounded bg-zinc-100 px-2 py-1 text-xs font-medium text-zinc-700">
          {variable}
        </span>
      ))}
    </div>
  );
}

function StatusBadge({ complete }: { complete: boolean }) {
  if (complete) {
    return (
      <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800">
        <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
        配置完整
      </span>
    );
  }

  return <span className="rounded bg-red-100 px-2 py-1 text-xs font-medium text-red-700">待补配置</span>;
}

function VariableRegistry({ variables }: { variables: WorkflowVariable[] }) {
  return (
    <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="variable-title">
      <div className="border-b border-zinc-200 px-4 py-3">
        <h3 id="variable-title" className="text-sm font-semibold">
          变量面板
        </h3>
        <p className="mt-1 text-xs text-zinc-500">展示节点输出变量、来源和敏感标记</p>
      </div>
      <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-3">
        {variables.map((variable) => (
          <article key={variable.name} className="rounded-lg border border-zinc-200 p-3">
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm font-semibold text-zinc-950">{variable.name}</p>
              <span className="rounded bg-zinc-100 px-2 py-1 text-xs font-medium text-zinc-700">{variable.type}</span>
            </div>
            <p className="mt-2 text-xs text-zinc-500">来源：{variable.sourceNode}</p>
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
    <section className="rounded-lg border border-zinc-200 bg-white" aria-labelledby="publish-check-title">
      <div className="border-b border-zinc-200 px-4 py-3">
        <h3 id="publish-check-title" className="text-sm font-semibold">
          发布校验摘要
        </h3>
        <p className="mt-1 text-xs text-zinc-500">先做前端提示，后续接入后端发布校验 API</p>
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
        <p className="text-sm leading-6 text-zinc-600">
          当前摘要覆盖节点必填配置和敏感变量提示，下一步会补变量可见性规则和节点保存校验。
        </p>
      </div>
    </section>
  );
}
