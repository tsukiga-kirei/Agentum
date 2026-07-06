import React, { useState } from "react";
import { BrainCircuit, CheckCircle2, ChevronDown, ChevronRight, Cpu, Loader2 } from "lucide-react";
import type { AgentExecutionStep } from "../../types/runtime-types";

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
            <BrainCircuit size={12} className="text-indigo-500" />
          ) : step.kind === "model_output" ? (
            <Cpu size={12} className="text-emerald-500" />
          ) : (
            <CheckCircle2 size={12} className="text-emerald-500" />
          )}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5 text-left">
          <span className="flex flex-wrap items-center gap-2">
            {step.toolType === "mcp" || step.toolType === "skill" ? (
              <span className="text-[10px] font-bold uppercase text-slate-400">{step.toolType}</span>
            ) : null}
            <span className="agent-tool-step-title">{step.title}</span>
          </span>
          {step.summary ? <span className="agent-tool-step-summary">{step.summary}</span> : null}
        </span>
        {hasDetail ? (
          <span className="agent-tool-step-chevron">
            {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          </span>
        ) : null}
      </button>
      {hasDetail && expanded ? (
        <div className="border-t border-slate-100 px-3 py-2 dark:border-slate-850">
          {step.durationMs ? (
            <div className="mb-2 text-[11px] text-slate-400">调用耗时：{step.durationMs} ms</div>
          ) : null}
          <pre className="agent-tool-step-detail m-0">{step.detail}</pre>
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
      <div className="rounded-lg border border-slate-100 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-900">
        <h5 className="mb-1 text-sm font-bold text-slate-700 dark:text-slate-350">{title}</h5>
        <p className="text-sm text-slate-400">{emptyText}</p>
      </div>
    );
  }

  function toggleStep(stepId: string) {
    setExpandedStepIds((prev) => ({ ...prev, [stepId]: !prev[stepId] }));
  }

  return (
    <div className="rounded-lg border border-slate-100 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-900">
      <button
        type="button"
        className="mb-3 flex w-full items-center justify-between gap-2 text-left"
        onClick={() => setSectionExpanded((value) => !value)}
      >
        <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350">
          {title}
          <span className="ml-2 text-xs font-normal text-slate-400">({steps.length})</span>
        </h5>
        <span className="text-slate-400">
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
