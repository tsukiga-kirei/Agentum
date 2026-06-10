import React from "react";
import type { RuntimePreviewStep } from "../../types/runtime-types";
import { Play, RotateCw, Check, X, Ban, Send, History } from "lucide-react";

interface StepActionBarProps {
  activeStep: RuntimePreviewStep;
  isStreaming: boolean;
  isAdvancing?: boolean;
  isReconnecting?: boolean;
  isRunCompleted: boolean;
  /** 当前步骤是否为流程最后一步（完成后按钮文案为「确认完成」） */
  isLastStep?: boolean;
  /** 节点被用户主动中断（canceled）：仅展示「重新执行」 */
  stepCanceled?: boolean;
  /** 节点执行失败（failed）：仅展示「恢复进度」 */
  stepFailed?: boolean;
  /** 前端看门狗判定执行异常（SSE 持续失败 / 长时间无心跳）：按被动失败处理 */
  watchdogStale?: boolean;
  /** 被动恢复场景展示在按钮左侧的人类可读错误原因 */
  failureMessage?: string | null;
  readOnly: boolean;
  onAdvance: () => void;
  onCompleteTodo: (comment: string) => void;
  onApprove: (comment: string) => void;
  onReject: (comment: string) => void;
  onRetry: () => void;
  onBack: () => void;
  onInterrupt?: () => void;
  /** 主动「重新执行」：清空整个节点数据从头重跑 */
  onRestart?: () => void;
  /** 被动「恢复进度」：保留已成功子智能体，仅重跑失败/未完成部分 */
  onRecover?: () => void;
}

export function StepActionBar({
  activeStep,
  isStreaming,
  isAdvancing = false,
  isReconnecting = false,
  isRunCompleted,
  isLastStep = false,
  stepCanceled = false,
  stepFailed = false,
  watchdogStale = false,
  failureMessage,
  readOnly,
  onAdvance,
  onCompleteTodo: _onCompleteTodo,
  onApprove,
  onReject,
  onRetry,
  onBack,
  onInterrupt,
  onRestart,
  onRecover,
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

  // 按钮互斥矩阵：主动中断（canceled）优先级最高，只出「重新执行」；
  // 其后是被动失败（failed / 看门狗判异常），只出「恢复进度」并在左侧展示错误原因。
  if (stepCanceled && !isAdvancing && !isStreaming) {
    return (
      <div className="step-action-bar flex justify-between items-center p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">
          本步骤已被中断，数据已清空；重新执行将从头完整执行本步骤。
        </span>
        <button
          type="button"
          className="sys-btn sys-btn--primary flex items-center gap-2 text-xs"
          onClick={onRestart}
        >
          <RotateCw size={14} /> 重新执行
        </button>
      </div>
    );
  }

  if ((stepFailed || watchdogStale) && !isAdvancing && !isStreaming) {
    return (
      <div className="step-action-bar flex justify-between items-center gap-4 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="min-w-0 text-xs text-rose-600 dark:text-rose-400 font-medium leading-relaxed">
          {failureMessage || "节点执行发生异常，可恢复进度继续执行。"}
        </span>
        <button
          type="button"
          className="sys-btn sys-btn--primary shrink-0 flex items-center gap-2 text-xs"
          onClick={onRecover}
        >
          <History size={14} /> 恢复进度
        </button>
      </div>
    );
  }

  if (isReconnecting) {
    return (
      <div className="step-action-bar flex justify-end p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        <span className="text-xs text-blue-600 dark:text-blue-400 font-medium">正在连接执行流…</span>
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

  if (activeStep.state === "done") {
    const canRegenerate = activeStep.allowsRegenerate;
    return (
      <div className="step-action-bar flex justify-end items-center gap-3 p-4 border-t border-slate-100 dark:border-slate-800 bg-white/85 dark:bg-slate-950/85 backdrop-blur-md rounded-b-xl">
        {canRegenerate ? (
          <button type="button" className="sys-btn sys-btn--default flex items-center gap-2 text-xs" onClick={onRetry}>
            <RotateCw size={14} /> 重新执行
          </button>
        ) : null}
        <button
          type="button"
          className="sys-btn sys-btn--primary step-advance-btn flex items-center gap-2 text-xs disabled:opacity-60"
          onClick={onAdvance}
          disabled={isAdvancing}
        >
          <Play size={14} fill="currentColor" /> {isLastStep ? "确认完成" : "执行下一步"}
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
