import React, { useEffect, useMemo, useRef, useState } from "react";
import { message } from "antd";
import {
  Ban,
  Bot,
  BrainCircuit,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Cpu,
  Loader2,
  MessageSquarePlus,
  PencilLine,
  Sigma,
} from "lucide-react";
import type { AgentExecutionStep, RuntimeChatMessage, RuntimePreviewStep, RuntimeTokenUsage } from "../../types/runtime-types";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { AnswerEditModal } from "./AnswerEditModal";
import { FollowUpModal } from "./FollowUpModal";
import {
  buildModelOutputSteps,
  buildPersistedExecutionSteps,
  filterUserVisibleSteps,
  mergeExecutionSteps,
  readAgentPermissions,
  readFinalAnswer,
  summarizeToolSteps,
} from "../../utils/agentExecutionSteps";

interface SingleAgentPanelProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  streamingText: string;
  executionSteps: AgentExecutionStep[];
  streamStartedAt: number | null;
  readOnly?: boolean;
  /** 中断空态文案场景：多智能体抽屉内子智能体详情使用 clusterDrawer */
  interruptedScope?: "default" | "clusterDrawer";
  /**
   * 当前步骤已完成、尚未点「执行下一步」时：切换回来滚到最后一轮 AI 回答开头，
   * 而不是整页顶部。
   */
  scrollToLatestAnswerWhenDone?: boolean;
  onSaveAnswer?: (content: string) => void | Promise<void>;
  onFollowUp?: (followUpMessage: string) => void | Promise<void>;
}

type ConversationTurn = {
  id: string;
  userMessage: string;
  toolSteps: AgentExecutionStep[];
  finalAnswer: string;
  tokenUsage?: RuntimeTokenUsage;
  current: boolean;
};

function formatElapsed(streamStartedAt: number | null): string {
  if (!streamStartedAt) {
    return "";
  }
  const seconds = Math.max(1, Math.round((Date.now() - streamStartedAt) / 1000));
  return `${seconds}s`;
}

function readInitialUserPrompt(config: Record<string, unknown>): string {
  const prompt = config.userPrompt ?? config.prompt ?? "";
  return String(prompt).trim();
}

function firstNonBlank(...values: Array<string | undefined>): string {
  return values.find((value) => value?.trim())?.trim() ?? "";
}

function normalizeForCompare(value: string | undefined): string {
  return (value ?? "").replace(/\s+/g, " ").trim();
}

function hasToolResultStep(steps: AgentExecutionStep[]): boolean {
  return steps.some((step) => step.kind === "tool" && !!firstNonBlank(step.detail, step.summary, step.title));
}

function readStepOutput(step: RuntimePreviewStep, labels: string[]): string {
  const field = (step.outputs ?? []).find((item) => labels.includes(item.label));
  return field?.value?.trim() ?? "";
}

function hasFinalAnswerToolResult(step: RuntimePreviewStep): boolean {
  const source = readStepOutput(step, ["final_answer_source", "finalAnswerSource"]);
  if (source) {
    return source === "final_answer_tool";
  }
  const finalAnswer = readStepOutput(step, ["final_answer", "agent_response"]);
  const modelContent = readStepOutput(step, ["model_content", "modelContent"]);
  return !!finalAnswer && !!modelContent && normalizeForCompare(finalAnswer) !== normalizeForCompare(modelContent);
}

function buildConversationTurns(
  messages: RuntimeChatMessage[] | undefined,
  config: Record<string, unknown>,
  toolSteps: AgentExecutionStep[],
  options: {
    finalAnswer: string;
    running: boolean;
    hasFinalAnswerToolResult: boolean;
  },
): ConversationTurn[] {
  const turns: ConversationTurn[] = [];
  let pendingUser = "";

  (messages ?? []).forEach((message) => {
    const content = message.content?.trim() ?? "";
    if (!content) {
      return;
    }
    if (message.role === "user") {
      pendingUser = content;
      return;
    }
    if (message.role === "assistant") {
      turns.push({
        id: `turn-${message.id}`,
        userMessage: pendingUser,
        toolSteps: normalizeTurnSteps(message.processSteps ?? [], { running: false }),
        finalAnswer: content,
        tokenUsage: message.tokenUsage,
        current: false,
      });
      pendingUser = "";
    }
  });

  if (pendingUser) {
    turns.push({
      id: `turn-pending-${turns.length}`,
      userMessage: pendingUser,
      toolSteps: [],
      finalAnswer: "",
      current: false,
    });
  }

  if (turns.length === 0) {
    const initialPrompt = readInitialUserPrompt(config);
    turns.push({
      id: "turn-current",
      userMessage: initialPrompt,
      toolSteps: [],
      finalAnswer: "",
      current: false,
    });
  }

  const currentTurnIndex = turns.length - 1;
  return turns.map((turn, index) => {
    if (index !== currentTurnIndex) {
      return turn;
    }
    return {
      ...turn,
      toolSteps: normalizeTurnSteps(dedupeProcessSteps(mergeTurnProcessSteps(turn.toolSteps ?? [], toolSteps)), {
        running: options.running,
        hasFinalAnswerToolResult: options.hasFinalAnswerToolResult,
      }),
      finalAnswer: options.finalAnswer.trim() ? options.finalAnswer : options.running ? "" : turn.finalAnswer,
      current: true,
    };
  });
}

function normalizeTurnSteps(steps: AgentExecutionStep[], options?: { running?: boolean; hasFinalAnswerToolResult?: boolean }): AgentExecutionStep[] {
  const withoutFinalToolSteps = steps.filter((step) => step.kind !== "final_answer");
  const hasToolResult = hasToolResultStep(withoutFinalToolSteps);
  if (!options?.running && !hasToolResult && !options?.hasFinalAnswerToolResult) {
    return withoutFinalToolSteps.filter((step) => step.kind !== "model_output");
  }
  return withoutFinalToolSteps;
}

function dedupeProcessSteps(steps: AgentExecutionStep[]): AgentExecutionStep[] {
  const result: AgentExecutionStep[] = [];
  const seenToolKeys = new Set<string>();
  let reasoningIndex = -1;
  let modelOutputIndex = -1;

  for (const step of steps) {
    if (step.kind === "reasoning") {
      if (reasoningIndex >= 0) {
        result[reasoningIndex] = preferUniqueStep(result[reasoningIndex], step, "reasoning");
      } else {
        reasoningIndex = result.length;
        result.push(step);
      }
      continue;
    }
    if (step.kind === "model_output" || step.kind === "final_answer") {
      if (modelOutputIndex >= 0) {
        result[modelOutputIndex] = preferUniqueStep(result[modelOutputIndex], step, "model_output");
      } else {
        modelOutputIndex = result.length;
        result.push(step);
      }
      continue;
    }
    const contentKey = (step.detail || step.summary || step.title).replace(/\s+/g, " ").trim();
    const key = `${step.kind}:${step.title}:${contentKey}`;
    if (seenToolKeys.has(key)) {
      continue;
    }
    seenToolKeys.add(key);
    result.push(step);
  }
  return result;
}

/** 同 kind 只保留一条：内容更长者优先；任一侧已 done 则收尾，避免 live+持久化叠出双转圈。 */
function preferUniqueStep(
  current: AgentExecutionStep,
  incoming: AgentExecutionStep,
  kind: "reasoning" | "model_output",
): AgentExecutionStep {
  const currentLen = current.detail?.trim().length ?? 0;
  const incomingLen = incoming.detail?.trim().length ?? 0;
  const richer = incomingLen >= currentLen ? incoming : current;
  const eitherDone = current.status === "done" || incoming.status === "done";
  const eitherError = current.status === "error" || incoming.status === "error";
  if (kind === "reasoning") {
    return {
      ...richer,
      kind: "reasoning",
      title: eitherDone || eitherError ? "深度推理" : richer.title,
      summary: eitherDone || eitherError ? "可展开查看推理过程" : richer.summary,
      status: eitherError ? "error" : eitherDone ? "done" : richer.status,
    };
  }
  return {
    ...richer,
    kind: richer.kind === "final_answer" ? "final_answer" : "model_output",
    status: eitherError ? "error" : eitherDone ? "done" : richer.status,
    summary: eitherDone && !eitherError ? "可展开查看" : richer.summary,
  };
}

/**
 * 完成态会同时拿到 chatMessages.processSteps 与 outputs.toolCalls，两者是同一次调用的不同持久化视图。
 * chatMessages 保留完整详情，因此同名工具已存在时只丢弃外层重复项；同一轮真实重复调用仍会完整保留在 messageSteps 中。
 */
function mergeTurnProcessSteps(messageSteps: AgentExecutionStep[], runtimeSteps: AgentExecutionStep[]): AgentExecutionStep[] {
  const persistedToolTitles = new Set(
    messageSteps.filter((step) => step.kind === "tool").map((step) => step.title),
  );
  return [
    ...messageSteps,
    ...runtimeSteps.filter((step) => step.kind !== "tool" || !persistedToolTitles.has(step.title)),
  ];
}

function ToolStepRow({
  step,
  expanded,
  onToggle,
}: {
  step: AgentExecutionStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const hasDetail = !!step.detail?.trim();
  const detailRef = useRef<HTMLPreElement | null>(null);
  /** 流式输出时默认跟到底；用户一旦滚离底部就取消，本轮不再强行拉回。 */
  const stickToBottomRef = useRef(true);
  const ignoreScrollEventRef = useRef(false);
  const wasRunningRef = useRef(false);

  // 新一轮 running 开始时恢复自动跟底（例如下一轮推理）。
  useEffect(() => {
    const running = step.status === "running";
    if (running && !wasRunningRef.current) {
      stickToBottomRef.current = true;
    }
    wasRunningRef.current = running;
  }, [step.status]);

  useEffect(() => {
    if (!expanded || step.status !== "running" || !detailRef.current || !stickToBottomRef.current) {
      return;
    }
    const el = detailRef.current;
    ignoreScrollEventRef.current = true;
    el.scrollTop = el.scrollHeight;
    const frame = window.requestAnimationFrame(() => {
      ignoreScrollEventRef.current = false;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [expanded, step.status, step.detail]);

  function handleDetailScroll() {
    if (ignoreScrollEventRef.current || !detailRef.current) {
      return;
    }
    const el = detailRef.current;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    // 用户滚离底部后取消自动跟底，避免抢滚动条。
    if (distanceToBottom > 12) {
      stickToBottomRef.current = false;
    }
  }

  return (
    <div className="agent-tool-step">
      <button
        type="button"
        className="agent-tool-step-head"
        onClick={hasDetail ? onToggle : undefined}
        disabled={!hasDetail}
      >
        <span className="agent-tool-step-icon">
          {step.status === "error" ? (
            <span className="agent-tool-step-dot agent-tool-step-dot--error" />
          ) : step.kind === "reasoning" ? (
            step.status === "running" ? <Loader2 size={12} className="animate-spin text-indigo-500" /> : <BrainCircuit size={12} className="text-indigo-500" />
          ) : step.kind === "model_output" ? (
            step.status === "running" ? <Loader2 size={12} className="animate-spin text-emerald-500" /> : <Cpu size={12} className="text-emerald-500" />
          ) : (
            <CheckCircle2 size={12} className="text-emerald-500" />
          )}
        </span>
        <span className="agent-tool-step-title">{step.title}</span>
        {step.summary ? <span className="agent-tool-step-summary">{step.summary}</span> : null}
        {hasDetail ? (
          <span className="agent-tool-step-chevron">
            {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          </span>
        ) : null}
      </button>
      {hasDetail && expanded ? (
        <pre
          ref={detailRef}
          className="agent-tool-step-detail"
          onScroll={handleDetailScroll}
        >
          {step.detail}
        </pre>
      ) : null}
    </div>
  );
}

function ToolStepsBlock({
  steps,
  headerTitle,
  running,
  headerActions,
}: {
  steps: AgentExecutionStep[];
  headerTitle: string;
  running: boolean;
  headerActions?: React.ReactNode;
}) {
  const [expanded, setExpanded] = useState(true);
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});

  if (steps.length === 0 && !running) {
    return headerActions ? (
      <div className="agent-turn-tools agent-turn-tools--status-only">
        <div className="agent-turn-tools-toggle agent-turn-tools-toggle--static">
          <CheckCircle2 size={14} className="text-emerald-500" />
          <span>{headerTitle}</span>
          <div className="agent-turn-tools-actions">{headerActions}</div>
        </div>
      </div>
    ) : null;
  }

  function toggleStep(stepId: string) {
    setExpandedStepIds((prev) => ({ ...prev, [stepId]: !prev[stepId] }));
  }

  const shouldShowList = expanded && (steps.length > 0 || !running);

  return (
    <div className="agent-turn-tools">
      <div className="agent-turn-tools-toggle">
        <button type="button" className="agent-turn-tools-toggle-main" onClick={() => setExpanded((value) => !value)}>
          {running ? (
            <Loader2 size={14} className="animate-spin text-blue-500" />
          ) : (
            <CheckCircle2 size={14} className="text-emerald-500" />
          )}
          <span>{headerTitle}</span>
          <span className="agent-turn-tools-chevron">{expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</span>
        </button>
        {headerActions ? <div className="agent-turn-tools-actions">{headerActions}</div> : null}
      </div>
      {shouldShowList ? (
        <div className="agent-turn-tools-list">
          {steps.length === 0 ? (
            <p className="agent-turn-tools-empty">工具调用与输出将显示在这里</p>
          ) : (
            steps.map((step) => (
              <ToolStepRow
                key={step.id}
                step={step}
                expanded={step.status === "running" || !!expandedStepIds[step.id]}
                onToggle={() => toggleStep(step.id)}
              />
            ))
          )}
        </div>
      ) : null}
    </div>
  );
}

function AgentInterruptedState({ scope }: { scope: "default" | "clusterDrawer" }) {
  const isClusterDrawer = scope === "clusterDrawer";
  return (
    <div className="agent-run-interrupted">
      <div className="agent-run-interrupted-icon" aria-hidden="true">
        <Ban size={32} strokeWidth={1.75} />
      </div>
      <h4 className="agent-run-interrupted-title">执行已中断</h4>
      <p className="agent-run-interrupted-desc">
        {isClusterDrawer
          ? "多智能体步骤已被中断，子智能体运行数据已清空。请关闭此详情，并点击页面下方「重新执行」从头重新开始本步骤。"
          : "本步骤运行数据已清空，无法继续查看或追问。请点击页面下方「重新执行」，从头完整运行本步骤。"}
      </p>
    </div>
  );
}

function findScrollParent(element: HTMLElement | null): HTMLElement | Window {
  let node = element?.parentElement ?? null;
  while (node) {
    const style = window.getComputedStyle(node);
    const overflowY = style.overflowY;
    if ((overflowY === "auto" || overflowY === "scroll" || overflowY === "overlay")
      && node.scrollHeight > node.clientHeight + 1) {
      return node;
    }
    node = node.parentElement;
  }
  return window;
}

function isScrollNearBottom(container: HTMLElement | Window, threshold = 80): boolean {
  if (container instanceof HTMLElement) {
    return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
  }
  const doc = document.documentElement;
  return doc.scrollHeight - window.scrollY - window.innerHeight <= threshold;
}

/** 同一页会话内已进入过的进行中节点：再次切回时滚到回答开头，而非跟底。 */
const seenRunningAgentNodes = new Set<string>();

function ConversationTurnBlock({
  turn,
  showRunningHero,
  elapsedLabel,
  headerActions,
  answerStartRef,
  answerEndRef,
  answerBodyRef,
}: {
  turn: ConversationTurn;
  showRunningHero: boolean;
  elapsedLabel: string;
  headerActions?: React.ReactNode;
  /** 最新一轮 AI 回答内容起点（含过程步骤） */
  answerStartRef?: React.Ref<HTMLDivElement>;
  /** 最新一轮 AI 回答内容终点（工具/推理/正文都在此面板内） */
  answerEndRef?: React.Ref<HTMLDivElement>;
  /** 最终回复正文起点（已完成待推进时滚到这里） */
  answerBodyRef?: React.Ref<HTMLDivElement>;
}) {
  const running = turn.current && showRunningHero;
  const headerTitle = summarizeToolSteps(turn.toolSteps, elapsedLabel, running);
  const waitingForAnswer = running && !turn.finalAnswer.trim();
  const showFinalAnswerBody = !!turn.finalAnswer.trim();

  return (
    <section className="agent-turn">
      {turn.userMessage.trim() ? (
        <div className="agent-turn-user-row">
          <div className="agent-turn-user-bubble">
            <p>{turn.userMessage}</p>
          </div>
        </div>
      ) : null}

      <div ref={answerStartRef} className="agent-turn-assistant-panel">
        {turn.current || turn.toolSteps.length > 0 ? (
          <ToolStepsBlock
            steps={turn.toolSteps}
            headerTitle={headerTitle}
            running={running}
            headerActions={headerActions}
          />
        ) : null}

        {showFinalAnswerBody ? (
          <>
            <div ref={answerBodyRef} className="agent-turn-assistant">
              <MarkdownRenderer
                content={turn.finalAnswer}
                compact
              />
            </div>
            {turn.tokenUsage ? <TokenUsageLine usage={turn.tokenUsage} /> : null}
          </>
        ) : waitingForAnswer && turn.toolSteps.length === 0 ? (
          <div className="agent-turn-waiting">智能体正在生成回复…</div>
        ) : null}
        <div ref={answerEndRef} aria-hidden="true" />
      </div>
    </section>
  );
}

function TokenUsageLine({ usage }: { usage: RuntimeTokenUsage }) {
  return (
    <div className="mt-2 flex items-center justify-end gap-1.5 text-[11px] text-[var(--color-text-tertiary)]" title="本轮包含智能体为完成回答发起的全部模型调用">
      <Sigma size={12} aria-hidden="true" />
      <span>本轮 {usage.totalTokens.toLocaleString("zh-CN")} tokens</span>
      <span aria-hidden="true">·</span>
      <span>输入 {usage.inputTokens.toLocaleString("zh-CN")}</span>
      <span aria-hidden="true">·</span>
      <span>输出 {usage.outputTokens.toLocaleString("zh-CN")}</span>
    </div>
  );
}

export function SingleAgentPanel({
  activeStep,
  isStreaming,
  streamingText,
  executionSteps,
  streamStartedAt,
  readOnly = false,
  interruptedScope = "default",
  scrollToLatestAnswerWhenDone = false,
  onSaveAnswer,
  onFollowUp,
}: SingleAgentPanelProps) {
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [followUpModalOpen, setFollowUpModalOpen] = useState(false);
  const [elapsedLabel, setElapsedLabel] = useState("");
  const stepInterrupted = activeStep.state === "canceled";

  const config = (activeStep.configSnapshot ?? {}) as Record<string, unknown>;
  const permissions = readAgentPermissions(config);
  const persistedSteps = useMemo(() => buildPersistedExecutionSteps(activeStep), [activeStep]);
  const isLiveForStep = isStreaming;
  const steps = useMemo(
    () => mergeExecutionSteps(persistedSteps, executionSteps, isLiveForStep),
    [persistedSteps, executionSteps, isLiveForStep],
  );
  const finalAnswer = readFinalAnswer(activeStep, streamingText);
  const hasAnswerContent = !!finalAnswer.trim();
  const showRunningHero = !stepInterrupted
    && (activeStep.state === "running" || activeStep.state === "pending" || isStreaming);
  const finalAnswerFromTool = useMemo(() => hasFinalAnswerToolResult(activeStep), [activeStep]);
  const latestTurnRef = useRef<HTMLDivElement | null>(null);
  const latestAnswerStartRef = useRef<HTMLDivElement | null>(null);
  const latestAnswerEndRef = useRef<HTMLDivElement | null>(null);
  const latestAnswerBodyRef = useRef<HTMLDivElement | null>(null);
  /** 进行中默认跟 AI 回答底部；用户滚离后取消，直到下次「首次进入」该步骤。 */
  const stickToBottomRef = useRef(true);
  const ignoreOuterScrollRef = useRef(false);
  const rawProcessSteps = useMemo(() => {
    const visibleSteps = filterUserVisibleSteps(steps)
      .filter((step) => step.kind !== "final_answer" || showRunningHero);
    // live 步骤已含推理/答案时不再追加 outputs 快照，避免刷新回放后同内容叠两条。
    const hasLiveReasoning = visibleSteps.some((step) => step.kind === "reasoning");
    const hasLiveModelOutput = visibleSteps.some(
      (step) => step.kind === "model_output" || step.kind === "final_answer",
    );
    const modelOutputSteps = buildModelOutputSteps(activeStep, finalAnswer).filter((step) => {
      if (step.kind === "reasoning" && hasLiveReasoning) {
        return false;
      }
      if (step.kind === "model_output" && hasLiveModelOutput) {
        return false;
      }
      return true;
    });
    return dedupeProcessSteps([...visibleSteps, ...modelOutputSteps]);
  }, [activeStep, finalAnswer, showRunningHero, steps]);
  const processSteps = useMemo(() => {
    return normalizeTurnSteps(dedupeProcessSteps(rawProcessSteps), {
      running: showRunningHero,
      hasFinalAnswerToolResult: finalAnswerFromTool,
    });
  }, [finalAnswerFromTool, rawProcessSteps, showRunningHero]);
  const processStepsScrollKey = useMemo(
    () => processSteps.map((step) => `${step.id}:${step.status}:${step.summary}:${step.detail?.length ?? 0}`).join("|"),
    [processSteps],
  );
  const canFollowUp = permissions.allowQuestion && activeStep.allowsFollowUp !== false && activeStep.state === "done" && !readOnly && !!onFollowUp;
  const canEditAnswer = permissions.allowUserEdit && activeStep.allowsRegenerate !== false && activeStep.state === "done" && !readOnly;
  // 运行中正文只在过程区「生成最终答案」框内流式展示，不提前画到下方最终回复区。
  const displayFinalAnswer = showRunningHero ? "" : finalAnswer;
  const conversationTurns = useMemo(
    () =>
      buildConversationTurns(activeStep.chatMessages, config, processSteps, {
        finalAnswer: displayFinalAnswer,
        running: showRunningHero,
        hasFinalAnswerToolResult: finalAnswerFromTool,
      }),
    [activeStep.chatMessages, config, displayFinalAnswer, finalAnswerFromTool, processSteps, showRunningHero],
  );

  const hasContent = conversationTurns.length > 0 || processSteps.length > 0 || !!displayFinalAnswer.trim() || hasAnswerContent || showRunningHero;

  useEffect(() => {
    if (!showRunningHero || !streamStartedAt) {
      setElapsedLabel("");
      return;
    }
    const timer = window.setInterval(() => {
      setElapsedLabel(formatElapsed(streamStartedAt));
    }, 1000);
    setElapsedLabel(formatElapsed(streamStartedAt));
    return () => window.clearInterval(timer);
  }, [showRunningHero, streamStartedAt]);

  // 中断 / 待执行 / 失败后清掉「已看过」标记，下次重新执行按首次跟底。
  useEffect(() => {
    const nodeId = activeStep.nodeRunId ?? "";
    if (!nodeId) {
      return;
    }
    if (activeStep.state === "canceled" || activeStep.state === "pending" || activeStep.state === "failed") {
      seenRunningAgentNodes.delete(nodeId);
    }
  }, [activeStep.nodeRunId, activeStep.state]);

  function scrollAnswerAnchor(anchor: HTMLElement | null, block: ScrollLogicalPosition, behavior: ScrollBehavior) {
    if (!anchor) {
      return;
    }
    ignoreOuterScrollRef.current = true;
    anchor.scrollIntoView({ block, behavior });
    window.requestAnimationFrame(() => {
      ignoreOuterScrollRef.current = false;
    });
  }

  // 进入进行中步骤：首次 → 跟底；切回已看过的进行中步骤 → 最新 AI 回答开头。
  // 当前步已完成但未推进下一步：同样滚到最后一轮 AI 回答开头。
  // 历史已完成步骤由 TaskRunWorkspace 主滚动区回到顶部。
  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      if (showRunningHero) {
        const nodeId = activeStep.nodeRunId ?? "";
        const returning = Boolean(nodeId) && seenRunningAgentNodes.has(nodeId);
        if (nodeId) {
          seenRunningAgentNodes.add(nodeId);
        }
        if (returning) {
          stickToBottomRef.current = false;
          scrollAnswerAnchor(latestAnswerStartRef.current, "start", "smooth");
          return;
        }
        stickToBottomRef.current = true;
        scrollAnswerAnchor(latestAnswerEndRef.current, "end", "smooth");
        return;
      }
      if (scrollToLatestAnswerWhenDone && activeStep.state === "done") {
        scrollAnswerAnchor(
          latestAnswerBodyRef.current ?? latestAnswerStartRef.current,
          "start",
          "auto",
        );
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeStep.nodeRunId, activeStep.state, showRunningHero, scrollToLatestAnswerWhenDone]);

  // 进行中且未取消跟底时：工具/推理/正文任意更新都跟到 AI 回答最底部。
  useEffect(() => {
    if (!showRunningHero || !stickToBottomRef.current) {
      return;
    }
    const frame = window.requestAnimationFrame(() => {
      scrollAnswerAnchor(latestAnswerEndRef.current, "end", "smooth");
    });
    return () => window.cancelAnimationFrame(frame);
  }, [showRunningHero, processStepsScrollKey, conversationTurns.length]);

  // 用户滚离工作区底部后取消强制跟底。
  useEffect(() => {
    if (!showRunningHero) {
      return;
    }
    const parent = findScrollParent(latestAnswerStartRef.current);
    const onScroll = () => {
      if (ignoreOuterScrollRef.current) {
        return;
      }
      if (!isScrollNearBottom(parent)) {
        stickToBottomRef.current = false;
      }
    };
    const target: HTMLElement | Window = parent;
    target.addEventListener("scroll", onScroll, { passive: true });
    return () => target.removeEventListener("scroll", onScroll);
  }, [showRunningHero, activeStep.nodeRunId, conversationTurns.length]);

  async function handleFollowUpSubmit(followUpMessage: string) {
    if (!followUpMessage.trim()) {
      message.warning("请先输入追问内容");
      return;
    }
    if (!onFollowUp) {
      return;
    }
    setFollowUpModalOpen(false);
    await onFollowUp(followUpMessage.trim());
  }

  async function handleSaveEditedAnswer(value: string) {
    if (!value.trim()) {
      message.warning("修改内容不能为空");
      return;
    }
    if (!onSaveAnswer) {
      return;
    }
    setEditModalOpen(false);
    await onSaveAnswer(value.trim());
  }

  const turnHeaderActions =
    !showRunningHero && (canFollowUp || (canEditAnswer && hasAnswerContent)) ? (
      <>
        {canFollowUp ? (
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => setFollowUpModalOpen(true)}>
            <MessageSquarePlus size={14} aria-hidden="true" />
            追问
          </button>
        ) : null}
        {canEditAnswer && hasAnswerContent ? (
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => setEditModalOpen(true)}>
            <PencilLine size={14} aria-hidden="true" />
            修改
          </button>
        ) : null}
      </>
    ) : null;

  return (
    <div className="agent-run-panel mx-auto flex max-w-3xl flex-col">
      {showRunningHero ? (
        <div className="agent-run-progress">
          <div className="agent-execution-progress-track" aria-hidden="true">
            <div className="agent-execution-progress-bar" />
          </div>
        </div>
      ) : null}

      <section className={`agent-run-body${stepInterrupted ? " agent-run-body--interrupted" : ""}`}>
        {stepInterrupted ? (
          <AgentInterruptedState scope={interruptedScope} />
        ) : !hasContent ? (
          <div className="agent-run-empty">
            <Bot size={22} className="text-slate-300" />
            <p>暂无输出内容</p>
          </div>
        ) : (
          <div className="agent-turn-list">
            {conversationTurns.map((turn, index) => (
              <div key={turn.id} ref={index === conversationTurns.length - 1 ? latestTurnRef : undefined}>
                <ConversationTurnBlock
                  turn={turn}
                  showRunningHero={showRunningHero}
                  elapsedLabel={elapsedLabel}
                  headerActions={turn.current ? turnHeaderActions : undefined}
                  answerStartRef={index === conversationTurns.length - 1 ? latestAnswerStartRef : undefined}
                  answerEndRef={index === conversationTurns.length - 1 ? latestAnswerEndRef : undefined}
                  answerBodyRef={index === conversationTurns.length - 1 ? latestAnswerBodyRef : undefined}
                />
              </div>
            ))}
          </div>
        )}
      </section>

      <AnswerEditModal
        open={editModalOpen}
        initialValue={finalAnswer}
        onClose={() => setEditModalOpen(false)}
        onSave={handleSaveEditedAnswer}
      />
      <FollowUpModal
        open={followUpModalOpen}
        onClose={() => setFollowUpModalOpen(false)}
        onSubmit={(value) => void handleFollowUpSubmit(value)}
      />
    </div>
  );
}
