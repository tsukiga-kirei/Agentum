import React, { useId, useState } from "react";
import {
  AlertCircle,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Code2,
  Database,
  FileText,
  Loader2,
  Sparkles,
  Wrench,
} from "lucide-react";
import type { AgentExecutionStep } from "../../types/runtime-types";
import {
  extractResultSummary,
  formatFriendlyToolName,
  getToolSemanticAppearance,
} from "../../utils/agentActivityPresentation";
import { McpResultView, parseMcpResult } from "./McpResultView";
import { MarkdownRenderer } from "./MarkdownRenderer";

interface ChatActivityTimelineProps {
  steps: AgentExecutionStep[];
  running: boolean;
  elapsedLabel?: string;
  headerActions?: React.ReactNode;
  defaultExpanded?: boolean;
}

function ProcessIcon({ state }: { state: "running" | "error" | "done" }) {
  if (state === "running") {
    return <Loader2 className="animate-spin text-blue-500" size={14} />;
  }
  if (state === "error") {
    return <AlertCircle className="text-rose-500" size={14} />;
  }
  return <CheckCircle2 className="text-emerald-500" size={14} />;
}

function resolveStepAppearance(step: AgentExecutionStep) {
  if (step.kind === "reasoning") {
    return {
      Icon: Sparkles,
      title: "思考与研判",
      badgeLabel: "思考",
      tone: "thinking" as const,
    };
  }
  if (step.kind === "model_output") {
    const raw = (step.detail || step.title || "").trim();
    const shortPreamble = raw.length > 0 && raw.length <= 40 && !raw.includes("\n");
    return {
      Icon: Bot,
      title: shortPreamble ? raw : (step.title || "过渡说明"),
      badgeLabel: "说明",
      tone: "thinking" as const,
    };
  }
  const semantic = getToolSemanticAppearance(step);
  const friendly = formatFriendlyToolName(step.title);
  return {
    Icon: semantic.Icon,
    title: friendly,
    badgeLabel: semantic.badgeLabel,
    tone: semantic.tone,
  };
}

function ActivityStepItem({
  step,
  expanded,
  onToggle,
}: {
  step: AgentExecutionStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const dataset = step.kind === "tool" ? parseMcpResult(step.detail) : null;
  const hasDetail = Boolean(step.detail?.trim()) || Boolean(dataset);
  const { Icon, title, badgeLabel, tone } = resolveStepAppearance(step);
  const resultSummary = extractResultSummary(step);
  const isRunning = step.status === "running";
  const isError = step.status === "error";

  const durationStr = step.durationMs
    ? step.durationMs < 1000
      ? `${step.durationMs}ms`
      : `${(step.durationMs / 1000).toFixed(1)}s`
    : null;

  return (
    <li className="ai-activity-step" data-tone={tone}>
      <span className="ai-activity-node" aria-hidden="true">
        {isRunning ? (
          <Loader2 size={14} className="animate-spin text-blue-500" />
        ) : (
          <Icon size={14} />
        )}
      </span>
      <div className="ai-activity-step-content">
        <div className="ai-activity-heading">
          {hasDetail ? (
            <button
              type="button"
              className="ai-activity-toggle"
              aria-expanded={expanded}
              onClick={onToggle}
            >
              {step.kind === "tool" ? (
                <span className="ai-activity-badge">{badgeLabel}</span>
              ) : null}
              <span className="ai-activity-title font-medium">{title}</span>
              {resultSummary ? (
                <span className="ai-activity-result-pill">{resultSummary}</span>
              ) : null}
              {step.kind === "tool" && step.title && step.title !== title && !resultSummary ? (
                <span className="ai-activity-tool-name" title={step.title}>
                  {step.title}
                </span>
              ) : null}
              {durationStr ? (
                <span className="ai-activity-duration">{durationStr}</span>
              ) : null}
              {isRunning ? (
                <span className="ai-activity-status-text text-blue-500">执行中</span>
              ) : isError ? (
                <span className="ai-activity-status-text text-rose-500">失败</span>
              ) : (
                <span className="ai-activity-status-text">完成</span>
              )}
              <ChevronRight
                size={13}
                className={`transition-transform duration-150 text-[var(--color-text-tertiary)] ${
                  expanded ? "rotate-90" : ""
                }`}
              />
            </button>
          ) : (
            <div className="inline-flex items-center gap-2 py-0.5 text-[0.8125rem] text-[var(--color-text-primary)] w-full">
              {step.kind === "tool" ? (
                <span className="ai-activity-badge">{badgeLabel}</span>
              ) : null}
              <span className="ai-activity-title font-medium">{title}</span>
              {resultSummary ? (
                <span className="ai-activity-result-pill">{resultSummary}</span>
              ) : null}
              {step.kind === "tool" && step.title && step.title !== title && !resultSummary ? (
                <span className="ai-activity-tool-name" title={step.title}>
                  {step.title}
                </span>
              ) : null}
              {durationStr ? (
                <span className="ai-activity-duration">{durationStr}</span>
              ) : null}
              {isRunning ? (
                <span className="ai-activity-status-text text-blue-500">执行中</span>
              ) : isError ? (
                <span className="ai-activity-status-text text-rose-500">失败</span>
              ) : (
                <span className="ai-activity-status-text">完成</span>
              )}
            </div>
          )}
        </div>

        {hasDetail && expanded ? (
          <div className="ai-activity-detail">
            {dataset ? (
              <McpResultView result={dataset} />
            ) : step.kind === "reasoning" ? (
              <pre className="ai-activity-reasoning-body">
                {step.detail || "正在深入分析业务约束与输入参数..."}
              </pre>
            ) : step.kind === "model_output" ? (
              <div className="ai-activity-preamble-body">
                <MarkdownRenderer content={step.detail || ""} compact />
              </div>
            ) : (
              <details className="ai-activity-data" open>
                <summary>调用详情</summary>
                <pre className="ai-tool-data">{step.detail}</pre>
              </details>
            )}
          </div>
        ) : null}
      </div>
    </li>
  );
}

export function ChatActivityTimeline({
  steps,
  running,
  elapsedLabel,
  headerActions,
  defaultExpanded,
}: ChatActivityTimelineProps) {
  const bodyId = useId();
  const [manualExpanded, setManualExpanded] = useState<boolean | null>(null);
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});

  const expanded = manualExpanded ?? (defaultExpanded ?? running);

  const rounds = steps.filter((s) => s.kind === "reasoning").length;
  const tools = steps.filter((s) => s.kind === "tool").length;
  const doneCount = steps.filter((s) => s.status === "done").length;
  const failedCount = steps.filter((s) => s.status === "error").length;
  const activeStep = steps.find((s) => s.status === "running");

  const state: "running" | "error" | "done" = running
    ? "running"
    : failedCount > 0
    ? "error"
    : "done";

  let label = "思考与工具调用完成";
  if (running) {
    if (activeStep?.title) {
      label = `正在执行：${formatFriendlyToolName(activeStep.title)}`;
    } else if (doneCount > 0) {
      label = `正在执行，已完成 ${doneCount} 个步骤`;
    } else {
      label = "正在思考与执行...";
    }
  } else if (failedCount > 0) {
    label = `过程未完成（${failedCount} 个步骤异常）`;
  } else if (rounds > 0 && tools > 0) {
    label = `思考 ${rounds} 轮 · 调用 ${tools} 次工具`;
  } else if (tools > 0) {
    label = `已完成 ${tools} 次工具调用`;
  } else if (rounds > 0) {
    label = "已完成深度思考与分析";
  } else if (steps.length > 0) {
    label = `已完成 ${steps.length} 个步骤`;
  }

  const metrics = elapsedLabel ? `耗时 ${elapsedLabel}` : "";

  function toggleStep(stepId: string) {
    setExpandedStepIds((prev) => ({
      ...prev,
      [stepId]: !(prev[stepId] ?? steps.find((s) => s.id === stepId)?.status === "running"),
    }));
  }

  if (steps.length === 0 && !running) {
    return headerActions ? (
      <div className="flex items-center justify-end mb-2">{headerActions}</div>
    ) : null;
  }

  return (
    <section className="ai-process" aria-label="思考与执行过程">
      <div className="flex items-center justify-between w-full">
        <button
          type="button"
          className="ai-process-toggle"
          aria-expanded={expanded}
          aria-controls={bodyId}
          onClick={() => setManualExpanded(!expanded)}
        >
          <span className="ai-process-status" aria-hidden="true">
            <ProcessIcon state={state} />
          </span>
          <span className="ai-process-label">{label}</span>
          {metrics ? <span className="ai-process-metrics">{metrics}</span> : null}
          <span
            className="ai-process-caret"
            data-expanded={expanded}
            aria-hidden="true"
          >
            <ChevronRight size={13} />
          </span>
        </button>
        {headerActions ? <div className="shrink-0">{headerActions}</div> : null}
      </div>

      {expanded ? (
        <div id={bodyId} className="ai-process-fold">
          <div className="ai-process-body" tabIndex={0} role="region" aria-label="思考步骤">
            <ol className="ai-activity-timeline">
              {steps.map((step) => (
                <ActivityStepItem
                  key={step.id}
                  step={step}
                  expanded={expandedStepIds[step.id] ?? step.status === "running"}
                  onToggle={() => toggleStep(step.id)}
                />
              ))}
              <li className="ai-activity-terminal">
                <span className="ai-activity-node" aria-hidden="true">
                  <ProcessIcon state={state} />
                </span>
                <span>{running ? "处理中…" : "完成"}</span>
              </li>
            </ol>
          </div>
        </div>
      ) : null}
    </section>
  );
}
