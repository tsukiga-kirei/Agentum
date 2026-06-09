import React, { useState } from "react";
import type { RuntimePreviewStep, RuntimeCapabilityItem, AgentPhase } from "../../types/runtime-types";
import { Bot, Sparkles, Terminal, FileText, ChevronDown, ChevronUp } from "lucide-react";
import { MarkdownRenderer } from "./MarkdownRenderer";

interface AgentChatPanelProps {
  activeStep: RuntimePreviewStep;
  streamingText: string;
  isStreaming: boolean;
  currentPhase: AgentPhase | null;
  toolCalls: RuntimeCapabilityItem[];
}

export function AgentChatPanel({
  activeStep,
  streamingText,
  isStreaming,
  currentPhase,
  toolCalls,
}: AgentChatPanelProps) {
  const [showPromptSnapshot, setShowPromptSnapshot] = useState(false);

  const mergedMessages = [...(activeStep.chatMessages || [])];
  
  // Append streaming chunk if actively streaming
  if (isStreaming && streamingText) {
    const lastMsg = mergedMessages[mergedMessages.length - 1];
    if (lastMsg && lastMsg.role === "assistant" && lastMsg.streaming) {
      mergedMessages[mergedMessages.length - 1] = {
        ...lastMsg,
        content: streamingText,
      };
    } else {
      mergedMessages.push({
        id: "streaming-message",
        role: "assistant",
        author: activeStep.title || "智能体",
        content: streamingText,
        streaming: true,
      });
    }
  }

  // Get active phase info for the thinking loader
  const phase = currentPhase || activeStep.agentPhase || "preparing";

  const phaseDetails = [
    { key: "preparing", label: "准备阶段", desc: "装配输入变量与加载提示词模板" },
    { key: "tool_calling", label: "工具调用", desc: "智能体正在调用 MCP/Skill 获取实时数据" },
    { key: "model_calling", label: "模型推理", desc: "正在请求大语言模型生成业务分析..." },
    { key: "validating", label: "输出验证", desc: "校验响应格式与脱敏规则" },
  ];

  const activePhaseIndex = phaseDetails.findIndex((p) => p.key === phase);

  return (
    <div className="space-y-4">
      {/* 1. Header & Thinking Process Steps */}
      <section className="bg-slate-50 dark:bg-slate-900/60 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
        <div className="flex items-center gap-2 mb-3">
          <Bot className="text-blue-500" size={18} />
          <h3 className="text-xs font-semibold text-slate-800 dark:text-slate-200">执行过程</h3>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          {phaseDetails.map((pd, index) => {
            const isCompleted = index < activePhaseIndex || phase === "completed";
            const isActive = index === activePhaseIndex && phase !== "completed" && phase !== "failed";
            const isPending = index > activePhaseIndex && phase !== "completed";

            return (
              <div 
                key={pd.key} 
                className={`p-2.5 rounded-lg border transition-all duration-300 ${
                  isActive 
                    ? "bg-blue-50/50 border-blue-200 dark:bg-blue-950/20 dark:border-blue-900 shadow-sm" 
                    : isCompleted 
                    ? "bg-emerald-50/20 border-emerald-100 dark:bg-emerald-950/10 dark:border-emerald-950/30" 
                    : "bg-white dark:bg-slate-950 border-slate-100 dark:border-slate-850"
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold ${
                    isCompleted 
                      ? "bg-emerald-500 text-white" 
                      : isActive 
                      ? "bg-blue-500 text-white animate-pulse" 
                      : "bg-slate-100 dark:bg-slate-800 text-slate-400"
                  }`}>
                    {isCompleted ? "✓" : index + 1}
                  </span>
                  <strong className={`text-xs font-semibold ${
                    isActive ? "text-blue-600 dark:text-blue-400" : "text-slate-700 dark:text-slate-350"
                  }`}>{pd.label}</strong>
                </div>
                <small className="text-[10px] text-slate-400 dark:text-slate-500 block mt-1 leading-tight">{pd.desc}</small>
              </div>
            );
          })}
        </div>
      </section>

      {/* 2. Capability Dock (MCP / Tools) */}
      {toolCalls.length > 0 || (activeStep.capabilities && activeStep.capabilities.length > 0) ? (
        <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 p-4">
          <div className="flex justify-between items-center mb-3">
            <div className="flex items-center gap-2">
              <Terminal className="text-indigo-500" size={16} />
              <h4 className="text-xs font-semibold text-slate-800 dark:text-slate-200">调用详情</h4>
            </div>
            <small className="text-[10px] text-slate-400">智能体实时工具调用状态</small>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {(toolCalls.length > 0 ? toolCalls : activeStep.capabilities || []).map((tool) => (
              <div 
                key={tool.id} 
                className={`p-3 rounded-lg border flex items-center justify-between gap-3 ${
                  tool.status === "error" 
                    ? "bg-rose-50/30 border-rose-100 dark:bg-rose-950/10 dark:border-rose-950/40" 
                    : tool.status === "done" 
                    ? "bg-slate-50/40 border-slate-100 dark:bg-slate-900/40 dark:border-slate-800"
                    : "bg-blue-50/20 border-blue-100 dark:bg-blue-950/10 dark:border-blue-900/30 animate-pulse"
                }`}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[10px] font-bold text-slate-400 uppercase">{tool.kind}</span>
                    <strong className="text-xs font-semibold text-slate-800 dark:text-slate-200 truncate block">{tool.name}</strong>
                  </div>
                  <small className="text-[10px] text-slate-400 dark:text-slate-500 truncate block mt-0.5">{tool.summary}</small>
                </div>
                <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                  tool.status === "done" 
                    ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400" 
                    : tool.status === "error" 
                    ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400" 
                    : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                }`}>
                  {tool.statusLabel}
                </span>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      {/* 3. Message Stream Bubble Area */}
      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-800 overflow-hidden">
        <header className="px-4 py-3 border-b border-slate-100 dark:border-slate-800 flex justify-between items-center bg-slate-50/50 dark:bg-slate-900/20">
          <div className="flex items-center gap-2">
            <Sparkles className="text-amber-500" size={16} />
            <h4 className="text-xs font-semibold text-slate-800 dark:text-slate-200">对话上下文</h4>
          </div>
          <button 
            type="button" 
            onClick={() => setShowPromptSnapshot(!showPromptSnapshot)}
            className="text-xs font-medium text-slate-500 hover:text-blue-600 flex items-center gap-1"
          >
            <FileText size={13} />
            {showPromptSnapshot ? "隐藏 Prompt" : "查看 Prompt"}
            {showPromptSnapshot ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          </button>
        </header>

        {/* Prompt Snapshot Viewer */}
        {showPromptSnapshot && (
          <div className="p-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/40 font-mono text-[10px] text-slate-500 dark:text-slate-400 space-y-2 overflow-x-auto">
            <strong>Prompt 构造快照:</strong>
            <pre className="whitespace-pre-wrap leading-relaxed">{`System Prompt:\n  ${activeStep.description}\n\nInputs Applied:\n  ${JSON.stringify(activeStep.inputs || [], null, 2)}`}</pre>
          </div>
        )}

        <div className="p-4 space-y-4 max-h-[400px] overflow-y-auto">
          {mergedMessages.length === 0 ? (
            <div className="text-center py-8 text-slate-400 text-xs">
              <Bot size={28} className="mx-auto mb-2 opacity-50" />
              正在启动智能体节点推理...
            </div>
          ) : (
            mergedMessages.map((msg) => (
              <div 
                key={msg.id} 
                className={`flex gap-3 max-w-[85%] ${
                  msg.role === "user" ? "ml-auto flex-row-reverse" : "mr-auto"
                }`}
              >
                {/* Avatar icon */}
                <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 border ${
                  msg.role === "user" 
                    ? "bg-slate-100 border-slate-200 text-slate-600 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-350" 
                    : "bg-blue-50 border-blue-100 text-blue-600 dark:bg-blue-950 dark:border-blue-900 dark:text-blue-400"
                }`}>
                  {msg.role === "user" ? "U" : <Bot size={14} />}
                </div>

                {/* Message Bubble Body */}
                <div className={`p-3 rounded-xl relative ${
                  msg.role === "user" 
                    ? "bg-blue-500 text-white rounded-tr-none" 
                    : "bg-slate-50 dark:bg-slate-900 border border-slate-100 dark:border-slate-800 rounded-tl-none"
                }`}>
                  <div className={`text-[10px] font-medium mb-1 ${
                    msg.role === "user" ? "text-blue-100" : "text-slate-400"
                  }`}>{msg.author}</div>
                  <MarkdownRenderer
                    content={msg.content}
                    compact
                    className={msg.role === "user" ? "agent-markdown--user" : ""}
                  />
                  
                  {msg.streaming && (
                    <span className="inline-block w-1.5 h-3.5 bg-blue-500 dark:bg-blue-400 ml-1 vertical-middle animate-pulse agent-chat-cursor" />
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      </section>
    </div>
  );
}
