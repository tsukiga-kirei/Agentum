import React from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { formatRuntimeErrorMessage } from "../../utils/runtimeErrors";
import { Play, RotateCw, Check, X, ArrowLeft, Ban, Send } from "lucide-react";

interface StepActionBarProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  isAdvancing?: boolean;
  streamInterrupted?: boolean;
  isWaitingBackendProgress?: boolean;
  isRunCompleted: boolean;
  isRunFailed: boolean;
  readOnly: boolean;
  onAdvance: () => void;
  onCompleteTodo: (comment: string) => void;
  onApprove: (comment: string) => void;
  onReject: (comment: string) => void;
  onRetry: () => void;
  onRollback: () => void;
  onBack: () => void;
  onInterrupt?: () => void;
  onRestartStream?: () => void;
  onForceReExecute?: () => void;
}

export function StepActionBar({
  activeStep,
  isStreaming,
  isAdvancing = false,
  streamInterrupted = false,
  isWaitingBackendProgress: _isWaitingBackendProgress = false,
  isRunCompleted,
  isRunFailed,
  readOnly,
  onAdvance,
  onCompleteTodo,
  onApprove,
  onReject,
  onRetry,
  onRollback,
  onBack,
  onInterrupt,
  onRestartStream,
  onForceReExecute,
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
    const errorCode = activeStep.outputs?.find((field) => field.label === "errorCode")?.value;
    const errorMessage = activeStep.outputs?.find((field) => field.label === "errorMessage")?.value;
    const failureMessage = formatRuntimeErrorMessage(errorCode, errorMessage);
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-rose-600 dark:text-rose-400 font-medium">
          节点执行发生错误{failureMessage ? `：${failureMessage}` : ""}
        </span>
        <div className="flex gap-3">
          <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onRollback}>
            <ArrowLeft size={14} /> 回退上一步
          </button>
          <button type="button" className="sys-btn sys-btn--primary flex items-center gap-2 text-xs" onClick={onRetry}>
            <RotateCw size={14} /> 重试当前节点
          </button>
        </div>
      </div>
    );
  }

  // 3. 用户中断 SSE 后暂停，需手动重新连接或从当前节点重新开始。
  if (streamInterrupted && !isStreaming && !isAdvancing) {
    const restartLabel = activeStep.state === "running" ? "重新连接" : "重新开始";
    return (
      <div className="step-action-bar flex justify-end gap-2 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button
          type="button"
          className="sys-btn sys-btn--primary flex items-center gap-2 text-xs"
          onClick={onRestartStream}
        >
          <RotateCw size={14} /> {restartLabel}
        </button>
      </div>
    );
  }

  // 3b. 节点 running 但页面尚未收到流式：多为刷新后重连，后台可能仍在执行。
  if (
    activeStep.state === "running"
    && (activeStep.kind === "agent" || activeStep.kind === "multiAgent")
    && !isStreaming
    && !isAdvancing
  ) {
    return (
      <div className="step-action-bar flex justify-end gap-2 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button
          type="button"
          className="sys-btn sys-btn--default flex items-center gap-2 text-xs"
          onClick={onRestartStream}
        >
          <RotateCw size={14} /> 重新连接
        </button>
        <button
          type="button"
          className="sys-btn sys-btn--primary flex items-center gap-2 text-xs"
          onClick={onForceReExecute ?? onRestartStream}
        >
          <Play size={14} fill="currentColor" /> 重新执行
        </button>
      </div>
    );
  }

  // 4. Executing / Streaming State
  if (isStreaming || isAdvancing) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <div className="flex items-center gap-2">
          <span className="flex h-2 w-2 relative">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
          </span>
          <span className="text-xs text-blue-600 dark:text-blue-400 font-medium">
            {isAdvancing && !isStreaming ? "启动中…" : "执行中…"}
          </span>
        </div>
        {isStreaming ? (
          <button
            type="button"
            className="sys-btn sys-btn--default flex items-center gap-2 text-xs border border-slate-200 text-slate-500 hover:text-rose-600 hover:border-rose-300"
            onClick={onInterrupt}
          >
            <Ban size={14} /> 中断执行
          </button>
        ) : null}
      </div>
    );
  }

  // 5. Pending step — delivery 需先预览再手动执行；智能体类节点由页面自动触发启动。
  if (activeStep.state === "pending") {
    if (activeStep.kind === "agent" || activeStep.kind === "multiAgent") {
      return (
        <div className="step-action-bar flex justify-end p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
          <span className="text-xs text-blue-600 dark:text-blue-400 font-medium">
            {isAdvancing ? "启动中…" : "等待启动…"}
          </span>
        </div>
      );
    }

    return (
      <div className="step-action-bar flex justify-end p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button
          type="button"
          className="sys-btn sys-btn--primary step-advance-btn flex items-center gap-2 text-xs disabled:opacity-60"
          onClick={onAdvance}
          disabled={isAdvancing}
        >
          <Play size={14} fill="currentColor" /> 执行此步骤
        </button>
      </div>
    );
  }

  // 6. Completed but ready to advance to next step — 需用户确认后再推进。
  if (activeStep.state === "done") {
    const canRegenerate = activeStep.allowsRegenerate;
    return (
      <div className="step-action-bar flex justify-end items-center gap-3 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        {canRegenerate ? (
          <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onRetry}>
            <RotateCw size={14} /> 重新生成
          </button>
        ) : null}
        <button
          type="button"
          className="sys-btn sys-btn--primary step-advance-btn flex items-center gap-2 text-xs disabled:opacity-60"
          onClick={onAdvance}
          disabled={isAdvancing}
        >
          <Play size={14} fill="currentColor" /> 执行下一步
        </button>
      </div>
    );
  }

  // 7. Waiting User Input
  if (activeStep.state === "waiting" && activeStep.kind === "input") {
    return (
      <div className="step-action-bar flex justify-end p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button
          type="submit"
          form="workbench-user-input-form"
          className="sys-btn sys-btn--primary flex items-center gap-2 text-sm px-5 py-2"
        >
          <Send size={14} />
          提交资料并继续
        </button>
      </div>
    );
  }

  // 8. Waiting Human Review / Approval
  if (activeStep.state === "waiting" && activeStep.kind === "approval") {
    return (
      <div className="step-action-bar flex justify-end gap-3 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button type="button" className="sys-btn sys-btn--danger flex items-center gap-2 text-xs" onClick={() => onReject("拒绝并通过")}>
            <X size={14} /> 驳回
          </button>
          <button type="button" className="sys-btn sys-btn--primary flex items-center gap-2 text-xs" onClick={() => onApprove("审核通过")}>
            <Check size={14} /> 审核通过
          </button>
      </div>
    );
  }

  // Fallback
  return null;
}
