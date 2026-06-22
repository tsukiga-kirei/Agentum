import React, { useEffect, useRef } from "react";
import type { RuntimePreviewStep, RuntimePreview, RuntimeStepState } from "../../types/runtime-types";
import { Zap, FileEdit, Bot, Users, Search, Package, Check, AlertTriangle, Ban } from "lucide-react";

interface StepProgressRailProps {
  preview: RuntimePreview;
  activeStepIndex: number;
  selectedStepIndex: number | null;
  activeRunTab: string;
  onStepSelect: (step: RuntimePreviewStep, index: number) => void;
}

// 每个运行状态对应的视觉令牌，集中管理便于配色协调与长期维护
type StepStateVisual = {
  // 状态图标按钮（圆形指示器）的样式
  indicator: string;
  // 选中态卡片的边框与背景强调色
  cardActive: string;
  // 选中态标题文字颜色
  titleActive: string;
};

const STEP_STATE_VISUALS: Record<RuntimeStepState, StepStateVisual> = {
  done: {
    indicator: "bg-emerald-500 border-emerald-500 text-white shadow-sm shadow-emerald-500/25",
    cardActive: "bg-emerald-50/60 dark:bg-emerald-950/30 border-emerald-300 dark:border-emerald-700/60 shadow-sm shadow-emerald-500/5",
    titleActive: "text-emerald-700 dark:text-emerald-300",
  },
  failed: {
    indicator: "bg-rose-500 border-rose-500 text-white shadow-sm shadow-rose-500/25",
    cardActive: "bg-rose-50/70 dark:bg-rose-950/30 border-rose-300 dark:border-rose-700/60 shadow-sm shadow-rose-500/5",
    titleActive: "text-rose-700 dark:text-rose-300",
  },
  running: {
    indicator: "bg-blue-50 border-blue-500 text-blue-600 dark:bg-blue-950/40 dark:border-blue-500 dark:text-blue-300 shadow-sm shadow-blue-500/15",
    cardActive: "bg-blue-50/70 dark:bg-blue-950/30 border-blue-300 dark:border-blue-600/70 shadow-sm shadow-blue-500/5",
    titleActive: "text-blue-700 dark:text-blue-300",
  },
  waiting: {
    indicator: "bg-amber-50 border-amber-400 text-amber-600 dark:bg-amber-950/40 dark:border-amber-500 dark:text-amber-300 shadow-sm shadow-amber-500/15",
    cardActive: "bg-amber-50/70 dark:bg-amber-950/30 border-amber-300 dark:border-amber-600/70 shadow-sm shadow-amber-500/5",
    titleActive: "text-amber-700 dark:text-amber-300",
  },
  pending: {
    indicator: "bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-700 text-slate-400 dark:text-slate-600",
    cardActive: "bg-white dark:bg-slate-900 border-slate-300 dark:border-slate-700",
    titleActive: "text-slate-700 dark:text-slate-200",
  },
  // 用户主动中断：中性灰展示，与失败（红色）严格区分，提示只能整步重新执行
  canceled: {
    indicator: "bg-slate-200 dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400",
    cardActive: "bg-slate-50 dark:bg-slate-900/70 border-slate-300 dark:border-slate-600 shadow-sm",
    titleActive: "text-slate-600 dark:text-slate-300",
  },
};

export function StepProgressRail({
  preview,
  activeStepIndex,
  selectedStepIndex,
  activeRunTab,
  onStepSelect,
}: StepProgressRailProps) {
  const activeStepRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (activeRunTab !== "current") {
      return;
    }
    activeStepRef.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [activeRunTab, activeStepIndex]);

  function getStepIcon(kind: string, state: string) {
    if (state === "done") return <Check size={15} strokeWidth={2.5} className="text-white" />;
    if (state === "failed") return <AlertTriangle size={14} strokeWidth={2.5} className="text-white" />;
    if (state === "canceled") return <Ban size={14} strokeWidth={2.5} />;

    switch (kind) {
      case "launch": return <Zap size={14} />;
      case "input": return <FileEdit size={14} />;
      case "agent": return <Bot size={14} />;
      case "multiAgent": return <Users size={14} />;
      case "approval": return <Search size={14} />;
      case "delivery": return <Package size={14} />;
      default: return <Bot size={14} />;
    }
  }

  const progressValue = Math.min(100, Math.max(0, preview.progress));

  return (
    <aside className="workbench-task-rail border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 p-4 w-full flex-shrink-0 flex flex-col rounded-xl shadow-sm" aria-label="任务流程进度">
      <div className="flex justify-between items-center mb-3">
        <strong className="text-base font-semibold text-slate-800 dark:text-slate-200 tracking-tight">流程进度</strong>
        <span className="inline-flex items-center gap-1.5 text-sm font-semibold text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/50 px-2.5 py-0.5 rounded-full tabular-nums">
          {progressValue >= 100 ? (
            <Check size={14} strokeWidth={2.5} className="text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
          ) : null}
          {progressValue}%
        </span>
      </div>
      <div className="h-3 w-full bg-slate-100 dark:bg-slate-800/80 rounded-full overflow-hidden mb-7 ring-1 ring-inset ring-slate-200/70 dark:ring-slate-800 shadow-inner">
        <div
          className="rail-progress-fill h-full bg-gradient-to-r from-blue-500 via-indigo-500 to-violet-500 rounded-full transition-all duration-700 ease-out shadow-sm shadow-indigo-500/30"
          style={{ width: `${progressValue}%` }}
        />
      </div>

      <div className="flex-1 overflow-y-auto space-y-1 pt-3 pl-2 pr-1 pb-6">
        {preview.steps.map((step, index) => {
          const stepsForTrace = preview.steps.filter((s) => s.state !== "pending");
          const fallbackIndex = stepsForTrace.length > 0
            ? preview.steps.indexOf(stepsForTrace[stepsForTrace.length - 1])
            : 0;
          const selectedIdx = selectedStepIndex !== null ? selectedStepIndex : fallbackIndex;
          const deliveryStepIndex = preview.steps.findIndex((item) => item.kind === "delivery");

          const isSelected = activeRunTab === "current"
            ? activeStepIndex === index
            : activeRunTab === "deliveries"
            ? step.kind === "delivery" && (selectedStepIndex === index || (selectedStepIndex === null && index === deliveryStepIndex))
            : activeRunTab === "trace"
            ? selectedIdx === index
            : false;

          const isFuturePending = step.state === "pending" && index !== activeStepIndex;
          const isDone = step.state === "done";
          const isFailed = step.state === "failed";
          const isRunning = step.state === "running";
          const isWaiting = step.state === "waiting";
          const isLast = index === preview.steps.length - 1;

          const visual = STEP_STATE_VISUALS[step.state] ?? STEP_STATE_VISUALS.pending;

          return (
            <div
              key={step.nodeRunId || step.title}
              ref={activeRunTab === "current" && activeStepIndex === index ? activeStepRef : undefined}
              className="relative flex gap-3 group pb-3 last:pb-0"
            >
              {/* 时间轴连接线：已完成节点向下延伸为绿色渐变并叠加向下流光，其余为浅灰 */}
              {!isLast && (
                <div
                  className={`absolute left-[14px] top-9 -bottom-0.5 w-1 rounded-full transition-colors duration-300 ${
                    isDone
                      ? "rail-flow-line bg-gradient-to-b from-emerald-400 to-emerald-500 dark:from-emerald-600 dark:to-emerald-500"
                      : "bg-slate-200 dark:bg-slate-800"
                  }`}
                />
              )}

              {/* 状态指示圆点：根据状态着色，失败显示告警图标 */}
              <button
                type="button"
                disabled={isFuturePending}
                onClick={() => onStepSelect(step, index)}
                aria-current={isSelected ? "step" : undefined}
                className={`w-8 h-8 aspect-square shrink-0 rounded-full flex items-center justify-center border transition-all duration-300 z-10 ${visual.indicator} ${
                  isSelected ? "ring-4 ring-blue-500/15 dark:ring-blue-400/15 scale-105" : ""
                } ${!isFuturePending ? "cursor-pointer" : "cursor-not-allowed"}`}
              >
                {getStepIcon(step.kind, step.state)}
              </button>

              {/* 步骤信息卡片 */}
              <button
                type="button"
                disabled={isFuturePending}
                onClick={() => onStepSelect(step, index)}
                className={`min-w-0 flex-1 text-left px-3 py-2.5 rounded-lg border transition-all duration-200 ${
                  isSelected
                    ? visual.cardActive
                    : isFuturePending
                    ? "opacity-60 cursor-not-allowed border-transparent"
                    : "bg-transparent border-transparent hover:border-slate-200 dark:hover:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-900/60"
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <strong className={`text-sm font-semibold block leading-tight transition-colors duration-200 truncate ${
                    isSelected ? visual.titleActive : "text-slate-800 dark:text-slate-200"
                  }`}>
                    {step.title}
                  </strong>
                  {/* 执行中 / 等待中显示脉冲动效，分别用蓝色与琥珀色区分 */}
                  {(isRunning || isWaiting) && (
                    <span className="flex h-1.5 w-1.5 relative flex-shrink-0" aria-hidden="true">
                      <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
                        isWaiting ? "bg-amber-400" : "bg-blue-400"
                      }`}></span>
                      <span className={`relative inline-flex rounded-full h-1.5 w-1.5 ${
                        isWaiting ? "bg-amber-500" : "bg-blue-500"
                      }`}></span>
                    </span>
                  )}
                  {/* 失败节点用静态红点提示，不使用脉冲，避免误读为执行中 */}
                  {isFailed && (
                    <span className="inline-flex h-1.5 w-1.5 rounded-full bg-rose-500 flex-shrink-0" aria-hidden="true"></span>
                  )}
                </div>
                <small className={`text-xs block mt-1 leading-normal ${
                  isFailed
                    ? "text-rose-500 dark:text-rose-400"
                    : "text-slate-500 dark:text-slate-400"
                }`}>{step.subtitle}</small>
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
