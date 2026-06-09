import React, { useState } from "react";
import type { RuntimePreviewStep, RunStreamState, RuntimeCapabilityItem } from "../../types/runtime-types";
import { Users, Bot, CheckCircle, Clock, ChevronDown, ChevronUp, Terminal, Cpu } from "lucide-react";

interface MultiAgentPanelProps {
  activeStep: RuntimePreviewStep;
  clusterAgents: RunStreamState["clusterAgents"];
}

export function MultiAgentPanel({
  activeStep,
  clusterAgents,
}: MultiAgentPanelProps) {
  const [expandedAgentIdx, setExpandedAgentIdx] = useState<number | null>(null);

  // If we don't have active stream data, fallback to rendering static agent lists from node configuration
  const agents = clusterAgents.length > 0 ? clusterAgents : (
    (activeStep.configSnapshot?.clusterAgents as any[]) || []
  ).map((a: any, idx: number) => ({
    index: idx,
    name: a.name || "子智能体",
    status: "pending" as const,
    streamingText: "",
    outputSummary: a.summary || "等待上游执行",
    toolCalls: [],
  }));

  const completedCount = agents.filter((a) => a.status === "completed").length;
  const runningCount = agents.filter((a) => a.status === "running").length;
  const totalCount = agents.length;
  const percent = totalCount > 0 ? Math.round((completedCount / totalCount) * 100) : 0;

  function toggleExpand(index: number) {
    setExpandedAgentIdx(expandedAgentIdx === index ? null : index);
  }

  return (
    <div className="space-y-4">
      {/* 1. Cluster Stats Summary Card */}
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

      {/* 2. Agents Parallel Card Grid */}
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
              {/* Card Header */}
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

              {/* Card Body - Content Log */}
              <div className="p-4 flex-1 flex flex-col justify-between space-y-3 min-h-[120px]">
                <div className="space-y-2">
                  {isRunning && agent.streamingText ? (
                    <div className="bg-slate-50 dark:bg-slate-900 rounded-lg p-2.5 border border-slate-100 dark:border-slate-800 font-mono text-[10px] leading-relaxed text-slate-600 dark:text-slate-350 max-h-[150px] overflow-y-auto whitespace-pre-wrap">
                      {agent.streamingText}
                      <span className="inline-block w-1.5 h-3 bg-blue-500 dark:bg-blue-400 ml-0.5 animate-pulse" />
                    </div>
                  ) : isCompleted ? (
                    <p className="text-xs text-slate-600 dark:text-slate-300 leading-relaxed line-clamp-4">
                      {agent.outputSummary}
                    </p>
                  ) : (
                    <p className="text-xs text-slate-400 dark:text-slate-500 italic">
                      {isRunning ? "正在初始化任务变量..." : "等待上游智能体节点就绪"}
                    </p>
                  )}
                </div>

                {/* Card Sub-tools Dock */}
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

              {/* Toggle Detail Button */}
              {isCompleted && agent.outputSummary && (
                <button
                  type="button"
                  onClick={() => toggleExpand(agent.index)}
                  className="w-full text-center py-2 text-[10px] font-medium text-slate-500 hover:text-blue-600 dark:text-slate-400 dark:hover:text-blue-400 hover:bg-slate-50/40 dark:hover:bg-slate-900/30 border-t border-slate-100 dark:border-slate-800 rounded-b-xl flex items-center justify-center gap-1"
                >
                  {isSelected ? "收起详细报告" : "展开详细报告"}
                  {isSelected ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                </button>
              )}

              {/* Collapsed Detailed Output */}
              {isSelected && isCompleted && (
                <div className="p-4 bg-slate-50/40 dark:bg-slate-900/10 border-t border-slate-100 dark:border-slate-800 text-xs text-slate-600 dark:text-slate-350 leading-relaxed whitespace-pre-wrap max-h-[250px] overflow-y-auto font-sans">
                  {agent.outputSummary}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
