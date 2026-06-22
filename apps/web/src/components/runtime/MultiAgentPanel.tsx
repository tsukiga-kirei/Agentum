import React, { useMemo, useState, useEffect } from "react";
import type { AgentExecutionStep, RuntimeCapabilityItem, RuntimeChatMessage, RuntimePreviewStep, RuntimeTokenUsage, RunStreamState } from "../../types/runtime-types";
import { AlertCircle, Bot, CheckCircle2, ChevronRight, Loader2, MessageSquarePlus, PencilLine, Settings2, Sparkles, Users, Wrench } from "lucide-react";
import { Drawer, message } from "antd";
import { useAuthStore } from "../../stores/authStore";
import { SingleAgentPanel } from "./SingleAgentPanel";
import { mergeClusterAgents, parseClusterAgentSummariesFromOutputs } from "../../utils/clusterAgentsMerge";
import { formatRuntimeErrorMessage } from "../../utils/runtimeErrors";
import { pickBestAgentOutput } from "../../utils/agentOutputText";
import { resolveSystemDisplayPrompt, resolveUserDisplayPrompt } from "../../utils/resolveDisplayPrompts";

interface MultiAgentPanelProps {
  activeStep: RuntimePreviewStep;
  clusterAgents: RunStreamState["clusterAgents"];
  isStreaming?: boolean;
  streamStartedAt?: number | null;
  onFollowUpAgent?: (agentIndex: number, message: string) => void | Promise<void>;
  onSaveAgentAnswer?: (agentIndex: number, content: string) => void | Promise<void>;
}

type DrawerAgent = {
  index: number;
  name: string;
  status: "pending" | "running" | "completed" | "failed";
  streamingText: string;
  reasoningText?: string;
  outputSummary: string;
  errorMessage?: string;
  toolCalls: RunStreamState["clusterAgents"][number]["toolCalls"];
  systemPrompt: string;
  userPrompt: string;
  modelName: string;
  skillNames: string[];
  mcpNames: string[];
  conversationHistory: RuntimeChatMessage[];
  tokenUsage?: RuntimeTokenUsage;
  allowQuestion: boolean;
  allowUserEdit: boolean;
};

export function MultiAgentPanel({
  activeStep,
  clusterAgents,
  isStreaming = false,
  streamStartedAt = null,
  onFollowUpAgent,
  onSaveAgentAnswer,
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
    const persistedAgents = parseClusterAgentSummariesFromOutputs(activeStep.outputs);

    return merged.map((agent) => {
      const config = configAgents[agent.index] ?? configAgents.find(
        (item) => String(item.name || item.label || "") === agent.name
      );
      const persistedAgent = persistedAgents[agent.index] ?? persistedAgents.find((item) => String(item.name ?? "") === agent.name);
      const persistedMessages = readPersistedConversation(persistedAgent?.chatMessages, agent.name, String(activeStep.nodeRunId ?? `cluster-${agent.index}`));
      return {
        ...agent,
        systemPrompt: config ? resolveSystemDisplayPrompt(config) : resolveSystemDisplayPrompt(undefined),
        userPrompt: config ? resolveUserDisplayPrompt(config) : resolveUserDisplayPrompt(undefined),
        modelName: readConfigString(
          config?.modelName
            ?? config?.model
            ?? readStepOutput(activeStep, ["modelName", "model_name"])
            ?? activeStep.configSnapshot?.modelName
            ?? activeStep.configSnapshot?.model,
          "",
        ),
        skillNames: readNameList(config?.skillNames ?? config?.skillIds ?? config?.skills),
        mcpNames: readNameList(config?.mcpNames ?? config?.mcpIds ?? config?.mcpServices),
        conversationHistory: persistedMessages.length > 0
          ? persistedMessages
          : readAgentConversationHistory(config, agent.name, String(activeStep.nodeRunId ?? `cluster-${agent.index}`)),
        tokenUsage: readTokenUsage(persistedAgent?.tokenUsage),
        allowQuestion: readAgentFollowUpAllowed(config, activeStep.configSnapshot),
        allowUserEdit: readAgentEditAllowed(config, activeStep.configSnapshot),
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
  const stepCanceled = activeStep.state === "canceled";
  const stepPending = activeStep.state === "pending" && !stepCanceled;

  return (
    <div className="multi-agent-run">
      <section className="multi-agent-overview">
        <div className="multi-agent-overview-head">
          <div className="multi-agent-overview-title">
            <Users size={18} />
            <h3>智能体集群进度</h3>
          </div>
          <small>
            共 {totalCount} 个子智能体 · 已完成 {completedCount} · 运行中 {runningCount}
          </small>
        </div>
        <div className="multi-agent-progress-row">
          <div className="multi-agent-progress-track">
            <div className="multi-agent-progress-bar" style={{ width: `${percent}%` }} />
          </div>
          <span>{percent}%</span>
        </div>
      </section>

      <div className="multi-agent-grid">
        {agents.map((agent) => {
          const isRunning = agent.status === "running";
          const isCompleted = agent.status === "completed";
          const isFailed = agent.status === "failed";
          const toolCount = agent.toolCalls?.length ?? 0;
          const skillCount = agent.skillNames.length;
          const mcpCount = agent.mcpNames.length;

          return (
            <button
              type="button"
              key={agent.index}
              onClick={() => setSelectedAgent(agent)}
              className={`multi-agent-card ${
                isRunning
                  ? "multi-agent-card--running"
                  : isCompleted
                  ? "multi-agent-card--completed"
                  : isFailed
                  ? "multi-agent-card--failed"
                  : "multi-agent-card--pending"
              }`}
            >
              {isRunning ? <span className="multi-agent-card-glow" aria-hidden="true" /> : null}
              <div className="multi-agent-card-main">
                <div className="multi-agent-card-icon">
                  {isRunning ? (
                    <Loader2 size={15} className="animate-spin" />
                  ) : isCompleted ? (
                    <CheckCircle2 size={15} />
                  ) : isFailed ? (
                    <AlertCircle size={15} />
                  ) : (
                    <Bot size={15} />
                  )}
                </div>
                <div className="multi-agent-card-body">
                  <strong>{agent.name}</strong>
                  <span>
                    {stepCanceled
                      ? "已中断"
                      : isRunning
                      ? "正在执行"
                      : isCompleted
                      ? "执行完成"
                      : isFailed
                      ? "执行失败"
                      : stepPending
                      ? "等待启动"
                      : "等待调度"}
                  </span>
                </div>
                <ChevronRight size={16} className="multi-agent-card-arrow" />
              </div>
              <div className="multi-agent-card-meta">
                {agent.modelName ? (
                  <span title={agent.modelName}>
                    <Settings2 size={12} />
                    {agent.modelName}
                  </span>
                ) : null}
                <span title={agent.skillNames.join("、") || "未配置 Skill"}>
                  <Sparkles size={12} />
                  {skillCount > 0 ? `${skillCount} 个 Skill` : "无 Skill"}
                </span>
                <span title={agent.mcpNames.join("、") || "未配置 MCP"}>
                  <Wrench size={12} />
                  {mcpCount > 0 ? `${mcpCount} 个 MCP` : "无 MCP"}
                </span>
                <span className={agent.allowQuestion ? "multi-agent-card-chip--on" : ""}>
                  <MessageSquarePlus size={12} />
                  {agent.allowQuestion ? "可追问" : "不可追问"}
                </span>
                <span className={agent.allowUserEdit ? "multi-agent-card-chip--on" : ""}>
                  <PencilLine size={12} />
                  {agent.allowUserEdit ? "可修改" : "不可修改"}
                </span>
              </div>
              <div className="multi-agent-card-foot">
                <span>{toolCount > 0 ? `${toolCount} 个工具调用` : "无工具调用"}</span>
                <span>{stepCanceled ? "需重新执行" : isRunning ? "实时更新" : isCompleted ? "可查看结果" : isFailed ? "查看原因" : "等待中"}</span>
              </div>
            </button>
          );
        })}
      </div>

      {selectedAgent ? (
        <Drawer
          title={`${selectedAgent.name} · 详情`}
          width={640}
          open
          onClose={() => setSelectedAgent(null)}
          rootClassName={themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark multi-agent-detail-drawer" : "agent-admin-drawer multi-agent-detail-drawer"}
        >
          <SingleAgentPanel
            activeStep={buildAgentDetailStep(activeStep, selectedAgent)}
            isStreaming={!stepCanceled && selectedAgent.status === "running"}
            streamingText=""
            executionSteps={stepCanceled ? [] : buildAgentExecutionSteps(selectedAgent)}
            streamStartedAt={!stepCanceled && selectedAgent.status === "running" ? streamStartedAt : null}
            interruptedScope="clusterDrawer"
            onFollowUp={async (followUpMessage) => {
              if (!onFollowUpAgent) {
                message.warning("当前子智能体未开放追问");
                return;
              }
              await onFollowUpAgent(selectedAgent.index, followUpMessage);
            }}
            onSaveAnswer={async (content) => {
              if (!onSaveAgentAnswer) {
                message.warning("当前子智能体未开放修改");
                return;
              }
              await onSaveAgentAnswer(selectedAgent.index, content);
            }}
          />
        </Drawer>
      ) : null}
    </div>
  );
}

function readConfigString(value: unknown, fallback: string): string {
  if (value === null || value === undefined) {
    return fallback;
  }
  const text = String(value).trim();
  return text || fallback;
}

function readStepOutput(step: RuntimePreviewStep, labels: string[]): string {
  return step.outputs?.find((field) => labels.includes(field.label))?.value?.trim() ?? "";
}

function readBoolean(value: unknown): boolean {
  return value === true || value === "true" || value === "1" || value === 1;
}

function readAgentEditAllowed(agentConfig: Record<string, unknown> | undefined, parentConfig: Record<string, unknown> | undefined): boolean {
  if (agentConfig && ("allowUserEdit" in agentConfig || "outputMode" in agentConfig)) {
    return readBoolean(agentConfig.allowUserEdit) || agentConfig.outputMode === "追问确认";
  }
  return readBoolean(parentConfig?.allowUserEdit) || parentConfig?.outputMode === "追问确认";
}

function readAgentFollowUpAllowed(agentConfig: Record<string, unknown> | undefined, parentConfig: Record<string, unknown> | undefined): boolean {
  if (agentConfig && ("allowQuestion" in agentConfig || "outputMode" in agentConfig)) {
    return readBoolean(agentConfig.allowQuestion) || agentConfig.outputMode === "追问确认";
  }
  return readBoolean(parentConfig?.allowQuestion) || parentConfig?.outputMode === "追问确认";
}

function readAgentConversationHistory(
  agentConfig: Record<string, unknown> | undefined,
  agentName: string,
  idPrefix: string,
): RuntimeChatMessage[] {
  const rawHistory = Array.isArray(agentConfig?.conversationHistory) ? agentConfig.conversationHistory : [];
  return rawHistory.flatMap((item, index) => {
    if (!item || typeof item !== "object") {
      return [];
    }
    const record = item as Record<string, unknown>;
    const role = record.role === "user" ? "user" : record.role === "assistant" ? "assistant" : "";
    const content = String(record.content ?? "").trim();
    if (!role || !content) {
      return [];
    }
    return [{
      id: `${idPrefix}-agent-history-${index}`,
      role,
      author: role === "user" ? "我" : agentName,
      content,
    }];
  });
}

function readPersistedConversation(value: unknown, agentName: string, idPrefix: string): RuntimeChatMessage[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item, index) => {
    if (!item || typeof item !== "object") {
      return [];
    }
    const record = item as Record<string, unknown>;
    const role = record.role === "user" ? "user" : record.role === "assistant" ? "assistant" : "";
    const content = String(record.content ?? "").trim();
    if (!role || !content) {
      return [];
    }
    return [{
      id: `${idPrefix}-persisted-${index}`,
      role,
      author: role === "user" ? "我" : agentName,
      content,
      tokenUsage: readTokenUsage(record.tokenUsage),
      processSteps: readPersistedProcessSteps(record.processSteps, `${idPrefix}-persisted-${index}`),
    }];
  });
}

function readPersistedProcessSteps(value: unknown, idPrefix: string): AgentExecutionStep[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item, index) => {
    if (!item || typeof item !== "object") {
      return [];
    }
    const record = item as Record<string, unknown>;
    const kind = record.kind === "tool" || record.kind === "reasoning" || record.kind === "model_output" || record.kind === "final_answer"
      ? record.kind
      : "model_output";
    return [{
      id: `${idPrefix}-step-${index}`,
      kind,
      title: String(record.title ?? (kind === "reasoning" ? "深度推理" : "执行步骤")),
      summary: String(record.summary ?? ""),
      status: record.status === "error" ? "error" as const : "done" as const,
      detail: String(record.detail ?? ""),
      toolType: record.toolType === "mcp" || record.toolType === "skill" || record.toolType === "reasoning" || record.toolType === "model"
        ? record.toolType
        : undefined,
    }];
  });
}

function readTokenUsage(value: unknown): RuntimeTokenUsage | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }
  const record = value as Record<string, unknown>;
  const inputTokens = Number(record.inputTokens ?? 0);
  const outputTokens = Number(record.outputTokens ?? 0);
  const totalTokens = Number(record.totalTokens ?? inputTokens + outputTokens);
  return Number.isFinite(totalTokens) && totalTokens > 0 ? { inputTokens, outputTokens, totalTokens } : undefined;
}

function readNameList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => {
      if (typeof item === "string") {
        return shortenName(item);
      }
      if (item && typeof item === "object") {
        const record = item as Record<string, unknown>;
        return shortenName(String(record.name ?? record.label ?? record.id ?? ""));
      }
      return "";
    })
    .filter((item) => item.length > 0);
}

function shortenName(value: string): string {
  const text = value.trim();
  if (!text) {
    return "";
  }
  const parts = text.split(/[/:#._-]/).filter(Boolean);
  return parts[parts.length - 1] || text;
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

function buildAgentExecutionSteps(agent: DrawerAgent): AgentExecutionStep[] {
  const outputText = resolveClusterAgentPreview(agent);
  const toolSteps: AgentExecutionStep[] = (agent.toolCalls ?? [])
    .filter((tool): tool is RuntimeCapabilityItem & { kind: "mcp" | "skill" } => tool.kind === "mcp" || tool.kind === "skill")
    .map((tool) => ({
      id: `cluster-tool-${agent.index}-${tool.id}`,
      kind: "tool",
      title: tool.kind === "skill" ? `读取 Skill：${tool.name}` : `调用 MCP：${tool.name}`,
      summary: tool.resultSummary || tool.statusLabel,
      status: tool.status === "error" ? "error" : tool.status === "running" ? "running" : "done",
      durationMs: tool.durationMs,
      detail: tool.resultSummary,
      toolType: tool.kind,
    }));
  if (agent.reasoningText?.trim()) {
    toolSteps.push({
      id: `cluster-reasoning-${agent.index}`,
      kind: "reasoning",
      title: agent.status === "running" ? "深度推理中" : "深度推理",
      summary: agent.status === "running" ? "正在生成推理过程" : "可展开查看推理过程",
      status: agent.status === "running" ? "running" : "done",
      detail: agent.reasoningText.trim(),
      toolType: "reasoning",
    });
  }
  if (outputText && (toolSteps.length > 0 || agent.status === "running")) {
    toolSteps.push({
      id: `cluster-model-output-${agent.index}`,
      kind: "model_output",
      title: agent.status === "failed" ? "生成失败原因" : "生成最终答案",
      summary: agent.status === "running" ? "正在生成…" : "可展开查看",
      status: agent.status === "running" ? "running" : agent.status === "failed" ? "error" : "done",
      detail: agent.status === "failed" ? formatRuntimeErrorMessage(undefined, outputText) : outputText,
      toolType: "model",
    });
  }
  return toolSteps;
}

function buildAgentDetailStep(parentStep: RuntimePreviewStep, agent: DrawerAgent): RuntimePreviewStep {
  if (parentStep.state === "canceled") {
    return {
      ...parentStep,
      nodeRunId: `${parentStep.nodeRunId}-agent-${agent.index}`,
      title: agent.name,
      subtitle: "子智能体处理",
      kind: "agent",
      state: "canceled",
      description: "子智能体运行详情",
      outputs: [],
      capabilities: [],
      allowsFollowUp: false,
      allowsRegenerate: false,
      configSnapshot: parentStep.configSnapshot ?? {},
      chatMessages: [],
    };
  }

  const outputText = resolveClusterAgentPreview(agent);
  const finalContent = agent.status === "failed" && outputText
    ? formatRuntimeErrorMessage(undefined, outputText)
    : outputText;
  const prompt = agent.userPrompt || agent.systemPrompt || agent.name;
  const state: RuntimePreviewStep["state"] =
    agent.status === "completed" ? "done" : agent.status === "failed" ? "failed" : agent.status === "running" ? "running" : "pending";
  const processSteps = buildAgentExecutionSteps(agent);
  const chatMessages = buildAgentChatMessages(parentStep, agent, prompt, finalContent, processSteps);
  const outputs = [
    ...(finalContent ? [{ label: "final_answer", value: finalContent }, { label: "model_content", value: finalContent }] : []),
    { label: "final_answer_source", value: "model_content" },
  ];

  return {
    ...parentStep,
    nodeRunId: `${parentStep.nodeRunId}-agent-${agent.index}`,
    title: agent.name,
    subtitle: "子智能体处理",
    kind: "agent",
    state,
    description: "子智能体运行详情",
    outputs,
    capabilities: agent.toolCalls,
    allowsFollowUp: agent.allowQuestion,
    allowsRegenerate: agent.allowUserEdit,
    configSnapshot: {
      ...(parentStep.configSnapshot ?? {}),
      userPrompt: prompt,
      prompt,
      allowQuestion: agent.allowQuestion,
      allowUserEdit: agent.allowUserEdit,
    },
    chatMessages,
  };
}

function buildAgentChatMessages(
  parentStep: RuntimePreviewStep,
  agent: DrawerAgent,
  prompt: string,
  finalContent: string,
  processSteps: AgentExecutionStep[],
): RuntimeChatMessage[] {
  const messages = agent.conversationHistory.length > 0
    ? agent.conversationHistory.map((message) => ({ ...message }))
    : [{
        id: `${parentStep.nodeRunId}-agent-${agent.index}-user`,
        role: "user" as const,
        author: "我",
        content: prompt,
      }];

  if (!finalContent.trim()) {
    return messages;
  }

  const lastMessage = messages[messages.length - 1];
  if (lastMessage?.role === "assistant" && lastMessage.content.trim() === finalContent.trim()) {
    messages[messages.length - 1] = {
      ...lastMessage,
      tokenUsage: lastMessage.tokenUsage ?? agent.tokenUsage,
      processSteps,
    };
    return messages;
  }

  messages.push({
    id: `${parentStep.nodeRunId}-agent-${agent.index}-assistant-current`,
    role: "assistant",
    author: agent.name,
    content: finalContent,
    tokenUsage: agent.tokenUsage,
    processSteps,
  });
  return messages;
}
