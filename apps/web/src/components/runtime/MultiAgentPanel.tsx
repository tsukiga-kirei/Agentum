import React, { useState } from "react";
import type { RuntimePreviewStep, RunStreamState } from "../../types/runtime-types";
import { Users, Bot, ChevronDown, ChevronUp, Terminal } from "lucide-react";
import { MarkdownRenderer } from "./MarkdownRenderer";

interface MultiAgentPanelProps {
  activeStep: RuntimePreviewStep;
  clusterAgents: RunStreamState["clusterAgents"];
  isStreaming?: boolean;
}

export function MultiAgentPanel({
  activeStep,
  clusterAgents,
  isStreaming = false,
}: MultiAgentPanelProps) {
  const [expandedAgentIdx, setExpandedAgentIdx] = useState<number | null>(null);

  const configAgents = Array.isArray(activeStep.configSnapshot?.clusterAgents)
    ? (activeStep.configSnapshot?.clusterAgents as Array<Record<string, unknown>>)
    : [];

  const agents = clusterAgents.length > 0
    ? clusterAgents
    : configAgents.map((agent, idx) => ({
        index: idx,
        name: String(agent.name || `子智能体 ${idx + 1}`),
        status: "pending" as const,
        streamingText: "",
        outputSummary: "",
        toolCalls: [],
      }));

  const completedCount = agents.filter((a) => a.status === "completed").length;
  const runningCount = agents.filter((a) => a.status === "running").length;
  const totalCount = agents.length;
  const percent = totalCount > 0 ? Math.round((completedCount / totalCount) * 100) : 0;
  const stepPending = activeStep.state === "pending";
  const stepRunning = activeStep.state === "running" || isStreaming;

  function toggleExpand(index: number) {
    setExpandedAgentIdx(expandedAgentIdx === index ? null : index);
  }

  function pendingMessage() {
    if (stepPending) {
      return "尚未启动：请点击下方「执行此步骤」开始运行子智能体。";
    }
    if (stepRunning && runningCount === 0 && completedCount === 0) {
      return "集群节点已启动，正在初始化子智能体...";
    }
    return "等待调度执行";
  }

  return (
    <div className="space-y-4">
      <section className="bg-slate-50 dark:bg-slate-900/60 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
        <div className="flex justify-between items-center mb-3">
          <div className="flex items-center gap-2">
            <Users className="text-blue-500" size={18} />
            <h3 className="text-xs font-semibold text-slate-800 dark:text-slate-200">智能体集群进度</h3>
          </div>
          <small className="text-[10px] text-slate-500 dark:text-slate-400 font-medium">
            共 {totalCount} 个子智能体 · 已完成 {completedCount} · 运行中 {runningCount}
          </small>
        </div>
        {stepPending ? (
          <p className="text-xs text-amber-600 dark:text-amber-400 mb-3">
            智能体集群不会自动开始，需要人工点击「执行此步骤」后才会逐个运行子智能体。
          </p>
        ) : null}
        <div className="flex items-center gap-3">
          <div className="h-2 flex-1 bg-slate-200/60 dark:bg-slate-800 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-blue-500 to-indigo-500 rounded-full transition-all duration-300"
              style={{ width: `${percent}%` }}
            />
          </div>
          <span className="text-xs font-bold text-slate-700 dark:text-slate-350">{percent}%</span>
        </div>
      </section>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {agents.map((agent) => {
          const isSelected = expandedAgentIdx === agent.index;
          const isRunning = agent.status === "running";
          const isCompleted = agent.status === "completed";
          const isFailed = agent.status === "failed";

          return (
            <div
              key={agent.index}
              className={`rounded-xl border transition-all duration-300 flex flex-col ${
                isRunning
                  ? "bg-blue-50/10 border-blue-200 dark:bg-blue-950/10 dark:border-blue-900/50 shadow-sm ring-1 ring-blue-500/10"
                  : isCompleted
                  ? "bg-emerald-50/10 border-emerald-100 dark:bg-emerald-950/5 dark:border-emerald-950/20"
                  : "bg-white dark:bg-slate-950 border-slate-100 dark:border-slate-800"
              }`}
            >
              <header className="p-4 border-b border-slate-100 dark:border-slate-800/80 flex items-center justify-between gap-3 bg-slate-50/20 dark:bg-slate-900/10 rounded-t-xl">
                <div className="flex items-center gap-2 min-w-0">
                  <div className={`w-6 h-6 rounded-full flex items-center justify-center shrink-0 ${
                    isCompleted
                      ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-400"
                      : isRunning
                      ? "bg-blue-50 text-blue-600 dark:bg-blue-950 dark:text-blue-400"
                      : "bg-slate-100 text-slate-400 dark:bg-slate-800 dark:text-slate-600"
                  }`}>
                    <Bot size={13} />
                  </div>
                  <strong className="text-xs font-semibold text-slate-800 dark:text-slate-200 truncate">{agent.name}</strong>
                </div>

                <span className={`text-[9px] px-2 py-0.5 rounded-full font-medium ${
                  isCompleted
                    ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                    : isFailed
                    ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-450"
                    : isRunning
                    ? "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400 animate-pulse"
                    : "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
                }`}>
                  {isCompleted ? "已完成" : isFailed ? "执行失败" : isRunning ? "执行中" : "等待中"}
                </span>
              </header>

              <div className="p-4 flex-1 flex flex-col justify-between space-y-3 min-h-[120px]">
                <div className="space-y-2">
                  {isRunning && agent.streamingText ? (
                    <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-2.5 border border-slate-100 dark:border-slate-800 max-h-[150px] overflow-y-auto">
                      <MarkdownRenderer content={agent.streamingText} compact />
                      <span className="inline-block w-1.5 h-3 bg-blue-500 dark:bg-blue-400 ml-0.5 animate-pulse" />
                    </div>
                  ) : isCompleted ? (
                    <MarkdownRenderer content={agent.outputSummary} compact className="line-clamp-4" />
                  ) : (
                    <p className="text-xs text-slate-400 dark:text-slate-500 italic">
                      {isRunning ? "正在初始化任务变量..." : pendingMessage()}
                    </p>
                  )}
                </div>

                {agent.toolCalls && agent.toolCalls.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5 pt-2 border-t border-slate-100 dark:border-slate-800/60">
                    {agent.toolCalls.map((tool) => (
                      <span
                        key={tool.id}
                        className={`inline-flex items-center gap-1 text-[9px] px-1.5 py-0.5 rounded border ${
                          tool.status === "done"
                            ? "bg-emerald-50/50 border-emerald-100 text-emerald-600 dark:bg-emerald-950/20 dark:border-emerald-900 dark:text-emerald-400"
                            : tool.status === "error"
                            ? "bg-rose-50/50 border-rose-100 text-rose-600 dark:bg-rose-950/20 dark:border-rose-900 dark:text-rose-450"
                            : "bg-blue-50/50 border-blue-100 text-blue-600 dark:bg-blue-950/20 dark:border-blue-900 dark:text-blue-400 animate-pulse"
                        }`}
                      >
                        <Terminal size={10} />
                        {tool.name}
                      </span>
                    ))}
                  </div>
                ) : null}
              </div>

              {isCompleted && agent.outputSummary ? (
                <button
                  type="button"
                  onClick={() => toggleExpand(agent.index)}
                  className="w-full text-center py-2 text-[10px] font-medium text-slate-500 hover:text-blue-600 dark:text-slate-400 dark:hover:text-blue-400 hover:bg-slate-50/40 dark:hover:bg-slate-900/30 border-t border-slate-100 dark:border-slate-800 rounded-b-xl flex items-center justify-center gap-1"
                >
                  {isSelected ? "收起详细报告" : "展开详细报告"}
                  {isSelected ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                </button>
              ) : null}

              {isSelected && isCompleted ? (
                <div className="p-4 bg-slate-50/40 dark:bg-slate-900/10 border-t border-slate-100 dark:border-slate-800 max-h-[250px] overflow-y-auto">
                  <MarkdownRenderer content={agent.outputSummary} compact />
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}
