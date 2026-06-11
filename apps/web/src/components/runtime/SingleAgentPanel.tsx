import React, { useEffect, useMemo, useRef, useState } from "react";
import { message } from "antd";
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Cpu,
  Loader2,
  MessageSquarePlus,
  PencilLine,
} from "lucide-react";
import type { AgentExecutionStep, RuntimeChatMessage, RuntimePreviewStep } from "../../types/runtime-types";
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
  onSaveAnswer?: (content: string) => void | Promise<void>;
  onFollowUp?: (followUpMessage: string) => void | Promise<void>;
}

type ConversationTurn = {
  id: string;
  userMessage: string;
  toolSteps: AgentExecutionStep[];
  finalAnswer: string;
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
      toolSteps: normalizeTurnSteps(dedupeProcessSteps([...(turn.toolSteps ?? []), ...toolSteps]), {
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
          ) : step.kind === "model_output" ? (
            <Cpu size={12} className="text-emerald-500" />
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
        <pre className="agent-tool-step-detail">{step.detail}</pre>
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

function ConversationTurnBlock({
  turn,
  showRunningHero,
  elapsedLabel,
  headerActions,
}: {
  turn: ConversationTurn;
  showRunningHero: boolean;
  elapsedLabel: string;
  headerActions?: React.ReactNode;
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

      <div className="agent-turn-assistant-panel">
        {turn.current || turn.toolSteps.length > 0 ? (
          <ToolStepsBlock
            steps={turn.toolSteps}
            headerTitle={headerTitle}
            running={running}
            headerActions={headerActions}
          />
        ) : null}

        {showFinalAnswerBody ? (
          <div className="agent-turn-assistant">
            <MarkdownRenderer
              content={turn.finalAnswer}
              compact
            />
          </div>
        ) : waitingForAnswer && turn.toolSteps.length === 0 ? (
          <div className="agent-turn-waiting">智能体正在生成回复…</div>
        ) : null}
      </div>
    </section>
  );
}

export function SingleAgentPanel({
  activeStep,
  isStreaming,
  streamingText,
  executionSteps,
  streamStartedAt,
  readOnly = false,
  onSaveAnswer,
  onFollowUp,
}: SingleAgentPanelProps) {
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [followUpModalOpen, setFollowUpModalOpen] = useState(false);
  const [elapsedLabel, setElapsedLabel] = useState("");

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
  const showRunningHero = activeStep.state === "running" || activeStep.state === "pending" || isStreaming;
  const finalAnswerFromTool = useMemo(() => hasFinalAnswerToolResult(activeStep), [activeStep]);
  const latestTurnRef = useRef<HTMLDivElement | null>(null);
  const latestTurnEndRef = useRef<HTMLDivElement | null>(null);
  const rawProcessSteps = useMemo(() => {
    const modelOutputSteps = buildModelOutputSteps(activeStep, finalAnswer);
    const visibleSteps = filterUserVisibleSteps(steps)
      .filter((step) => step.kind !== "final_answer" || showRunningHero);
    return [...visibleSteps, ...modelOutputSteps].filter((step, index, list) =>
      list.findIndex((item) => item.id === step.id) === index
    );
  }, [activeStep, finalAnswer, showRunningHero, steps]);
  const pureContextAnswer = useMemo(() => {
    if (showRunningHero || hasToolResultStep(rawProcessSteps) || finalAnswerFromTool) {
      return "";
    }
    return rawProcessSteps.find((step) => step.kind === "model_output" && step.detail?.trim())?.detail?.trim() ?? "";
  }, [finalAnswerFromTool, rawProcessSteps, showRunningHero]);
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
  const displayFinalAnswer = showRunningHero
    ? firstNonBlank(streamingText, pureContextAnswer)
    : finalAnswer;
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

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      if (showRunningHero) {
        latestTurnEndRef.current?.scrollIntoView({
          block: "end",
          behavior: "smooth",
        });
        return;
      }
      latestTurnRef.current?.scrollIntoView({
        block: "start",
        behavior: "auto",
      });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [conversationTurns.length, displayFinalAnswer.length, processStepsScrollKey, showRunningHero]);

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

      <section className="agent-run-body">
        {!hasContent ? (
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
                />
                {index === conversationTurns.length - 1 ? <div ref={latestTurnEndRef} /> : null}
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
