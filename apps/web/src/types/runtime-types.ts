/**
 * 运行态类型定义。
 *
 * 本模块定义任务处理流程中运行态相关的所有类型，包括：
 * - 节点步骤状态与分类
 * - SSE 流式事件类型
 * - 智能体执行阶段
 * - 对话消息与能力调用
 * - 运行预览与步骤操作
 *
 * 从 WorkbenchShell.tsx 提取并扩展，统一运行态类型管理。
 */

// ============================================================
// 基础运行态类型
// ============================================================

/** 节点步骤的执行状态；canceled 表示用户主动中断（数据已清空，只能整步重新执行） */
export type RuntimeStepState = "done" | "running" | "waiting" | "failed" | "pending" | "canceled";

/** 节点的业务分类，用于前端渲染不同的交互面板 */
export type RuntimeNodeKind = "launch" | "input" | "agent" | "multiAgent" | "approval" | "delivery";

/** 智能体执行阶段，对应 SSE 事件中的阶段标记 */
export type AgentPhase =
  | "preparing"      // 变量装配、资产解析
  | "tool_calling"   // MCP/Skill 工具调用
  | "model_calling"  // 模型推理中
  | "validating"     // 输出验证
  | "completed"      // 执行完成
  | "failed";        // 执行失败

// ============================================================
// 节点字段与能力
// ============================================================

/** 节点输入/输出字段 */
export type RuntimeNodeField = {
  label: string;
  value: string;
  sensitive?: boolean;
};

/** 对话消息 */
export type RuntimeChatMessage = {
  id: string;
  role: "user" | "assistant" | "system";
  author: string;
  content: string;
  /** 是否正在流式输出中 */
  streaming?: boolean;
  /** 消息产生的时间戳 */
  timestamp?: string;
};

/** 智能体执行步骤（主界面摘要 + 抽屉详情） */
export type AgentExecutionStep = {
  id: string;
  kind: "phase" | "tool" | "final_answer";
  phaseKey?: AgentPhase;
  title: string;
  summary: string;
  status: "running" | "done" | "error";
  durationMs?: number;
  /** 抽屉中展示的原始输出 */
  detail?: string;
  toolType?: "mcp" | "skill";
};

/** 能力调用项（MCP / Skill / 子智能体） */
export type RuntimeCapabilityItem = {
  id: string;
  name: string;
  kind: "mcp" | "skill" | "agent";
  status: "idle" | "running" | "waiting" | "done" | "error";
  statusLabel: string;
  summary: string;
  /** 是否为高风险操作，UI 需要特殊标记 */
  highRisk?: boolean;
  /** 工具调用耗时（毫秒） */
  durationMs?: number;
  /** 工具调用返回的结果摘要 */
  resultSummary?: string;
};

// ============================================================
// 运行预览步骤
// ============================================================

/** 单个步骤的运行预览数据 */
export type RuntimePreviewStep = {
  nodeRunId: string;
  /** 工作流节点 key，与 run.currentNodeKey 对齐 */
  nodeKey: string;
  title: string;
  subtitle: string;
  state: RuntimeStepState;
  kind: RuntimeNodeKind;
  description: string;
  inputs?: RuntimeNodeField[];
  outputs?: RuntimeNodeField[];
  completedAt?: string;
  chatMessages?: RuntimeChatMessage[];
  capabilities?: RuntimeCapabilityItem[];
  /** 当前智能体执行阶段 */
  agentPhase?: AgentPhase;
  /** 是否允许追问 */
  allowsFollowUp?: boolean;
  /** 是否允许重新生成 */
  allowsRegenerate?: boolean;
  /** 是否允许中断 */
  allowsInterrupt?: boolean;
  /** 节点的原始配置快照，便于展示子智能体集群等配置信息 */
  configSnapshot?: any;
};

/** 智能体运行预览 */
export type RuntimePreviewAgent = {
  name: string;
  capability: string;
  status: string;
  statusTone: "running" | "waiting" | "done";
  output: string;
  duration: string;
};

/** 运行事件记录 */
export type RuntimePreviewEvent = {
  id: string;
  time: string;
  title: string;
  description: string;
  tone: "info" | "success" | "warning";
  stepTitle: string;
  nodeId?: string;
};

/** 交付物预览 */
export type RuntimeDeliveryItem = {
  name: string;
  status: string;
  meta: string;
};

/** 整体运行预览 */
export type RuntimePreview = {
  runId: string;
  statusLabel: string;
  activeNode: string;
  progress: number;
  startedAt: string;
  ownerName: string;
  workflowVersion: number;
  steps: RuntimePreviewStep[];
  agents: RuntimePreviewAgent[];
  events: RuntimePreviewEvent[];
  deliveries: RuntimeDeliveryItem[];
};

// ============================================================
// SSE 事件类型
// ============================================================

/** 节点开始执行事件 */
export type NodeStartedEvent = {
  runId: string;
  nodeRunId: string;
  nodeType: string;
  nodeName: string;
  timestamp: string;
};

/** 智能体准备阶段事件 */
export type AgentThinkingEvent = {
  runId: string;
  nodeRunId: string;
  phase: AgentPhase;
  /** 阶段描述，如 "正在装配变量..." */
  message: string;
  timestamp: string;
};

/** 智能体模型流式输出事件 */
export type AgentStreamingEvent = {
  runId: string;
  nodeRunId: string;
  /** 本次增量文本 */
  deltaContent: string;
  /** 累计已输出文本 */
  accumulatedContent: string;
  timestamp: string;
};

/** 工具调用事件（MCP / Skill） */
export type AgentToolCallEvent = {
  runId: string;
  nodeRunId: string;
  toolName: string;
  toolType: "mcp" | "skill";
  status: "started" | "completed" | "failed";
  /** 工具调用结果摘要（仅 completed 时有值） */
  result?: string;
  /** 调用耗时（毫秒，仅 completed 时有值） */
  durationMs?: number;
  timestamp: string;
};

/** 子智能体事件（多智能体集群场景） */
export type ClusterAgentEvent = {
  runId: string;
  nodeRunId: string;
  /** 子智能体在集群中的索引 */
  agentIndex: number;
  agentName: string;
  eventType: "started" | "phase" | "streaming" | "tool_call" | "completed" | "failed";
  /** phase 时的智能体阶段 */
  phase?: AgentPhase;
  /** phase 时的阶段说明 */
  message?: string;
  /** streaming 时的增量文本 */
  deltaContent?: string;
  /** streaming 时的累计文本 */
  accumulatedContent?: string;
  /** tool_call 时的工具名称 */
  toolName?: string;
  /** tool_call 时的工具类型 */
  toolType?: "mcp" | "skill" | "agent";
  /** tool_call 时的状态 */
  toolStatus?: "started" | "completed" | "failed";
  /** tool_call 时的结果摘要 */
  result?: string;
  /** tool_call 时的耗时 */
  durationMs?: number;
  /** completed 时的输出摘要 */
  outputSummary?: string;
  /** failed 时的错误码 */
  errorCode?: string;
  /** failed 时的错误说明 */
  errorMessage?: string;
  timestamp: string;
};

/** 节点执行完成事件 */
export type NodeCompletedEvent = {
  runId: string;
  nodeRunId: string;
  outputs: Record<string, unknown>;
  timestamp: string;
};

/** 节点执行失败事件 */
export type NodeFailedEvent = {
  runId: string;
  nodeRunId: string;
  errorCode: string;
  errorMessage: string;
  timestamp: string;
};

/** 流程暂停事件 */
export type RunPausedEvent = {
  runId: string;
  /** 下一个待处理的节点（如果有） */
  nextNodeRunId?: string;
  nextNodeName?: string;
  nextNodeType?: string;
  /** 暂停原因描述 */
  reason: string;
  timestamp: string;
};

/** 流程完成事件 */
export type RunCompletedEvent = {
  runId: string;
  totalDurationMs: number;
  completedNodeCount: number;
  timestamp: string;
};

/** 心跳保活事件：Worker 每 15s 发送一次，前端看门狗据此判定后台执行是否存活 */
export type HeartbeatEvent = {
  timestamp: string;
  runId?: string;
  nodeRunId?: string;
  workerId?: string;
};

/** SSE 连接成功事件 */
export type ConnectedEvent = {
  runId: string;
  currentState: string;
  currentNodeName?: string;
  timestamp: string;
};

/** 所有 SSE 事件的联合类型；eventId 为 Redis Stream 记录 ID，用于断线续传 */
export type StreamEvent = (
  | { type: "connected"; data: ConnectedEvent }
  | { type: "node_started"; data: NodeStartedEvent }
  | { type: "agent_thinking"; data: AgentThinkingEvent }
  | { type: "agent_streaming"; data: AgentStreamingEvent }
  | { type: "agent_tool_call"; data: AgentToolCallEvent }
  | { type: "cluster_agent"; data: ClusterAgentEvent }
  | { type: "node_completed"; data: NodeCompletedEvent }
  | { type: "node_failed"; data: NodeFailedEvent }
  | { type: "run_paused"; data: RunPausedEvent }
  | { type: "run_completed"; data: RunCompletedEvent }
  | { type: "heartbeat"; data: HeartbeatEvent }
) & { eventId?: string };

// ============================================================
// 步骤操作
// ============================================================

/** 步进操作类型 */
export type StepAction =
  | "advance"       // 继续执行下一步
  | "submit_input"  // 提交用户输入
  | "approve"       // 审核通过
  | "reject"        // 审核驳回
  | "regenerate"    // 重新生成
  | "retry"         // 重试失败步骤
  | "rollback"      // 回退到指定步骤
  | "interrupt";    // 中断当前执行

// ============================================================
// 输入表单
// ============================================================

/** 输入字段配置（来自节点 config.inputFields） */
export type InputFieldConfig = {
  id: string;
  label: string;
  variable: string;
  placeholder: string;
  defaultValue?: string;
  /** 字段类型，默认 text */
  fieldType?: "text" | "textarea" | "select" | "file";
  /** select 类型的选项列表 */
  options?: Array<{ label: string; value: string }>;
  /** 是否必填 */
  required?: boolean;
};

/** 输入表单提交的 payload 结构 */
export type InputPayload = Record<string, unknown>;

// ============================================================
// 流式连接状态
// ============================================================

/** SSE 连接状态 */
export type StreamConnectionState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "error";

/** 建立连接时的选项 */
export type RunStreamConnectOptions = {
  /** true：回放当前步骤已产生的全部事件（进入/刷新页面场景）；默认只接收新事件 */
  replay?: boolean;
};

/** useRunStream Hook 的返回值 */
export type RunStreamState = {
  /** 已接收的全部事件 */
  events: StreamEvent[];
  /** 当前正在流式输出的文本（智能体节点） */
  streamingText: string;
  /** 当前是否正在流式输出 */
  isStreaming: boolean;
  /** 当前智能体执行阶段 */
  currentPhase: AgentPhase | null;
  /** 当前正在执行的节点信息 */
  activeNodeInfo: { nodeRunId: string; nodeName: string; nodeType: string } | null;
  /** 能力调用实时状态 */
  toolCalls: RuntimeCapabilityItem[];
  /** 单智能体执行步骤时间线（SSE 实时累积） */
  executionSteps: AgentExecutionStep[];
  /** 当前节点开始执行的本地时间戳（毫秒） */
  streamStartedAt: number | null;
  /** 子智能体实时状态（多智能体集群） */
  clusterAgents: Array<{
    index: number;
    name: string;
    status: "pending" | "running" | "completed" | "failed";
    streamingText: string;
    outputSummary: string;
    errorMessage?: string;
    toolCalls: RuntimeCapabilityItem[];
  }>;
  /** 连接状态 */
  connectionState: StreamConnectionState;
  /** 连接错误信息 */
  error: string | null;
  /** 最近一次收到任意事件（含 heartbeat）的本地时间戳（毫秒），看门狗活性判定依据 */
  lastEventAt: number | null;
  /** 连续重连失败次数：达到阈值时前端看门狗主动判定异常 */
  reconnectFailures: number;
  /** 建立连接 */
  connect: (options?: RunStreamConnectOptions) => Promise<void>;
  /** 等待 SSE 已连接后再推进步骤 */
  ensureConnected: (timeoutMs?: number) => Promise<void>;
  /** 断开连接 */
  disconnect: (options?: { preserveProgress?: boolean }) => void;
};
