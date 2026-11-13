import { McpResultView, parseMcpResult } from "./McpResultView";
import React, { useState } from "react";
import { AlertCircle, CheckCircle2, ChevronDown, ChevronRight, Loader2 } from "lucide-react";
import type { AgentExecutionStep } from "../../types/runtime-types";
import {
  extractResultSummary,
  formatFriendlyToolName,
  getToolSemanticAppearance,
} from "../../utils/agentActivityPresentation";

function TraceExecutionStepRow({
  step,
  expanded,
  onToggle,
}: {
  step: AgentExecutionStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const hasDetail = !!step.detail?.trim();
  const dataset = step.kind === "tool" ? parseMcpResult(step.detail) : null;
  const { Icon, badgeLabel, tone } = getToolSemanticAppearance(step);
  const friendlyName = formatFriendlyToolName(step.title);
  const resultSummary = extractResultSummary(step);
  const isRunning = step.status === "running";
  const isError = step.status === "error";

  const durationStr = step.durationMs
    ? step.durationMs < 1000
      ? `${step.durationMs}ms`
      : `${(step.durationMs / 1000).toFixed(1)}s`
    : null;

  return (
    <div className="agent-tool-step" data-kind={step.kind} data-status={step.status}>
      <button
        type="button"
        className="agent-tool-step-head"
        onClick={hasDetail ? onToggle : undefined}
        disabled={!hasDetail}
      >
        <span className="agent-tool-step-icon" data-tone={tone}>
          {isRunning ? (
            <Loader2 size={13} className="animate-spin text-blue-500" />
          ) : isError ? (
            <AlertCircle size={13} className="text-rose-500" />
          ) : (
            <Icon size={13} />
          )}
        </span>
        <span className="agent-tool-step-type-pill">{badgeLabel}</span>
        <span className="agent-tool-step-title" title={step.title}>{friendlyName}</span>
        {resultSummary ? (
          <span className="agent-tool-step-result-pill">{resultSummary}</span>
        ) : step.summary && step.summary !== "调用完成" ? (
          <span className="agent-tool-step-summary">{step.summary}</span>
        ) : null}
        {durationStr ? (
          <span className="agent-tool-step-duration">{durationStr}</span>
        ) : null}
        <span className="agent-tool-step-status">
          {isRunning ? "执行中" : isError ? "失败" : "完成"}
        </span>
        {hasDetail ? (
          <span className="agent-tool-step-chevron">
            {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          </span>
        ) : null}
      </button>
      {hasDetail && expanded ? (
        <div className="agent-tool-step-detail-container">
          {dataset ? <McpResultView result={dataset} /> : <pre className="agent-tool-step-detail m-0">{step.detail}</pre>}
        </div>
      ) : null}
    </div>
  );
}

export function TraceExecutionStepsSection({
  title,
  steps,
  defaultExpanded = true,
  emptyText = "该步骤无工具或 Skill 调用记录",
}: {
  title: string;
  steps: AgentExecutionStep[];
  defaultExpanded?: boolean;
  emptyText?: string;
}) {
  const [sectionExpanded, setSectionExpanded] = useState(defaultExpanded);
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});

  if (steps.length === 0) {
    return (
      <div className="rounded-lg border border-slate-200 bg-slate-100/80 p-4 dark:border-slate-800 dark:bg-slate-900">
        <h5 className="mb-1 text-sm font-bold text-slate-700 dark:text-slate-350">{title}</h5>
        <p className="text-sm text-slate-500 dark:text-slate-400">{emptyText}</p>
      </div>
    );
  }

  function toggleStep(stepId: string) {
    setExpandedStepIds((prev) => ({ ...prev, [stepId]: !prev[stepId] }));
  }

  return (
    <div className="rounded-lg border border-slate-200 bg-slate-100/80 p-4 dark:border-slate-800 dark:bg-slate-900">
      <button
        type="button"
        className="mb-3 flex w-full items-center justify-between gap-2 text-left"
        onClick={() => setSectionExpanded((value) => !value)}
      >
        <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350">
          {title}
          <span className="ml-2 text-xs font-normal text-slate-500 dark:text-slate-400">({steps.length})</span>
        </h5>
        <span className="text-slate-500 dark:text-slate-400">
          {sectionExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </span>
      </button>
      {sectionExpanded ? (
        <div className="space-y-2">
          {steps.map((step) => (
            <TraceExecutionStepRow
              key={step.id}
              step={step}
              expanded={step.status === "running" || !!expandedStepIds[step.id]}
              onToggle={() => toggleStep(step.id)}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function TraceExecutionStepsInline({ steps }: { steps: AgentExecutionStep[] }) {
  const [expandedStepIds, setExpandedStepIds] = useState<Record<string, boolean>>({});

  if (steps.length === 0) {
    return null;
  }

  return (
    <div className="space-y-2">
      {steps.map((step) => (
        <TraceExecutionStepRow
          key={step.id}
          step={step}
          expanded={!!expandedStepIds[step.id]}
          onToggle={() => setExpandedStepIds((prev) => ({ ...prev, [step.id]: !prev[step.id] }))}
        />
      ))}
    </div>
  );
}

export function TraceExecutionRunningHint() {
  return (
    <div className="flex items-center gap-2 text-sm text-slate-400">
      <Loader2 size={14} className="animate-spin" />
      工具调用记录将在执行完成后展示
    </div>
  );
}
