import type { AgentExecutionStep, AgentPhase, RuntimeCapabilityItem, RuntimeChatMessage, RuntimePreviewStep } from "../types/runtime-types";

const PHASE_LABELS: Record<AgentPhase, string> = {
  preparing: "准备上下文",
  tool_calling: "工具调用",
  model_calling: "模型推理",
  validating: "校验输出",
  completed: "执行完成",
  failed: "执行失败",
};

export function phaseStepTitle(phase: AgentPhase): string {
  return PHASE_LABELS[phase] ?? phase;
}

/** 从 assistant 消息的 processSteps 汇总工具/推理步骤，供执行历史回看。 */
function collectChatProcessSteps(messages: RuntimeChatMessage[] | undefined, idPrefix: string): AgentExecutionStep[] {
  if (!messages?.length) {
    return [];
  }
  const steps: AgentExecutionStep[] = [];
  messages.forEach((message, messageIndex) => {
    if (message.role !== "assistant" || !message.processSteps?.length) {
      return;
    }
    message.processSteps.forEach((step, stepIndex) => {
      steps.push({
        ...step,
        id: `${idPrefix}-msg-${messageIndex}-step-${stepIndex}-${step.id}`,
      });
    });
  });
  return dedupeExecutionSteps(steps);
}

function dedupeExecutionSteps(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  const seen = new Set<string>();
  return steps.filter((step) => {
    const contentKey = (step.detail || step.summary || step.title).replace(/\s+/g, " ").trim();
    const key = `${step.kind}:${step.title}:${contentKey}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function readRawProcessSteps(value: unknown, idPrefix: string): AgentExecutionStep[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item, index) => {
    if (!item || typeof item !== "object") {
      return [];
    }
    const record = item as Record<string, unknown>;
    const kind = record.kind === "tool" || record.kind === "reasoning" || record.kind === "model_output" || record.kind === "final_answer"
      ? record.kind
      : "model_output";
    const status = record.status === "running" || record.status === "error" ? record.status : "done";
    return [{
      id: `${idPrefix}-raw-step-${index}`,
      kind,
      title: String(record.title ?? (kind === "reasoning" ? "深度推理" : "执行步骤")),
      summary: String(record.summary ?? ""),
      status,
      detail: String(record.detail ?? ""),
      toolType: record.toolType === "mcp" || record.toolType === "skill" || record.toolType === "reasoning" || record.toolType === "model"
        ? record.toolType
        : undefined,
    }];
  });
}

function readPersistedToolCalls(value: unknown, idPrefix: string): AgentExecutionStep[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item, index) => {
    if (!item || typeof item !== "object") {
      return [];
    }
    const record = item as Record<string, unknown>;
    const toolType = record.toolType === "skill" ? "skill" : record.toolType === "mcp" ? "mcp" : null;
    if (!toolType) {
      return [];
    }
    const toolName = String(record.toolName ?? record.name ?? "工具调用");
    const status = record.status === "failed" || record.status === "error" ? "error" as const : "done" as const;
    const detail = String(record.detail ?? record.result ?? record.summary ?? "");
    return [{
      id: `${idPrefix}-tool-${index}`,
      kind: "tool" as const,
      title: toolType === "skill" ? `读取 Skill：${toolName}` : `调用 MCP：${toolName}`,
      summary: String(record.summary ?? (status === "error" ? "调用失败" : "调用完成")),
      status,
      detail,
      toolType,
    }];
  });
}

/**
 * 执行历史页：优先使用 chatMessages.processSteps（含完整 detail），否则回退到 capabilities / outputs。
 */
export function buildTraceExecutionSteps(step: RuntimePreviewStep): AgentExecutionStep[] {
  const finalAnswer = readFinalAnswer(step);
  const chatSteps = collectChatProcessSteps(step.chatMessages, step.nodeRunId);
  if (chatSteps.length > 0) {
    return filterUserVisibleSteps(chatSteps);
  }
  const capabilitySteps = buildPersistedExecutionSteps(step);
  const modelSteps = buildModelOutputSteps(step, finalAnswer);
  return filterUserVisibleSteps(dedupeExecutionSteps([...capabilitySteps, ...modelSteps]));
}

/** 集群子智能体持久化快照中的工具/推理步骤，供执行历史展开查看。 */
export function buildClusterAgentTraceSteps(agent: Record<string, unknown>, idPrefix: string): AgentExecutionStep[] {
  const fromChat = Array.isArray(agent.chatMessages)
    ? agent.chatMessages.flatMap((item, messageIndex) => {
        if (!item || typeof item !== "object") {
          return [];
        }
        const record = item as Record<string, unknown>;
        if (record.role !== "assistant") {
          return [];
        }
        return readRawProcessSteps(record.processSteps, `${idPrefix}-chat-${messageIndex}`);
      })
    : [];
  const fromToolCalls = readPersistedToolCalls(agent.toolCalls, idPrefix);
  return filterUserVisibleSteps(dedupeExecutionSteps([...fromChat, ...fromToolCalls]));
}

export function buildPersistedExecutionSteps(step: RuntimePreviewStep): AgentExecutionStep[] {
  const steps: AgentExecutionStep[] = [];

  const capabilities = step.capabilities ?? [];
  capabilities
    .filter((item) => item.kind === "mcp" || item.kind === "skill")
    .forEach((tool, index) => {
      steps.push({
        id: `persisted-tool-${index}`,
        kind: "tool",
        title: tool.kind === "skill" ? `读取 Skill：${tool.name}` : `调用 MCP：${tool.name}`,
        summary: tool.summary,
        status: tool.status === "error" ? "error" : "done",
        durationMs: tool.durationMs,
        detail: tool.resultSummary,
        toolType: tool.kind === "skill" ? "skill" : "mcp",
      });
    });

  return steps;
}

export function buildModelOutputSteps(step: RuntimePreviewStep, finalAnswer: string): AgentExecutionStep[] {
  const normalizedFinalAnswer = normalizeContent(finalAnswer);
  const seen = new Set<string>();
  const steps: AgentExecutionStep[] = [];

  function appendOutput(id: string, title: string, content: string, options?: { allowSameAsFinalAnswer?: boolean }) {
    const normalized = normalizeContent(content);
    if (!normalized || (!options?.allowSameAsFinalAnswer && normalized === normalizedFinalAnswer) || seen.has(normalized)) {
      return;
    }
    seen.add(normalized);
      steps.push({
        id,
        kind: "model_output",
        title,
        summary: "可展开查看",
        status: "done",
        detail: content.trim(),
        toolType: "model",
    });
  }

  const outputs = step.outputs ?? [];
  const reasoning = outputs.find((field) => field.label === "reasoning_content" || field.label === "reasoningContext")?.value;
  if (reasoning?.trim()) {
    steps.push({
      id: "persisted-reasoning",
      kind: "reasoning",
      title: "深度推理",
      summary: "可展开查看推理过程",
      status: "done",
      detail: reasoning.trim(),
      toolType: "reasoning",
    });
  }
  const rawOutputLabels = ["model_content", "modelContent", "raw_content", "rawContent", "response_content", "responseContent", "content", "responseBody"];
  rawOutputLabels.forEach((label) => {
    const value = outputs.find((field) => field.label === label)?.value;
    if (value) {
      appendOutput(`persisted-output-${label}`, "生成最终答案", value, { allowSameAsFinalAnswer: label === "model_content" || label === "modelContent" });
    }
  });

  return steps;
}

export function mergeExecutionSteps(
  persisted: AgentExecutionStep[],
  live: AgentExecutionStep[],
  isLiveForStep: boolean,
): AgentExecutionStep[] {
  if (!isLiveForStep || live.length === 0) {
    return persisted.length > 0 ? persisted : live;
  }
  return live;
}

export function readFinalAnswer(step: RuntimePreviewStep, streamingText = ""): string {
  const outputs = step.outputs ?? [];
  const finalField = outputs.find((field) => field.label === "final_answer" || field.label === "agent_response");
  if (finalField?.value?.trim()) {
    return finalField.value.trim();
  }
  if (streamingText.trim()) {
    return streamingText;
  }
  const assistantMessages = (step.chatMessages ?? []).filter((message) => message.role === "assistant");
  const latestAssistant = assistantMessages[assistantMessages.length - 1]?.content;
  if (latestAssistant?.trim()) {
    return latestAssistant;
  }
  const fallbackField = outputs.find((field) =>
    ["model_content", "modelContent", "raw_content", "rawContent", "response_content", "responseContent", "content", "responseBody"].includes(field.label)
  );
  if (fallbackField?.value?.trim()) {
    return fallbackField.value.trim();
  }
  return "";
}

export function readConfiguredTools(config: Record<string, unknown> | undefined): Array<{ id: string; label: string; kind: "skill" | "mcp" }> {
  if (!config) {
    return [];
  }
  const skills = readIdList(config.skillIds ?? config.skills);
  const mcps = readIdList(config.mcpIds ?? config.mcpServices);
  return [
    ...skills.map((id) => ({ id, label: shortenId(id), kind: "skill" as const })),
    ...mcps.map((id) => ({ id, label: shortenId(id), kind: "mcp" as const })),
  ];
}

export function readAgentPermissions(config: Record<string, unknown> | undefined): {
  allowQuestion: boolean;
  allowUserEdit: boolean;
} {
  return {
    allowQuestion: readBoolean(config?.allowQuestion),
    allowUserEdit: readBoolean(config?.allowUserEdit) || String(config?.outputMode ?? "") === "追问确认",
  };
}

/** 运行页前置区只展示用户关心的 AI 过程：工具调用、模型原始返回和 final_answer。 */
export function filterUserVisibleSteps(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  return steps.filter((step) => step.kind === "tool" || step.kind === "reasoning" || step.kind === "model_output" || step.kind === "final_answer");
}

export function summarizeToolSteps(steps: AgentExecutionStep[], elapsedLabel = "", running = false): string {
  const doneCount = steps.filter((step) => step.status === "done").length;
  const hasRunning = running || steps.some((step) => step.status === "running");
  if (hasRunning) {
    const base = doneCount > 0 ? `正在执行，已完成 ${doneCount} 个步骤` : "正在执行";
    return elapsedLabel ? `${base}，耗时 ${elapsedLabel}` : base;
  }
  if (steps.length === 0) {
    return elapsedLabel ? `执行完成，耗时 ${elapsedLabel}` : "执行完成";
  }
  const base = `已完成 ${steps.length} 个步骤`;
  return elapsedLabel ? `${base}，耗时 ${elapsedLabel}` : base;
}

export function summarizeExecutionSteps(steps: AgentExecutionStep[]): string {
  const visibleSteps = filterUserVisibleSteps(steps);
  const doneCount = visibleSteps.filter((step) => step.status === "done").length;
  const running = visibleSteps.some((step) => step.status === "running")
    || steps.some((step) => step.kind === "phase" && step.status === "running");
  if (running) {
    return doneCount > 0 ? `正在执行，已完成 ${doneCount} 个步骤` : "正在执行";
  }
  if (doneCount === 0) {
    return "等待智能体开始执行";
  }
  return `已完成 ${doneCount} 个步骤`;
}

export function upsertPhaseStep(
  steps: AgentExecutionStep[],
  phase: AgentPhase,
  message: string,
): AgentExecutionStep[] {
  const normalized = steps.map((step) =>
    step.kind === "phase" && step.status === "running"
      ? { ...step, status: "done" as const }
      : step,
  );
  const last = normalized[normalized.length - 1];
  if (last?.kind === "phase" && last.phaseKey === phase && last.status === "running") {
    return normalized.map((step, index) =>
      index === normalized.length - 1 ? { ...step, summary: message } : step,
    );
  }
  return [
    ...normalized,
    {
      id: `phase-${phase}-${normalized.length}`,
      kind: "phase",
      phaseKey: phase,
      title: phaseStepTitle(phase),
      summary: message,
      status: phase === "completed" || phase === "failed" ? "done" : "running",
    },
  ];
}

export function upsertReasoningStep(
  steps: AgentExecutionStep[],
  accumulatedContent: string,
  running: boolean,
): AgentExecutionStep[] {
  const nextStep: AgentExecutionStep = {
    id: "reasoning",
    kind: "reasoning",
    title: running ? "深度推理中" : "深度推理",
    summary: running ? "正在生成推理过程" : "可展开查看推理过程",
    status: running ? "running" : "done",
    detail: accumulatedContent,
    toolType: "reasoning",
  };
  const existingIndex = steps.findIndex((step) => step.kind === "reasoning");
  if (existingIndex < 0) {
    return [...steps, nextStep];
  }
  const copy = [...steps];
  copy[existingIndex] = nextStep;
  return copy;
}

export function upsertToolStep(
  steps: AgentExecutionStep[],
  tool: RuntimeCapabilityItem,
): AgentExecutionStep[] {
  const title = tool.kind === "skill" ? `读取 Skill：${tool.name}` : `调用 MCP：${tool.name}`;
  const existingIndex = steps.findIndex((step) => step.kind === "tool" && step.id === `tool-${tool.id}`);
  const next: AgentExecutionStep = {
    id: `tool-${tool.id}`,
    kind: "tool",
    title,
    summary: tool.summary,
    status: tool.status === "running" ? "running" : tool.status === "error" ? "error" : "done",
    durationMs: tool.durationMs,
    detail: tool.resultSummary,
    toolType: tool.kind === "skill" ? "skill" : "mcp",
  };
  if (existingIndex >= 0) {
    return steps.map((step, index) => (index === existingIndex ? next : step));
  }
  return [...steps, next];
}

export function upsertFinalAnswerStep(
  steps: AgentExecutionStep[],
  content: string,
  streaming: boolean,
): AgentExecutionStep[] {
  const existing = steps.find((step) => step.id === "live-final-answer");
  if (!content.trim() && !streaming && !existing) {
    return steps;
  }
  const withoutDraft = steps.filter((step) => step.id !== "live-final-answer");
  return [
    ...withoutDraft,
    {
      id: "live-final-answer",
      kind: "final_answer",
      title: "生成最终答案",
      summary: streaming ? "正在生成最终答案…" : "智能体已提交 final_answer",
      status: streaming ? "running" : "done",
      detail: content.trim() ? content : existing?.detail,
    },
  ];
}

export function upsertModelOutputStep(
  steps: AgentExecutionStep[],
  content: string,
  streaming: boolean,
): AgentExecutionStep[] {
  const existing = steps.find((step) => step.id === "live-model-output");
  if (!content.trim() && !streaming && !existing) {
    return steps;
  }
  const withoutDraft = steps.filter((step) => step.id !== "live-model-output");
  return [
    ...withoutDraft,
    {
      id: "live-model-output",
      kind: "model_output",
      title: "生成最终答案",
      summary: streaming ? "正在生成…" : "可展开查看",
      status: streaming ? "running" : "done",
      detail: content.trim() ? content : existing?.detail,
      toolType: "model",
    },
  ];
}

export function finalizeFinalAnswerStep(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  return steps.map((step) =>
    step.kind === "reasoning"
      ? { ...step, title: "深度推理", status: "done", summary: "可展开查看推理过程" }
      : step.id === "live-final-answer"
      ? {
          ...step,
          status: "done",
          summary: "智能体已提交 final_answer",
        }
      : step,
  );
}

function readIdList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => (item == null ? "" : String(item).trim()))
    .filter((item) => item && item !== "none" && item !== "custom");
}

function normalizeContent(value: string | undefined): string {
  return (value ?? "").replace(/\s+/g, " ").trim();
}

function readBoolean(value: unknown): boolean {
  return value === true || value === "true";
}

function shortenId(value: string): string {
  if (value.length <= 12) {
    return value;
  }
  return `${value.slice(0, 8)}…`;
}
