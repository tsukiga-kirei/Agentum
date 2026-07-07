import type { RunStreamState, RuntimeStepState } from "../types/runtime-types";
import { isPersistedClusterAgentFailed } from "./runtimeErrors";
import { pickBestAgentOutput } from "./agentOutputText";

type ClusterAgentView = RunStreamState["clusterAgents"][number];

type ClusterProgress = {
  completedCount: number;
  nextAgentIndex: number;
};

function parseJsonField(value: string | undefined): unknown {
  if (!value) {
    return null;
  }
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

export function parseClusterAgentSummariesFromOutputs(
  outputs: Array<{ label: string; value: string }> | undefined
): Array<Record<string, unknown>> {
  const field = outputs?.find((item) => item.label === "clusterAgents");
  const parsed = parseJsonField(field?.value);
  return Array.isArray(parsed) ? parsed : [];
}

export function parseClusterProgressFromOutputs(
  outputs: Array<{ label: string; value: string }> | undefined
): ClusterProgress | null {
  const field = outputs?.find((item) => item.label === "clusterProgress");
  const parsed = parseJsonField(field?.value);
  if (!parsed || typeof parsed !== "object") {
    return null;
  }
  const record = parsed as Record<string, unknown>;
  const completedCount = typeof record.completedCount === "number" ? record.completedCount : 0;
  const nextAgentIndex =
    typeof record.nextAgentIndex === "number" ? record.nextAgentIndex : completedCount;
  return { completedCount, nextAgentIndex };
}

function summaryFromPersisted(agent: Record<string, unknown>): string {
  const finalAnswer = agent.final_answer ?? agent.finalAnswer;
  if (typeof finalAnswer === "string" && finalAnswer.trim()) {
    return finalAnswer.trim();
  }
  const text = agent.summary ?? agent.outputSummary ?? "";
  return typeof text === "string" ? text : String(text ?? "");
}

export function clusterAgentDisplayText(agent: Record<string, unknown>): string {
  return summaryFromPersisted(agent);
}

function nameFromPersisted(agent: Record<string, unknown>, index: number): string {
  const name = agent.name ?? agent.label;
  if (typeof name === "string" && name.trim()) {
    return name;
  }
  return `子智能体 ${index + 1}`;
}

function indexFromPersisted(agent: Record<string, unknown>): number | null {
  const rawIndex = agent.agentIndex ?? agent.index;
  const index = Number(rawIndex);
  return Number.isFinite(index) ? index : null;
}

export function findPersistedClusterAgent(
  persisted: Array<Record<string, unknown>>,
  index: number,
  name: string,
): Record<string, unknown> | undefined {
  const indexed = persisted.find((agent) => indexFromPersisted(agent) === index);
  if (indexed) {
    return indexed;
  }
  const hasExplicitIndex = persisted.some((agent) => indexFromPersisted(agent) !== null);
  if (!hasExplicitIndex && persisted[index]) {
    return persisted[index];
  }
  return persisted.find((agent) => nameFromPersisted(agent, index) === name);
}

/**
 * 合并配置、DB 增量快照与 SSE 流式状态，避免刷新后已完成子智能体显示为空。
 */
export function mergeClusterAgents(options: {
  configAgents: Array<Record<string, unknown>>;
  outputs: Array<{ label: string; value: string }> | undefined;
  streamAgents: RunStreamState["clusterAgents"];
  stepState: RuntimeStepState;
  stepRunning: boolean;
}): ClusterAgentView[] {
  const { configAgents, outputs, streamAgents, stepState, stepRunning } = options;
  const persisted = parseClusterAgentSummariesFromOutputs(outputs);
  const progress = parseClusterProgressFromOutputs(outputs);

  const bases =
    configAgents.length > 0
      ? configAgents.map((agent, index) => ({
          index,
          name: String(agent.name || agent.label || `子智能体 ${index + 1}`),
        }))
      : streamAgents.map((agent) => ({ index: agent.index, name: agent.name }));

  if (bases.length === 0) {
    return streamAgents;
  }

  return bases.map((base) => {
    if (stepState === "canceled") {
      return {
        index: base.index,
        name: base.name,
        status: "pending" as const,
        streamingText: "",
        outputSummary: "",
        toolCalls: [],
      };
    }

    const live = streamAgents.find((agent) => agent.index === base.index || agent.name === base.name);
    const persistedAgent = findPersistedClusterAgent(persisted, base.index, base.name);
    const persistedSummary = persistedAgent ? summaryFromPersisted(persistedAgent) : "";

    if (live) {
      const merged: ClusterAgentView = {
        ...live,
        index: base.index,
        name: base.name,
      };
      if (
        merged.status !== "completed"
        && merged.status !== "failed"
        && persistedSummary
        && !merged.outputSummary
        && !merged.streamingText
      ) {
        merged.status = persistedAgent && isPersistedClusterAgentFailed(persistedAgent) ? "failed" : "completed";
        merged.outputSummary = persistedSummary;
        if (merged.status === "failed") {
          merged.errorMessage = persistedSummary;
        }
      }
      if (merged.status === "completed" || merged.status === "running") {
        const bestText = pickBestAgentOutput(
          merged.outputSummary,
          merged.streamingText,
          persistedSummary
        );
        if (bestText) {
          merged.outputSummary = bestText;
          merged.streamingText = bestText;
        }
      }
      return merged;
    }

    if (persistedSummary && persistedAgent) {
      const failed = isPersistedClusterAgentFailed(persistedAgent);
      return {
        index: base.index,
        name: base.name,
        status: failed ? "failed" : "completed",
        streamingText: "",
        outputSummary: persistedSummary,
        errorMessage: failed ? persistedSummary : undefined,
        toolCalls: [],
      };
    }

    if (stepState === "done" || stepState === "failed") {
      return {
        index: base.index,
        name: base.name,
        status: stepState === "failed" ? "failed" : "completed",
        streamingText: "",
        outputSummary: "",
        toolCalls: [],
      };
    }

    if (stepRunning && progress) {
      if (base.index < progress.completedCount) {
        return {
          index: base.index,
          name: base.name,
          status: "completed",
          streamingText: "",
          outputSummary: "已完成（刷新后摘要加载中）",
          toolCalls: [],
        };
      }
      if (base.index === progress.nextAgentIndex) {
        return {
          index: base.index,
          name: base.name,
          status: "running",
          streamingText: "",
          outputSummary: "",
          toolCalls: [],
        };
      }
    } else if (stepRunning) {
      return {
        index: base.index,
        name: base.name,
        status: "running",
        streamingText: "",
        outputSummary: "",
        toolCalls: [],
      };
    }

    return {
      index: base.index,
      name: base.name,
      status: "pending",
      streamingText: "",
      outputSummary: "",
      toolCalls: [],
    };
  });
}
