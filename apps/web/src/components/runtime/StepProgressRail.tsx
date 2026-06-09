import React from "react";
import type { RuntimePreviewStep, RuntimePreview } from "../../types/runtime-types";
import { Zap, FileEdit, Bot, Users, Search, Package, Check } from "lucide-react";

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
  
  function getStepIcon(kind: string, state: string) {
    if (state === "done") return <Check size={14} className="text-white" />;
    
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

  return (
    <aside className="workbench-task-rail border-r border-slate-200 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-900/50 p-4 w-80 flex-shrink-0 flex flex-col" aria-label="任务流程进度">
      <div className="flex justify-between items-center mb-2">
        <strong className="text-sm font-semibold text-slate-800 dark:text-slate-200">流程进度</strong>
        <span className="text-xs font-medium text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-950/50 px-2 py-0.5 rounded-full">{preview.progress}%</span>
      </div>
      <div className="h-1.5 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden mb-6">
        <div 
          className="h-full bg-gradient-to-r from-blue-500 to-indigo-500 rounded-full transition-all duration-500" 
          style={{ width: `${preview.progress}%` }} 
        />
      </div>

      <div className="flex-1 overflow-y-auto space-y-4 pr-1">
        {preview.steps.map((step, index) => {
          const isSelected = selectedStepIndex === index || (activeRunTab === "current" && activeStepIndex === index);
          const isPending = step.state === "pending";
          const isCurrent = step.state === "running" || step.state === "waiting" || step.state === "failed";
          const isDone = step.state === "done";
          const isFailed = step.state === "failed";
          
          return (
            <div key={step.nodeRunId || step.title} className="relative flex gap-3 group">
              {/* Timeline Connector Line */}
              {index < preview.steps.length - 1 && (
                <div 
                  className={`absolute left-[15px] top-8 bottom-0 w-0.5 -mb-4 transition-colors duration-300 ${
                    isDone ? "bg-emerald-500 dark:bg-emerald-600" : "bg-slate-200 dark:bg-slate-800"
                  }`} 
                />
              )}

              {/* Step Status Icon Indicator */}
              <button
                type="button"
                disabled={isPending}
                onClick={() => onStepSelect(step, index)}
                className={`w-8 h-8 rounded-full flex items-center justify-center border transition-all duration-300 z-10 ${
                  isDone 
                    ? "bg-emerald-500 border-emerald-500 text-white shadow-sm shadow-emerald-500/20" 
                    : isFailed 
                    ? "bg-rose-500 border-rose-500 text-white shadow-sm shadow-rose-500/20"
                    : isCurrent 
                    ? "bg-blue-50 border-blue-500 text-blue-600 dark:bg-blue-950/40 dark:border-blue-500 dark:text-blue-400 shadow-sm shadow-blue-500/10" 
                    : "bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-800 text-slate-400 dark:text-slate-600"
                } ${isSelected ? "ring-2 ring-blue-500/30 border-blue-500 scale-105" : ""}`}
              >
                {getStepIcon(step.kind, step.state)}
              </button>

              {/* Step Info Content Card */}
              <button
                type="button"
                disabled={isPending}
                onClick={() => onStepSelect(step, index)}
                className={`flex-1 text-left p-3 rounded-lg border transition-all duration-200 ${
                  isSelected 
                    ? "bg-white dark:bg-slate-900 border-blue-500 dark:border-blue-500 shadow-sm shadow-blue-500/5" 
                    : isPending 
                    ? "opacity-50 cursor-not-allowed border-transparent" 
                    : "bg-white/40 dark:bg-slate-950/40 border-transparent hover:border-slate-200 dark:hover:border-slate-800 hover:bg-white dark:hover:bg-slate-900"
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <strong className={`text-xs font-semibold block leading-tight transition-colors duration-200 ${
                    isSelected ? "text-blue-600 dark:text-blue-400" : "text-slate-800 dark:text-slate-200"
                  }`}>
                    {step.title}
                  </strong>
                  {isCurrent && (
                    <span className="flex h-1.5 w-1.5 relative">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500"></span>
                    </span>
                  )}
                </div>
                <small className="text-[10px] text-slate-500 dark:text-slate-400 block mt-1 leading-normal">{step.subtitle}</small>
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
