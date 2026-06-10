import React, { useEffect, useMemo, useState } from "react";
import { message } from "antd";
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Loader2,
  MessageSquarePlus,
  PencilLine,
} from "lucide-react";
import type { AgentExecutionStep, RuntimeChatMessage, RuntimePreviewStep } from "../../types/runtime-types";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { AnswerEditModal } from "./AnswerEditModal";
import { FollowUpModal } from "./FollowUpModal";
import {
  buildPersistedExecutionSteps,
  filterUserVisibleSteps,
  mergeExecutionSteps,
  readAgentPermissions,
  readFinalAnswer,
  summarizeExecutionSteps,
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

function formatElapsed(streamStartedAt: number | null, completedAt?: string): string {
  if (!streamStartedAt) {
    return completedAt ? "已完成" : "";
  }
  const seconds = Math.max(1, Math.round((Date.now() - streamStartedAt) / 1000));
  return `${seconds}s`;
}

function ExecutionStepRow({
  step,
  expanded,
  onToggle,
}: {
  step: AgentExecutionStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const hasDetail = !!step.detail?.trim();
  const canExpand = hasDetail && step.kind !== "final_answer";

  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-800 overflow-hidden">
      <button
        type="button"
        className="flex w-full items-start gap-3 px-3 py-2.5 text-left hover:bg-slate-50 dark:hover:bg-slate-900/40"
        onClick={canExpand ? onToggle : undefined}
        disabled={!canExpand}
      >
        <span className="mt-0.5 shrink-0 text-slate-400">
          {step.status === "running" ? (
            <Loader2 size={14} className="animate-spin text-blue-500" />
          ) : step.status === "error" ? (
            <span className="inline-block h-3.5 w-3.5 rounded-full bg-rose-500" />
          ) : (
            <CheckCircle2 size={14} className="text-emerald-500" />
          )}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-sm text-slate-800 dark:text-slate-100">{step.title}</span>
          {step.summary ? (
            <span className="mt-0.5 block text-xs text-slate-500 dark:text-slate-400">{step.summary}</span>
          ) : null}
        </span>
        {canExpand ? (
          <span className="shrink-0 text-slate-400">
            {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </span>
        ) : null}
      </button>
      {canExpand && expanded && hasDetail ? (
        <div className="border-t border-slate-100 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-900/30 px-3 py-3">
          <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-slate-200 bg-white p-2 font-mono text-[11px] leading-relaxed text-slate-700 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300">
            {step.detail}
          </pre>
        </div>
      ) : null}
    </div>
  );
}

function ConversationMessageRow({ messageItem }: { messageItem: RuntimeChatMessage }) {
  const isUser = messageItem.role === "user";
  if (!isUser) {
    return (
      <div className="relative w-full">
        <MarkdownRenderer
          content={messageItem.content}
          compact
          className={messageItem.streaming ? "agent-markdown--streaming" : ""}
        />
        {messageItem.streaming ? (
          <span className="agent-chat-cursor mt-1 inline-block h-4 w-0.5 animate-pulse bg-blue-500 align-middle" aria-hidden="true" />
        ) : null}
      </div>
    );
  }

  return (
    <div className="flex justify-end gap-3">
      <div className="max-w-[85%] rounded-xl bg-blue-500 px-3 py-2.5 text-white">
        <p className="whitespace-pre-wrap text-sm leading-relaxed">{messageItem.content}</p>
      </div>
      <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-slate-200 bg-slate-100 text-xs font-medium text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
        我
      </span>
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
  onSaveAnswer,
  onFollowUp,
}: SingleAgentPanelProps) {
  const [stepsExpanded, setStepsExpanded] = useState(true);
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});
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
  const visibleSteps = useMemo(() => filterUserVisibleSteps(steps), [steps]);
  const finalAnswer = readFinalAnswer(activeStep, streamingText);
  const hasAnswerContent = !!finalAnswer.trim();
  const stepSummary = summarizeExecutionSteps(steps);
  const showRunningHero = activeStep.state === "running" || activeStep.state === "pending" || isStreaming;
  const isStreamingAnswer = showRunningHero && hasAnswerContent && isStreaming;
  const canFollowUp = permissions.allowQuestion && activeStep.allowsFollowUp !== false && activeStep.state === "done" && !readOnly && !!onFollowUp;
  const canEditAnswer = permissions.allowUserEdit && activeStep.allowsRegenerate !== false && activeStep.state === "done" && !readOnly;

  const conversationMessages = useMemo(() => {
    const baseMessages = [...(activeStep.chatMessages ?? [])];
    if (!streamingText.trim() || activeStep.state === "done") {
      return baseMessages;
    }
    const lastMessage = baseMessages[baseMessages.length - 1];
    if (lastMessage?.role === "assistant") {
      return [
        ...baseMessages.slice(0, -1),
        { ...lastMessage, content: streamingText, streaming: isStreaming },
      ];
    }
    return [
      ...baseMessages,
      {
        id: "streaming-assistant",
        role: "assistant" as const,
        author: activeStep.title,
        content: streamingText,
        streaming: isStreaming,
      },
    ];
  }, [activeStep.chatMessages, activeStep.state, activeStep.title, isStreaming, streamingText]);

  const hasConversation = conversationMessages.length > 0;

  useEffect(() => {
    if (showRunningHero) {
      setStepsExpanded(true);
    }
  }, [showRunningHero, activeStep.nodeRunId]);

  useEffect(() => {
    if (!showRunningHero || !streamStartedAt) {
      setElapsedLabel(activeStep.completedAt ? "" : "");
      return;
    }
    const timer = window.setInterval(() => {
      setElapsedLabel(formatElapsed(streamStartedAt, activeStep.completedAt));
    }, 1000);
    setElapsedLabel(formatElapsed(streamStartedAt, activeStep.completedAt));
    return () => window.clearInterval(timer);
  }, [showRunningHero, streamStartedAt, activeStep.completedAt]);

  function toggleStep(stepId: string) {
    setExpandedStepIds((prev) => ({ ...prev, [stepId]: !prev[stepId] }));
  }

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

  return (
    <div className="agent-run-panel mx-auto flex max-w-3xl flex-col gap-4">
      <section className="agent-run-progress-card overflow-hidden rounded-xl border border-emerald-200 bg-emerald-50/60 dark:border-emerald-900/50 dark:bg-emerald-950/20">
        <div className="flex items-center gap-2">
          <button
            type="button"
            className="flex min-w-0 flex-1 items-center gap-2 px-4 py-3 text-left"
            onClick={() => setStepsExpanded((value) => !value)}
          >
            {showRunningHero ? (
              <Loader2 size={16} className="shrink-0 animate-spin text-blue-500" />
            ) : (
              <CheckCircle2 size={16} className="shrink-0 text-emerald-500" />
            )}
            <span className="min-w-0 flex-1 text-sm text-slate-800 dark:text-slate-100">
              {stepSummary}
              {elapsedLabel ? (
                <span className="text-slate-500 dark:text-slate-400">{`，耗时 ${elapsedLabel}`}</span>
              ) : null}
            </span>
            <span className="shrink-0 text-slate-400">
              {stepsExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            </span>
          </button>
          <div className="mr-3 flex shrink-0 items-center gap-2">
            {canFollowUp ? (
              <button
                type="button"
                className="sys-btn sys-btn--default sys-btn--sm"
                onClick={() => setFollowUpModalOpen(true)}
              >
                <MessageSquarePlus size={14} aria-hidden="true" />
                追问
              </button>
            ) : null}
            {canEditAnswer && hasAnswerContent ? (
              <button
                type="button"
                className="sys-btn sys-btn--default sys-btn--sm"
                onClick={() => setEditModalOpen(true)}
              >
                <PencilLine size={14} aria-hidden="true" />
                修改
              </button>
            ) : null}
          </div>
        </div>

        {showRunningHero ? (
          <div className="px-4 pb-3">
            <div className="agent-execution-progress-track" aria-hidden="true">
              <div className="agent-execution-progress-bar" />
            </div>
          </div>
        ) : null}

        {stepsExpanded ? (
          <div className="space-y-2 border-t border-emerald-100 px-4 py-3 dark:border-emerald-900/40">
            {visibleSteps.length === 0 ? (
              <p className="py-2 text-center text-xs text-slate-400">
                {showRunningHero ? "工具调用与输出将显示在这里" : "暂无工具调用记录"}
              </p>
            ) : (
              visibleSteps.map((step) => (
                <ExecutionStepRow
                  key={step.id}
                  step={step}
                  expanded={!!expandedStepIds[step.id]}
                  onToggle={() => toggleStep(step.id)}
                />
              ))
            )}
          </div>
        ) : null}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-950">
        {!hasAnswerContent && !hasConversation && showRunningHero ? (
          <div className="flex items-start gap-3 py-6 text-slate-500 dark:text-slate-400">
            <Bot size={20} className="mt-0.5 shrink-0 text-blue-500" />
            <div>
              <p className="text-sm font-medium text-slate-700 dark:text-slate-200">智能体正在生成答案…</p>
              <p className="mt-1 text-xs">内容将在此处逐字输出。</p>
            </div>
          </div>
        ) : hasConversation ? (
          <div className="space-y-4">
            {conversationMessages.map((messageItem) => (
              <ConversationMessageRow key={messageItem.id} messageItem={messageItem} />
            ))}
          </div>
        ) : hasAnswerContent ? (
          <div className="relative">
            <MarkdownRenderer content={finalAnswer} />
            {isStreamingAnswer ? (
              <span className="agent-chat-cursor mt-1 inline-block h-4 w-0.5 animate-pulse bg-blue-500 align-middle" aria-hidden="true" />
            ) : null}
          </div>
        ) : (
          <div className="py-8 text-center text-sm text-slate-400">暂无输出内容</div>
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
