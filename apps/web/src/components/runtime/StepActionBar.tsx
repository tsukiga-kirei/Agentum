import React from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { formatRuntimeErrorMessage } from "../../utils/runtimeErrors";
import { Play, RotateCw, Check, X, ArrowLeft, Ban, Send } from "lucide-react";

interface StepActionBarProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  isAdvancing?: boolean;
  streamInterrupted?: boolean;
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
  onRegenerateCurrent?: () => void;
}

export function StepActionBar({
  activeStep,
  isStreaming,
  isAdvancing = false,
  streamInterrupted = false,
  isRunCompleted,
  isRunFailed,
  readOnly,
  onAdvance,
  onCompleteTodo: _onCompleteTodo,
  onApprove,
  onReject,
  onRetry,
  onRollback,
  onBack,
  onInterrupt,
  onRegenerateCurrent,
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

  // 中断后或后台仍在跑但前端未连流：仅提供「重新生成」整步重做，不提供重连/重新执行。
  const showRegenerateCurrent =
    (streamInterrupted
      || (activeStep.state === "running" && (activeStep.kind === "agent" || activeStep.kind === "multiAgent")))
    && !isStreaming
    && !isAdvancing;

  if (showRegenerateCurrent) {
    return (
      <div className="step-action-bar flex justify-end gap-2 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <button
          type="button"
          className="sys-btn sys-btn--primary flex items-center gap-2 text-xs"
          onClick={onRegenerateCurrent}
        >
          <RotateCw size={14} /> 重新生成
        </button>
      </div>
    );
  }

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

  if (activeStep.state === "pending") {
    if (activeStep.kind === "agent" || activeStep.kind === "multiAgent") {
      return (
        <div className="step-action-bar flex justify-end p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
          <span className="text-xs text-blue-600 dark:text-blue-400 font-medium">
            {isAdvancing ? "启动中…" : streamInterrupted ? "已中断，可点击重新生成整步执行" : "等待启动…"}
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

  return null;
}
