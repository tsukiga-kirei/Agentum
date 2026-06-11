import React, { useEffect, useMemo, useState } from "react";
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
import type { AgentExecutionStep, RuntimePreviewStep } from "../../types/runtime-types";
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

function readUserPrompt(
  messages: Array<{ role: string; content: string }> | undefined,
  config: Record<string, unknown>,
): string {
  const userMessages = (messages ?? []).filter((message) => message.role === "user");
  const latestUser = userMessages[userMessages.length - 1]?.content;
  if (latestUser?.trim()) {
    return latestUser.trim();
  }
  return readInitialUserPrompt(config);
}

function buildConversationTurn(
  userMessage: string,
  toolSteps: AgentExecutionStep[],
  options: {
    finalAnswer: string;
  },
): ConversationTurn {
  return {
    id: "turn-current",
    userMessage,
    toolSteps,
    finalAnswer: options.finalAnswer,
  };
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
          {step.status === "running" ? (
            <Loader2 size={12} className="animate-spin text-blue-500" />
          ) : step.status === "error" ? (
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
  const running = showRunningHero;
  const headerTitle = summarizeToolSteps(turn.toolSteps, elapsedLabel, running);
  const waitingForAnswer = running && !turn.finalAnswer.trim();
  const showFinalAnswerBody = !running && !!turn.finalAnswer.trim();

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
        <ToolStepsBlock
          steps={turn.toolSteps}
          headerTitle={headerTitle}
          running={running}
          headerActions={headerActions}
        />

        {showFinalAnswerBody ? (
          <div className="agent-turn-assistant">
            <MarkdownRenderer
              content={turn.finalAnswer}
              compact
            />
          </div>
        ) : waitingForAnswer ? (
          <div className="agent-turn-waiting">
            <Loader2 size={16} className="animate-spin text-blue-500" />
            <span>智能体正在生成回复…</span>
          </div>
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
  const processSteps = useMemo(() => {
    const modelOutputSteps = buildModelOutputSteps(activeStep, finalAnswer);
    const visibleSteps = filterUserVisibleSteps(steps);
    const idDeduped = [...visibleSteps, ...modelOutputSteps].filter((step, index, list) =>
      list.findIndex((item) => item.id === step.id) === index
    );
    return dedupeProcessSteps(idDeduped);
  }, [activeStep, finalAnswer, steps]);
  const hasAnswerContent = !!finalAnswer.trim();
  const showRunningHero = activeStep.state === "running" || activeStep.state === "pending" || isStreaming;
  const canFollowUp = permissions.allowQuestion && activeStep.allowsFollowUp !== false && activeStep.state === "done" && !readOnly && !!onFollowUp;
  const canEditAnswer = permissions.allowUserEdit && activeStep.allowsRegenerate !== false && activeStep.state === "done" && !readOnly;
  const userPrompt = useMemo(() => readUserPrompt(activeStep.chatMessages, config), [activeStep.chatMessages, config]);
  const conversationTurn = useMemo(
    () =>
      buildConversationTurn(userPrompt, processSteps, {
        finalAnswer,
      }),
    [finalAnswer, processSteps, userPrompt],
  );

  const hasContent = !!userPrompt.trim() || processSteps.length > 0 || hasAnswerContent || showRunningHero;

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
            <ConversationTurnBlock
              key={conversationTurn.id}
              turn={conversationTurn}
              showRunningHero={showRunningHero}
              elapsedLabel={elapsedLabel}
              headerActions={turnHeaderActions}
            />
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
