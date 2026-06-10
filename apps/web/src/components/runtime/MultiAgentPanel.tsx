import React, { useMemo, useState, useEffect } from "react";
import type { RuntimePreviewStep, RunStreamState } from "../../types/runtime-types";
import { Users, Bot, Terminal } from "lucide-react";
import { Drawer } from "antd";
import { useAuthStore } from "../../stores/authStore";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { mergeClusterAgents } from "../../utils/clusterAgentsMerge";
import { formatRuntimeErrorMessage } from "../../utils/runtimeErrors";
import { pickBestAgentOutput } from "../../utils/agentOutputText";
import { formatDisplayPrompt, resolveSystemDisplayPrompt, resolveUserDisplayPrompt } from "../../utils/resolveDisplayPrompts";

interface MultiAgentPanelProps {
  activeStep: RuntimePreviewStep;
  clusterAgents: RunStreamState["clusterAgents"];
  isStreaming?: boolean;
}

type DrawerAgent = {
  index: number;
  name: string;
  status: "pending" | "running" | "completed" | "failed";
  streamingText: string;
  outputSummary: string;
  errorMessage?: string;
  toolCalls: RunStreamState["clusterAgents"][number]["toolCalls"];
  systemPrompt: string;
  userPrompt: string;
};

export function MultiAgentPanel({
  activeStep,
  clusterAgents,
  isStreaming = false,
}: MultiAgentPanelProps) {
  const [selectedAgent, setSelectedAgent] = useState<DrawerAgent | null>(null);
  const themeMode = useAuthStore((s) => s.themeMode);

  const configAgents = Array.isArray(activeStep.configSnapshot?.clusterAgents)
    ? (activeStep.configSnapshot?.clusterAgents as Array<Record<string, unknown>>)
    : [];

  const agents = useMemo((): DrawerAgent[] => {
    const merged = mergeClusterAgents({
      configAgents,
      outputs: activeStep.outputs,
      streamAgents: clusterAgents,
      stepState: activeStep.state,
      stepRunning: activeStep.state === "running" || isStreaming,
    });

    return merged.map((agent) => {
      const config = configAgents[agent.index] ?? configAgents.find(
        (item) => String(item.name || item.label || "") === agent.name
      );
      return {
        ...agent,
        systemPrompt: config ? resolveSystemDisplayPrompt(config) : resolveSystemDisplayPrompt(undefined),
        userPrompt: config ? resolveUserDisplayPrompt(config) : resolveUserDisplayPrompt(undefined),
      };
    });
  }, [configAgents, clusterAgents, activeStep.outputs, activeStep.state, isStreaming]);

  useEffect(() => {
    if (!selectedAgent) {
      return;
    }
    const fresh = agents.find((agent) => agent.index === selectedAgent.index);
    if (fresh) {
      setSelectedAgent(fresh);
    }
  }, [agents, selectedAgent?.index]);

  const completedCount = agents.filter((a) => a.status === "completed").length;
  const runningCount = agents.filter((a) => a.status === "running").length;
  const totalCount = agents.length;
  const percent = totalCount > 0 ? Math.round((completedCount / totalCount) * 100) : 0;
  const stepPending = activeStep.state === "pending";
  const stepRunning = activeStep.state === "running" || isStreaming;

  function pendingMessage(agentStatus: DrawerAgent["status"]) {
    if (stepPending) {
      return "等待集群节点启动...";
    }
    if (stepRunning && agentStatus === "pending") {
      return "等待调度执行";
    }
    if (agentStatus === "running") {
      return "正在执行...";
    }
    return "等待调度执行";
  }

  return (
    <div className="space-y-4">
      <section className="bg-slate-50 dark:bg-slate-900/60 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
        <div className="flex justify-between items-center mb-3">
          <div className="flex items-center gap-2">
            <Users className="text-blue-500" size={18} />
            <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">智能体集群进度</h3>
          </div>
          <small className="text-xs text-slate-500 dark:text-slate-400 font-medium">
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

      <div className="grid grid-cols-1 gap-4">
        {agents.map((agent) => {
          const isRunning = agent.status === "running";
          const isCompleted = agent.status === "completed";
          const isFailed = agent.status === "failed";
          const previewText = resolveClusterAgentPreview(agent);

          return (
            <div
              key={agent.index}
              onClick={() => setSelectedAgent(agent)}
              className={`rounded-xl border cursor-pointer hover:shadow-md transition-all duration-300 flex flex-col ${
                isRunning
                  ? "bg-blue-50/10 border-blue-200 dark:bg-blue-950/10 dark:border-blue-900/50 shadow-sm ring-1 ring-blue-500/10"
                  : isCompleted
                  ? "bg-emerald-50/10 border-emerald-100 dark:bg-emerald-950/5 dark:border-emerald-950/20"
                  : isFailed
                ? "bg-rose-50/10 border-rose-200 dark:bg-rose-950/10 dark:border-rose-900/50"
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
                  <strong className="text-sm font-semibold text-slate-800 dark:text-slate-200 truncate">{agent.name}</strong>
                </div>

                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
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
                  {previewText ? (
                    <div className="max-h-[280px] overflow-y-auto">
                      <MarkdownRenderer content={previewText} />
                      {isRunning ? (
                        <span className="inline-block w-1.5 h-3 bg-blue-500 dark:bg-blue-400 ml-0.5 animate-pulse" />
                      ) : null}
                    </div>
                  ) : (
                    <p className="text-sm text-slate-400 dark:text-slate-500 italic">
                      {pendingMessage(agent.status)}
                    </p>
                  )}
                </div>

                {agent.toolCalls && agent.toolCalls.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5 pt-2 border-t border-slate-100 dark:border-slate-800/60">
                    {agent.toolCalls.map((tool) => (
                      <span
                        key={tool.id}
                        className={`inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded border ${
                          tool.status === "done"
                            ? "bg-emerald-50/50 border-emerald-100 text-emerald-600 dark:bg-emerald-950/20 dark:border-emerald-900 dark:text-emerald-400"
                            : tool.status === "error"
                            ? "bg-rose-50/50 border-rose-100 text-rose-600 dark:bg-rose-950/20 dark:border-rose-900 dark:text-rose-455"
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
            </div>
          );
        })}
      </div>

      {selectedAgent ? (
        <Drawer
          title={`${selectedAgent.name} · 详情`}
          width={640}
          open
          onClose={() => setSelectedAgent(null)}
          rootClassName={themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"}
        >
          <div className="space-y-4 sys-drawer-section">
            <section className="bg-slate-50 dark:bg-slate-900/40 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
              <span className="text-xs text-slate-400 font-bold block mb-1">执行状态</span>
              <span className={`inline-block text-xs px-2.5 py-0.5 rounded-full font-semibold ${
                selectedAgent.status === "completed"
                  ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/45 dark:text-emerald-400"
                  : selectedAgent.status === "failed"
                  ? "bg-rose-50 text-rose-600 dark:bg-rose-950/45 dark:text-rose-400"
                  : selectedAgent.status === "running"
                  ? "bg-blue-50 text-blue-600 dark:bg-blue-950/45 dark:text-blue-400 animate-pulse"
                  : "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
              }`}>
                {selectedAgent.status === "completed" ? "已完成" : selectedAgent.status === "failed" ? "执行失败" : selectedAgent.status === "running" ? "执行中" : "等待中"}
              </span>
            </section>

            <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
              <span className="text-xs text-slate-400 font-bold block mb-2">系统提示词</span>
              <MarkdownRenderer content={formatDisplayPrompt(selectedAgent.systemPrompt)} compact />
            </section>

            <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
              <span className="text-xs text-slate-400 font-bold block mb-2">用户提示词</span>
              <MarkdownRenderer content={formatDisplayPrompt(selectedAgent.userPrompt)} compact />
            </section>

            <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
              <span className="text-xs text-slate-400 font-bold block mb-2">
                {selectedAgent.status === "failed" ? "失败原因" : "执行输出"}
              </span>
              {selectedAgent.status === "failed" ? (
                <p className="text-sm text-rose-700 dark:text-rose-300 whitespace-pre-wrap">
                  {formatRuntimeErrorMessage(undefined, selectedAgent.errorMessage || selectedAgent.outputSummary)}
                </p>
              ) : resolveClusterAgentPreview(selectedAgent) ? (
                <div className="text-sm bg-slate-50 dark:bg-slate-900 p-3 rounded-lg border border-slate-100 dark:border-slate-800">
                  <MarkdownRenderer content={resolveClusterAgentPreview(selectedAgent)} />
                  {selectedAgent.status === "running" ? (
                    <span className="inline-block w-1.5 h-3.5 bg-blue-500 dark:bg-blue-400 ml-1 animate-pulse" />
                  ) : null}
                </div>
              ) : (
                <p className="text-sm text-slate-400 italic">暂无输出结果</p>
              )}
            </section>

            {selectedAgent.toolCalls && selectedAgent.toolCalls.length > 0 ? (
              <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
                <span className="text-xs text-slate-400 font-bold block mb-3">工具/MCP 调用记录</span>
                <div className="space-y-2">
                  {selectedAgent.toolCalls.map((tool) => (
                    <div
                      key={tool.id}
                      className="p-3 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50/20 dark:bg-slate-900/20 text-xs flex justify-between items-center"
                    >
                      <div>
                        <div className="flex items-center gap-1.5">
                          <span className="text-[10px] font-bold text-slate-450 uppercase">{tool.kind}</span>
                          <strong className="text-xs text-slate-850 dark:text-slate-200">{tool.name}</strong>
                        </div>
                        {tool.resultSummary ? <p className="text-[10px] text-slate-400 mt-1 font-mono">{tool.resultSummary}</p> : null}
                      </div>
                      <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                        tool.status === "done"
                          ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                          : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                      }`}>
                        {tool.statusLabel}
                      </span>
                    </div>
                  ))}
                </div>
              </section>
            ) : null}
          </div>
        </Drawer>
      ) : null}
    </div>
  );
}

function resolveClusterAgentPreview(agent: {
  status: DrawerAgent["status"];
  streamingText: string;
  outputSummary: string;
  errorMessage?: string;
}): string {
  if (agent.status === "failed") {
    return agent.errorMessage || agent.outputSummary || "";
  }
  if (agent.status === "running" || agent.status === "completed") {
    return pickBestAgentOutput(agent.streamingText, agent.outputSummary);
  }
  return "";
}
