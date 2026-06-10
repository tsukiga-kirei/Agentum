import React, { useEffect, useMemo, useState } from "react";
import { Drawer, message } from "antd";
import {
  Bot,
  BrainCircuit,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Loader2,
  MessageSquarePlus,
  PencilLine,
  ServerCog,
  Sparkles,
  Workflow,
} from "lucide-react";
import type { AgentExecutionStep, AgentPhase, RuntimeCapabilityItem, RuntimePreviewStep } from "../../types/runtime-types";
import { MarkdownRenderer } from "./MarkdownRenderer";
import {
  buildPersistedExecutionSteps,
  mergeExecutionSteps,
  readAgentPermissions,
  readConfiguredTools,
  readFinalAnswer,
  summarizeExecutionSteps,
} from "../../utils/agentExecutionSteps";

interface SingleAgentPanelProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  currentPhase: AgentPhase | null;
  streamingText: string;
  toolCalls: RuntimeCapabilityItem[];
  executionSteps: AgentExecutionStep[];
  streamStartedAt: number | null;
  readOnly?: boolean;
  onRegenerate?: () => void;
}

function formatElapsed(streamStartedAt: number | null, completedAt?: string): string {
  if (!streamStartedAt) {
    return completedAt ? "已完成" : "—";
  }
  const seconds = Math.max(1, Math.round((Date.now() - streamStartedAt) / 1000));
  return `${seconds}s`;
}

function phaseStatusLabel(phase: AgentPhase | null, isStreaming: boolean): string {
  if (!isStreaming) {
    return "等待执行";
  }
  switch (phase) {
    case "preparing":
      return "正在准备上下文";
    case "tool_calling":
      return "正在调用工具";
    case "model_calling":
      return "正在模型推理";
    case "validating":
      return "正在校验输出";
    case "completed":
      return "执行完成";
    case "failed":
      return "执行失败";
    default:
      return "正在执行";
  }
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
  const canExpand = hasDetail;

  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-800 overflow-hidden">
      <button
        type="button"
        className="flex w-full items-start gap-3 px-3 py-3 text-left hover:bg-slate-50 dark:hover:bg-slate-900/40"
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
          <span className="block text-sm font-medium text-slate-800 dark:text-slate-100">{step.title}</span>
          <span className="mt-0.5 block text-xs text-slate-500 dark:text-slate-400">{step.summary}</span>
          {step.durationMs ? (
            <span className="mt-1 block text-[10px] text-slate-400">耗时 {step.durationMs}ms</span>
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
          <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-slate-400">
            {step.kind === "final_answer" ? "最终答案" : "原始输出"}
          </div>
          {step.kind === "final_answer" ? (
            <div className="rounded-md border border-slate-200 bg-white p-2 dark:border-slate-800 dark:bg-slate-950">
              <MarkdownRenderer content={step.detail ?? ""} compact />
            </div>
          ) : (
            <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md border border-slate-200 bg-white p-2 font-mono text-[11px] leading-relaxed text-slate-700 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-300">
              {step.detail}
            </pre>
          )}
        </div>
      ) : null}
    </div>
  );
}

export function SingleAgentPanel({
  activeStep,
  isStreaming,
  currentPhase,
  streamingText,
  toolCalls,
  executionSteps,
  streamStartedAt,
  readOnly = false,
  onRegenerate,
}: SingleAgentPanelProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});
  const [finalAnswerExpanded, setFinalAnswerExpanded] = useState(false);
  const [followUpText, setFollowUpText] = useState("");
  const [editedAnswer, setEditedAnswer] = useState("");
  const [elapsedLabel, setElapsedLabel] = useState("—");

  const config = (activeStep.configSnapshot ?? {}) as Record<string, unknown>;
  const permissions = readAgentPermissions(config);
  const configuredTools = readConfiguredTools(config);
  const persistedSteps = useMemo(() => buildPersistedExecutionSteps(activeStep), [activeStep]);
  const isLiveForStep = isStreaming;
  const steps = useMemo(
    () => mergeExecutionSteps(persistedSteps, executionSteps, isLiveForStep),
    [persistedSteps, executionSteps, isLiveForStep],
  );
  const finalAnswer = readFinalAnswer(activeStep, streamingText);
  const modelName =
    activeStep.outputs?.find((field) => field.label === "modelName")?.value
    || (toolCalls.find((tool) => tool.kind === "agent")?.name ?? "")
    || "租户分配模型";

  const systemPrompt = String(config.systemPrompt ?? "").trim();
  const userPrompt = String(config.userPrompt ?? config.prompt ?? "").trim();
  const stepSummary = summarizeExecutionSteps(steps);
  const showRunningHero = activeStep.state === "running" || activeStep.state === "pending" || isStreaming;
  const isAnswerReady = activeStep.state === "done" && !!finalAnswer.trim();
  const runningStatusText = useMemo(() => {
    const runningStep = [...steps].reverse().find((step) => step.status === "running");
    if (runningStep?.summary) {
      return runningStep.summary;
    }
    return phaseStatusLabel(currentPhase, isStreaming || activeStep.state === "running");
  }, [steps, currentPhase, isStreaming, activeStep.state]);
  const canFollowUp = permissions.allowQuestion && activeStep.allowsFollowUp !== false && activeStep.state === "done" && !readOnly;
  const canEditAnswer = permissions.allowUserEdit && activeStep.allowsRegenerate !== false && activeStep.state === "done" && !readOnly;

  useEffect(() => {
    setEditedAnswer(finalAnswer);
    setFinalAnswerExpanded(false);
  }, [finalAnswer, activeStep.nodeRunId]);

  useEffect(() => {
    if (showRunningHero) {
      setFinalAnswerExpanded(false);
    }
  }, [showRunningHero, activeStep.nodeRunId]);

  useEffect(() => {
    if (!showRunningHero || !streamStartedAt) {
      setElapsedLabel(activeStep.completedAt ? "已完成" : "—");
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

  function handleFollowUpSubmit() {
    if (!followUpText.trim()) {
      message.warning("请先输入追问内容");
      return;
    }
    message.info("追问运行态接口建设中，当前请先通过「重新生成」或联系流程设计者调整提示词。");
  }

  function handleSaveEditedAnswer() {
    if (!editedAnswer.trim()) {
      message.warning("修改内容不能为空");
      return;
    }
    if (!onRegenerate) {
      return;
    }
    message.success("将基于修改后的内容重新生成");
    onRegenerate();
  }

  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-950">
        <div className="flex items-start justify-between gap-4">
          <div className="flex min-w-0 items-start gap-3">
            <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${
              showRunningHero ? "bg-blue-500/10 text-blue-500" : "bg-emerald-500/10 text-emerald-500"
            }`}>
              {showRunningHero ? <Loader2 size={20} className="animate-spin" /> : <Bot size={20} />}
            </div>
            <div className="min-w-0">
              <h4 className="text-sm font-semibold text-slate-900 dark:text-slate-100">单智能体执行</h4>
              <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                {showRunningHero ? phaseStatusLabel(currentPhase, isStreaming) : "智能体步骤已完成，可查看最终答案与执行详情。"}
              </p>
            </div>
          </div>
          <button
            type="button"
            className="sys-btn sys-btn--default shrink-0 text-xs"
            onClick={() => setDrawerOpen(true)}
          >
            查看执行详情
          </button>
        </div>

        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <div className="rounded-lg bg-slate-50 px-3 py-2.5 dark:bg-slate-900/50">
            <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-slate-400">
              <Sparkles size={12} /> 使用模型
            </div>
            <div className="mt-1 text-sm font-medium text-slate-800 dark:text-slate-100">{modelName}</div>
          </div>
          <div className="rounded-lg bg-slate-50 px-3 py-2.5 dark:bg-slate-900/50">
            <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-slate-400">
              <Workflow size={12} /> 绑定工具
            </div>
            <div className="mt-1 flex flex-wrap gap-1.5">
              {configuredTools.length === 0 ? (
                <span className="text-xs text-slate-500">未配置 Skill / MCP</span>
              ) : (
                configuredTools.map((tool) => (
                  <span
                    key={`${tool.kind}-${tool.id}`}
                    className="inline-flex items-center gap-1 rounded-full bg-white px-2 py-0.5 text-[11px] text-slate-600 ring-1 ring-slate-200 dark:bg-slate-950 dark:text-slate-300 dark:ring-slate-700"
                  >
                    {tool.kind === "skill" ? <BrainCircuit size={11} /> : <ServerCog size={11} />}
                    {tool.label}
                  </span>
                ))
              )}
            </div>
          </div>
          <div className="rounded-lg bg-slate-50 px-3 py-2.5 dark:bg-slate-900/50">
            <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-slate-400">
              <Clock3 size={12} /> 执行进度
            </div>
            <div className="mt-1 text-sm font-medium text-slate-800 dark:text-slate-100">{stepSummary}</div>
            <div className="mt-0.5 text-xs text-slate-500">{elapsedLabel}</div>
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <span className="text-[10px] font-medium uppercase tracking-wide text-slate-400">交互权限</span>
          {permissions.allowQuestion ? (
            <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[11px] text-blue-700 dark:bg-blue-950/40 dark:text-blue-300">允许追问</span>
          ) : (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500 dark:bg-slate-800">不允许追问</span>
          )}
          {permissions.allowUserEdit ? (
            <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[11px] text-indigo-700 dark:bg-indigo-950/40 dark:text-indigo-300">允许修改</span>
          ) : (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500 dark:bg-slate-800">不允许修改</span>
          )}
        </div>

        <div className="agent-execution-answer-panel mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          {showRunningHero ? (
            <div className="px-3 py-3">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Loader2 size={14} className="animate-spin text-blue-500" />
                  <span className="text-sm font-medium text-slate-800 dark:text-slate-100">最终答案</span>
                </div>
                <span className="text-xs text-blue-600 dark:text-blue-400">执行中</span>
              </div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{runningStatusText}</p>
              <div className="agent-execution-progress-track mt-3" aria-hidden="true">
                <div className="agent-execution-progress-bar" />
              </div>
            </div>
          ) : (
            <>
              <button
                type="button"
                className="flex w-full items-center justify-between gap-3 px-3 py-3 text-left hover:bg-slate-50 dark:hover:bg-slate-900/40"
                onClick={() => isAnswerReady && setFinalAnswerExpanded((value) => !value)}
                disabled={!isAnswerReady}
              >
                <div className="flex min-w-0 items-center gap-2">
                  <CheckCircle2 size={14} className={isAnswerReady ? "text-emerald-500" : "text-slate-300"} />
                  <span className="text-sm font-medium text-slate-800 dark:text-slate-100">最终答案</span>
                  {isAnswerReady ? (
                    <span className="truncate text-xs text-slate-400">
                      {finalAnswerExpanded ? "点击收起" : "点击展开查看完整内容"}
                    </span>
                  ) : (
                    <span className="text-xs text-slate-400">暂无内容</span>
                  )}
                </div>
                {isAnswerReady ? (
                  <span className="shrink-0 text-slate-400">
                    {finalAnswerExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  </span>
                ) : null}
              </button>
              {isAnswerReady && finalAnswerExpanded ? (
                <div className="border-t border-slate-100 px-3 py-3 dark:border-slate-800">
                  <MarkdownRenderer content={finalAnswer} compact />
                </div>
              ) : null}
            </>
          )}
        </div>
      </section>

      <Drawer
        title="智能体执行详情"
        placement="right"
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        rootClassName="agent-admin-drawer"
        destroyOnClose={false}
      >
        <div className="workbench-launch-drawer">
          <div className="workbench-launch-drawer-content">
            <section className="workbench-launch-drawer-hero">
              <span className="workflow-launch-card-icon" aria-hidden="true">
                <Bot size={18} />
              </span>
              <div className="min-w-0">
                <h2>{activeStep.title}</h2>
                <p>
                  {modelName}
                  <span className="workflow-detail-drawer-meta-sep" aria-hidden="true"> · </span>
                  {stepSummary}
                  <span className="workflow-detail-drawer-meta-sep" aria-hidden="true"> · </span>
                  {elapsedLabel}
                </p>
              </div>
            </section>

            <section className="workbench-launch-drawer-section">
              <h3>提示词</h3>
              <div className="space-y-3">
                <label className="sys-field">
                  <span className="sys-field-label">系统提示词</span>
                  <textarea
                    className="sys-field-textarea sys-readonly-textarea min-h-[72px]"
                    readOnly
                    value={systemPrompt || "（未配置）"}
                  />
                </label>
                <label className="sys-field">
                  <span className="sys-field-label">用户提示词</span>
                  <textarea
                    className="sys-field-textarea sys-readonly-textarea min-h-[72px]"
                    readOnly
                    value={userPrompt || "（未配置）"}
                  />
                </label>
              </div>
            </section>

            <section className="workbench-launch-drawer-section">
              <h3>执行步骤</h3>
              <p className="workbench-launch-drawer-section-lead">
                点击步骤可展开工具返回内容；最终答案默认展开。
              </p>
              <div className="space-y-2">
                {steps.length === 0 ? (
                  <div className="rounded-lg border border-dashed border-slate-200 px-3 py-6 text-center text-xs text-slate-400 dark:border-slate-800">
                    {showRunningHero ? "智能体执行中，步骤将实时追加到这里。" : "暂无步骤记录"}
                  </div>
                ) : (
                  steps.map((step) => (
                    <ExecutionStepRow
                      key={step.id}
                      step={step}
                      expanded={step.kind === "final_answer" ? true : !!expandedStepIds[step.id]}
                      onToggle={() => toggleStep(step.id)}
                    />
                  ))
                )}
              </div>
            </section>

            {canFollowUp ? (
              <section className="workbench-launch-drawer-section">
                <h3>追问</h3>
                <p className="workbench-launch-drawer-section-lead">流程已开启「允许追问」，可在下方输入补充问题。</p>
                <textarea
                  className="sys-field-textarea min-h-[96px]"
                  placeholder="输入你想追问智能体的问题"
                  value={followUpText}
                  onChange={(event) => setFollowUpText(event.target.value)}
                />
              </section>
            ) : null}

            {canEditAnswer ? (
              <section className="workbench-launch-drawer-section">
                <h3>修改结果</h3>
                <p className="workbench-launch-drawer-section-lead">流程已开启「允许修改」，保存后将基于修改内容重新生成。</p>
                <textarea
                  className="sys-field-textarea min-h-[160px]"
                  value={editedAnswer}
                  onChange={(event) => setEditedAnswer(event.target.value)}
                />
              </section>
            ) : null}
          </div>

          <div className="workflow-drawer-footer">
            <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => setDrawerOpen(false)}>
              关闭
            </button>
            <div className="workflow-drawer-footer-actions">
              {canFollowUp ? (
                <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={handleFollowUpSubmit}>
                  <MessageSquarePlus size={14} aria-hidden="true" />
                  提交追问
                </button>
              ) : null}
              {canEditAnswer ? (
                <button type="button" className="sys-btn sys-btn--primary sys-btn--sm" onClick={handleSaveEditedAnswer}>
                  <PencilLine size={14} aria-hidden="true" />
                  保存并重新生成
                </button>
              ) : null}
            </div>
          </div>
        </div>
      </Drawer>
    </div>
  );
}
