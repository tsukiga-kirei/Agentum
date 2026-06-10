import type { AgentExecutionStep, AgentPhase, RuntimeCapabilityItem, RuntimePreviewStep } from "../types/runtime-types";

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

  const finalAnswer = readFinalAnswer(step);
  if (finalAnswer) {
    steps.push({
      id: "persisted-final-answer",
      kind: "final_answer",
      title: "最终答案",
      summary: "智能体已提交 final_answer",
      status: "done",
      detail: finalAnswer,
    });
  }

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
  if (streamingText.trim()) {
    return streamingText;
  }
  const fromMessage = step.chatMessages?.find((message) => message.role === "assistant")?.content;
  if (fromMessage?.trim()) {
    return fromMessage;
  }
  const outputs = step.outputs ?? [];
  const finalField = outputs.find((field) => field.label === "final_answer" || field.label === "agent_response");
  return finalField?.value?.trim() ?? "";
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

/** 运行页只展示工具调用与最终答案，隐藏准备上下文/模型推理等内部阶段。 */
export function filterUserVisibleSteps(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  return steps.filter((step) => step.kind === "tool" || step.kind === "final_answer");
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
      title: "最终答案",
      summary: streaming ? "正在生成最终答案…" : "智能体已提交 final_answer",
      status: streaming ? "running" : "done",
      detail: content.trim() ? content : existing?.detail,
    },
  ];
}

export function finalizeFinalAnswerStep(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  return steps.map((step) =>
    step.id === "live-final-answer"
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

function readBoolean(value: unknown): boolean {
  return value === true || value === "true";
}

function shortenId(value: string): string {
  if (value.length <= 12) {
    return value;
  }
  return `${value.slice(0, 8)}…`;
}
