import React, { useEffect, useRef } from "react";
import type { RuntimePreviewStep, RuntimePreview } from "../../types/runtime-types";
import { Zap, FileEdit, Bot, Users, Search, Package, Check, AlertTriangle, Ban } from "lucide-react";

interface StepProgressRailProps {
  preview: RuntimePreview;
  activeStepIndex: number;
  selectedStepIndex: number | null;
  activeRunTab: string;
  onStepSelect: (step: RuntimePreviewStep, index: number) => void;
}

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
    <aside className="workbench-task-rail" aria-label="任务流程进度">
      <div className="rail-progress-header">
        <strong className="rail-progress-title">流程进度</strong>
        <span className="rail-progress-percent">
          {progressValue >= 100 ? (
            <Check size={14} strokeWidth={2.5} aria-hidden="true" />
          ) : null}
          {progressValue}%
        </span>
      </div>
      <div className="rail-progress-track">
        <div
          className="rail-progress-fill"
          style={{ width: `${progressValue}%` }}
        />
      </div>

      <div className="rail-step-list">
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

          const stateClass = `rail-state-${step.state}`;

          return (
            <div
              key={step.nodeRunId || step.title}
              ref={activeRunTab === "current" && activeStepIndex === index ? activeStepRef : undefined}
              className="rail-step-row"
            >
              {/* 时间轴连接线：已完成节点向下延伸为绿色渐变并叠加向下流光，其余为浅灰 */}
              {!isLast && (
                <div
                  className={`rail-flow-line ${isDone ? "rail-flow-line--done" : ""}`}
                />
              )}

              {/* 状态指示圆点：根据状态着色，失败显示告警图标 */}
              <button
                type="button"
                disabled={isFuturePending}
                onClick={() => onStepSelect(step, index)}
                aria-current={isSelected ? "step" : undefined}
                className={`rail-step-indicator ${stateClass} ${isSelected ? "rail-step-indicator--selected" : ""} ${!isFuturePending ? "rail-step-indicator--clickable" : "rail-step-indicator--disabled"}`}
              >
                {getStepIcon(step.kind, step.state)}
              </button>

              {/* 步骤信息卡片 */}
              <button
                type="button"
                disabled={isFuturePending}
                onClick={() => onStepSelect(step, index)}
                className={`rail-step-card ${isSelected ? `rail-step-card--selected ${stateClass}` : ""} ${isFuturePending ? "rail-step-card--disabled" : ""}`}
              >
                <div className="rail-step-card-header">
                  <strong className={`rail-step-title ${isSelected ? stateClass : ""}`}>
                    {step.title}
                  </strong>
                  {/* 执行中 / 等待中保留呼吸状态点，和左侧时间线的流动感保持一致。 */}
                  {(isRunning || isWaiting) && (
                    <span className={`rail-step-live-dot ${isWaiting ? "rail-state-waiting" : "rail-state-running"}`} aria-hidden="true" />
                  )}
                  {/* 失败节点用静态红点提示，不使用脉冲，避免误读为执行中 */}
                  {isFailed && (
                    <span className="rail-step-live-dot rail-state-failed" aria-hidden="true" />
                  )}
                </div>
                <small className={`rail-step-subtitle ${isFailed ? "rail-state-failed" : ""}`}>{step.subtitle}</small>
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
