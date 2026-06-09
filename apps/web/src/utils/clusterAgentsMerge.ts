import type { RunStreamState } from "../types/runtime-types";
import { isClusterAgentFailureSummary } from "./runtimeErrors";

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
  const text = agent.summary ?? agent.outputSummary ?? "";
  return typeof text === "string" ? text : String(text ?? "");
}

function nameFromPersisted(agent: Record<string, unknown>, index: number): string {
  const name = agent.name ?? agent.label;
  if (typeof name === "string" && name.trim()) {
    return name;
  }
  return `子智能体 ${index + 1}`;
}

/**
 * 合并配置、DB 增量快照与 SSE 流式状态，避免刷新后已完成子智能体显示为空。
 */
export function mergeClusterAgents(options: {
  configAgents: Array<Record<string, unknown>>;
  outputs: Array<{ label: string; value: string }> | undefined;
  streamAgents: RunStreamState["clusterAgents"];
  stepState: "pending" | "running" | "done" | "waiting" | "failed";
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
    const live = streamAgents.find((agent) => agent.index === base.index || agent.name === base.name);
    const persistedAgent =
      persisted[base.index] ??
      persisted.find((agent) => nameFromPersisted(agent, base.index) === base.name);
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
        merged.status = isClusterAgentFailureSummary(persistedSummary) ? "failed" : "completed";
        merged.outputSummary = persistedSummary;
        if (merged.status === "failed") {
          merged.errorMessage = persistedSummary;
        }
      }
      return merged;
    }

    if (persistedSummary) {
      const failed = isClusterAgentFailureSummary(persistedSummary);
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

    if (stepState === "done") {
      return {
        index: base.index,
        name: base.name,
        status: "pending",
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
