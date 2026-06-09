import React from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { Play, RotateCw, Check, X, ArrowLeft, Ban } from "lucide-react";

interface StepActionBarProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  isRunCompleted: boolean;
  isRunFailed: boolean;
  isRunSaved: boolean;
  readOnly: boolean;
  onAdvance: () => void;
  onCompleteTodo: (comment: string) => void;
  onApprove: (comment: string) => void;
  onReject: (comment: string) => void;
  onRetry: () => void;
  onRollback: () => void;
  onBack: () => void;
  onInterrupt?: () => void;
}

export function StepActionBar({
  activeStep,
  isStreaming,
  isRunCompleted,
  isRunFailed,
  isRunSaved,
  readOnly,
  onAdvance,
  onCompleteTodo,
  onApprove,
  onReject,
  onRetry,
  onRollback,
  onBack,
  onInterrupt,
}: StepActionBarProps) {
  
  if (readOnly) {
    return (
      <div className="step-action-bar flex justify-end gap-3 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onBack}>
          返回列表
        </button>
      </div>
    );
  }

  // 1. Run Completed State
  if (isRunCompleted) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-emerald-600 dark:text-emerald-400 font-medium">✓ 流程已全部顺利完成</span>
        <div className="flex gap-3">
          <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onBack}>
            返回工作台
          </button>
        </div>
      </div>
    );
  }

  // 2. Run Failed State
  if (isRunFailed) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-rose-600 dark:text-rose-400 font-medium">
          节点执行发生错误{isRunSaved ? "" : "，请先保存任务后再重试"}
        </span>
        {isRunSaved && (
          <div className="flex gap-3">
            <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onRollback}>
              <ArrowLeft size={14} /> 回退上一步
            </button>
            <button type="button" className="sys-btn sys-btn--primary flex items-center gap-2 text-xs" onClick={onRetry}>
              <RotateCw size={14} /> 重试当前节点
            </button>
          </div>
        )}
      </div>
    );
  }

  // 3. Executing / Streaming State
  if (isStreaming) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <div className="flex items-center gap-2">
          <span className="flex h-2 w-2 relative">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
          </span>
          <span className="text-xs text-blue-600 dark:text-blue-400 font-medium">AI 智能体正在流式输出中...</span>
        </div>
        <button 
          type="button" 
          className="sys-btn sys-btn--default flex items-center gap-2 text-xs border border-slate-200 text-slate-500 hover:text-rose-600 hover:border-rose-300"
          onClick={onInterrupt}
        >
          <Ban size={14} /> 中断执行
        </button>
      </div>
    );
  }

  // 4. Pending agent / cluster step — needs manual advance to start execution
  if (
    activeStep.state === "pending"
    && (activeStep.kind === "agent" || activeStep.kind === "multiAgent")
  ) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-slate-500 dark:text-slate-400">
          当前节点尚未开始执行，点击下方按钮启动{activeStep.kind === "multiAgent" ? "智能体集群" : "智能体"}。
        </span>
        <button type="button" className="sys-btn sys-btn--primary step-advance-btn flex items-center gap-2 text-xs" onClick={onAdvance}>
          <Play size={14} fill="currentColor" /> 执行此步骤
        </button>
      </div>
    );
  }

  // 5. Completed but ready to advance to next step
  if (activeStep.state === "done") {
    const canRegenerate = isRunSaved && activeStep.allowsRegenerate;
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-slate-500 dark:text-slate-400">
          当前节点已执行完毕{canRegenerate ? "，可重新生成或继续推进。" : "，点击下一步继续推进。"}
        </span>
        <div className="flex items-center gap-3">
          {canRegenerate && (
            <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onRetry}>
              <RotateCw size={14} /> 重新生成
            </button>
          )}
          <button type="button" className="sys-btn sys-btn--primary step-advance-btn flex items-center gap-2 text-xs" onClick={onAdvance}>
            <Play size={14} fill="currentColor" /> 执行下一步
          </button>
        </div>
      </div>
    );
  }

  // 5. Waiting User Input
  if (activeStep.state === "waiting" && activeStep.kind === "input") {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-amber-600 dark:text-amber-400 font-medium">📝 请在上方表单中填写资料并提交</span>
      </div>
    );
  }

  // 6. Waiting Human Review / Approval
  if (activeStep.state === "waiting" && activeStep.kind === "approval") {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-amber-600 dark:text-amber-400 font-medium">🔍 正在等待人工审核本步骤结论</span>
        <div className="flex gap-3">
          <button type="button" className="sys-btn sys-btn--danger flex items-center gap-2 text-xs" onClick={() => onReject("拒绝并通过")}>
            <X size={14} /> 驳回
          </button>
          <button type="button" className="sys-btn sys-btn--primary flex items-center gap-2 text-xs" onClick={() => onApprove("审核通过")}>
            <Check size={14} /> 审核通过
          </button>
        </div>
      </div>
    );
  }

  // Fallback
  return null;
}
