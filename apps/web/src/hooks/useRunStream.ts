import { useState, useEffect, useRef, useCallback } from "react";
import type {
  StreamEvent,
  RunStreamState,
  RunStreamConnectOptions,
  StreamConnectionState,
  AgentPhase,
  RuntimeCapabilityItem,
  AgentExecutionStep,
} from "../types/runtime-types";
import {
  finalizeFinalAnswerStep,
  upsertFinalAnswerStep,
  upsertPhaseStep,
  upsertToolStep,
} from "../utils/agentExecutionSteps";
import { API_BASE_URL } from "../services/apiClient";
import { formatRuntimeErrorMessage } from "../utils/runtimeErrors";
import { pickBestAgentOutput } from "../utils/agentOutputText";

/** 断线续传：sessionStorage 中保存每个任务最近收到的 Redis Stream 事件 ID。 */
function lastEventIdStorageKey(runId: string): string {
  return `agentum:run-stream:${runId}`;
}

function readStoredLastEventId(runId: string): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return window.sessionStorage.getItem(lastEventIdStorageKey(runId));
}

function storeLastEventId(runId: string, eventId: string): void {
  if (typeof window === "undefined") {
    return;
  }
  window.sessionStorage.setItem(lastEventIdStorageKey(runId), eventId);
}

function clearStoredLastEventId(runId: string): void {
  if (typeof window === "undefined") {
    return;
  }
  window.sessionStorage.removeItem(lastEventIdStorageKey(runId));
}

/**
 * 任务运行态 SSE 流 Hook（Redis Stream 中继版）。
 *
 * 执行在后端 Worker 完成、事件落 Redis Stream，本 Hook 只负责连接中继并回放：
 * - 进入/刷新页面用 `connect({ replay: true })` 回放当前步骤全部事件，做到无感恢复；
 * - 断线自动重连时优先带 lastEventId 续传，避免漏事件；
 * - heartbeat 事件刷新 lastEventAt，供上层看门狗判定后台执行是否存活；
 * - 连续重连失败计数暴露给上层，达到阈值时亮出被动「恢复进度」按钮。
 */
export function useRunStream(
  tenantId: string,
  runId: string,
  token: string
): RunStreamState {
  const [events, setEvents] = useState<StreamEvent[]>([]);
  const [streamingText, setStreamingText] = useState<string>("");
  const [isStreaming, setIsStreaming] = useState<boolean>(false);
  const [currentPhase, setCurrentPhase] = useState<AgentPhase | null>(null);
  const [activeNodeInfo, setActiveNodeInfo] = useState<{
    nodeRunId: string;
    nodeName: string;
    nodeType: string;
  } | null>(null);
  const [toolCalls, setToolCalls] = useState<RuntimeCapabilityItem[]>([]);
  const [executionSteps, setExecutionSteps] = useState<AgentExecutionStep[]>([]);
  const [streamStartedAt, setStreamStartedAt] = useState<number | null>(null);
  const [clusterAgents, setClusterAgents] = useState<
    RunStreamState["clusterAgents"]
  >([]);
  const [connectionState, setConnectionState] =
    useState<StreamConnectionState>("disconnected");
  const [error, setError] = useState<string | null>(null);
  const [lastEventAt, setLastEventAt] = useState<number | null>(null);
  const [reconnectFailures, setReconnectFailures] = useState(0);

  const abortControllerRef = useRef<AbortController | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectionStateRef = useRef<StreamConnectionState>("disconnected");
  const isStreamActiveRef = useRef(false);
  const connectSessionRef = useRef(0);
  const connectReadyRef = useRef<Promise<void> | null>(null);
  const pendingConnectResolversRef = useRef<Array<() => void>>([]);
  const activeNodeRunIdRef = useRef<string | null>(null);
  const reconnectFailuresRef = useRef(0);
  /** 本步骤 SSE 已收到终态事件；在 reloadRunDetail 完成前阻止自动重连 */
  const stepStreamTerminalRef = useRef(false);

  const markStepStreamTerminal = useCallback(() => {
    stepStreamTerminalRef.current = true;
  }, []);

  const clearStepStreamTerminal = useCallback(() => {
    stepStreamTerminalRef.current = false;
  }, []);

  const isStepStreamTerminal = useCallback(() => stepStreamTerminalRef.current, []);

  const disconnect = useCallback((options?: { preserveProgress?: boolean }) => {
    const preserveProgress = options?.preserveProgress === true;
    connectSessionRef.current += 1;
    connectReadyRef.current = null;
    connectionStateRef.current = "disconnected";
    isStreamActiveRef.current = false;
    pendingConnectResolversRef.current.forEach((resolve) => resolve());
    pendingConnectResolversRef.current = [];
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    setConnectionState("disconnected");
    setIsStreaming(false);
    setActiveNodeInfo(null);
    activeNodeRunIdRef.current = null;
    if (!preserveProgress) {
      setStreamingText("");
      setCurrentPhase(null);
      setToolCalls([]);
      setExecutionSteps([]);
      setStreamStartedAt(null);
      setClusterAgents([]);
    }
  }, []);

  const resolvePendingConnectors = useCallback(() => {
    pendingConnectResolversRef.current.forEach((resolve) => resolve());
    pendingConnectResolversRef.current = [];
  }, []);

  const connect = useCallback((options?: RunStreamConnectOptions): Promise<void> => {
    if (connectionStateRef.current === "connected" && isStreamActiveRef.current) {
      return Promise.resolve();
    }
    if (connectionStateRef.current === "connecting" && connectReadyRef.current) {
      return connectReadyRef.current;
    }

    const session = connectSessionRef.current + 1;
    connectSessionRef.current = session;
    connectionStateRef.current = "connecting";

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }

    setConnectionState("connecting");
    setError(null);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    // 连接参数：进入/刷新页面要求整步回放（React 状态已丢失，断点续传会漏掉刷新前的事件），
    // 同一页面会话内的断线重连才走 lastEventId 续传。
    const params = new URLSearchParams();
    if (options?.replay) {
      clearStoredLastEventId(runId);
      params.set("replay", "true");
    } else {
      const storedEventId = readStoredLastEventId(runId);
      if (storedEventId) {
        params.set("lastEventId", storedEventId);
      }
    }
    const query = params.toString();
    const url = `${API_BASE_URL}/api/tenants/${tenantId}/workbench/runs/${runId}/stream${query ? `?${query}` : ""}`;

    connectReadyRef.current = new Promise<void>((resolveReady, rejectReady) => {
      void (async function startStream() {
        try {
          const response = await fetch(url, {
            method: "GET",
            headers: {
              Authorization: `Bearer ${token}`,
              Accept: "text/event-stream",
            },
            signal: controller.signal,
          });

          if (session !== connectSessionRef.current) {
            await response.body?.cancel();
            return;
          }

          if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
          }

          connectionStateRef.current = "connected";
          isStreamActiveRef.current = true;
          reconnectFailuresRef.current = 0;
          setReconnectFailures(0);
          setConnectionState("connected");
          resolveReady();
          resolvePendingConnectors();

          const reader = response.body?.getReader();
          if (!reader) {
            throw new Error("Response body is not readable");
          }

          const decoder = new TextDecoder("utf-8");
          let buffer = "";
          let currentEventType = "";
          let currentEventId = "";

          while (true) {
            if (session !== connectSessionRef.current) {
              await reader.cancel();
              return;
            }

            const { done, value } = await reader.read();
            if (done) {
              break;
            }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split("\n");
            buffer = lines.pop() || "";

            for (const line of lines) {
              const trimmed = line.trim();
              if (!trimmed) {
                continue;
              }

              if (trimmed.startsWith("id:")) {
                currentEventId = trimmed.substring(3).trim();
              } else if (trimmed.startsWith("event:")) {
                currentEventType = trimmed.substring(6).trim();
              } else if (trimmed.startsWith("data:")) {
                const dataStr = trimmed.substring(5).trim();
                if (currentEventId) {
                  storeLastEventId(runId, currentEventId);
                }
                setLastEventAt(Date.now());

                if (dataStr === "[DONE]") {
                  // 步骤终态：清除断点记录，下次进入重新整步回放。
                  clearStoredLastEventId(runId);
                  markStepStreamTerminal();
                  if (session === connectSessionRef.current) {
                    disconnect();
                  }
                  break;
                }

                try {
                  const parsedData = JSON.parse(dataStr);
                  const streamEvent = {
                    type: currentEventType || "message",
                    data: parsedData,
                    eventId: currentEventId || undefined,
                  } as StreamEvent;

                  setEvents((prev) => [...prev, streamEvent]);
                  handleStreamEvent(streamEvent);
                } catch (e) {
                  console.error("Failed to parse SSE data line:", dataStr, e);
                }

                currentEventType = "";
                currentEventId = "";
              }
            }
          }

          if (session === connectSessionRef.current) {
            connectionStateRef.current = "disconnected";
            isStreamActiveRef.current = false;
            setConnectionState("disconnected");
          }
        } catch (err: unknown) {
          if (session !== connectSessionRef.current) {
            return;
          }
          if (err instanceof Error && err.name === "AbortError") {
            connectionStateRef.current = "disconnected";
            isStreamActiveRef.current = false;
            return;
          }
          console.error("SSE connection error:", err);
          connectionStateRef.current = "error";
          isStreamActiveRef.current = false;
          reconnectFailuresRef.current += 1;
          setReconnectFailures(reconnectFailuresRef.current);
          setConnectionState("error");
          setError(err instanceof Error ? err.message : "连接断开");
          resolvePendingConnectors();
          rejectReady(err instanceof Error ? err : new Error("SSE 连接失败"));

          if (!controller.signal.aborted) {
            setConnectionState("reconnecting");
            reconnectTimeoutRef.current = setTimeout(() => {
              if (session !== connectSessionRef.current) {
                return;
              }
              connectionStateRef.current = "disconnected";
              setConnectionState("disconnected");
              // 重连优先走 lastEventId 续传（storage 中已有断点）。
              void connect();
            }, 3000);
          }
        } finally {
          if (connectReadyRef.current && session === connectSessionRef.current) {
            connectReadyRef.current = null;
          }
        }
      })();
    });

    return connectReadyRef.current;
  }, [tenantId, runId, token, disconnect, resolvePendingConnectors]);

  const ensureConnected = useCallback(async (timeoutMs = 8000) => {
    if (connectionStateRef.current === "connected" && isStreamActiveRef.current) {
      return;
    }
    await Promise.race([
      connect(),
      new Promise<void>((_, reject) => {
        window.setTimeout(() => {
          reject(new Error("SSE 连接超时，请稍后重试"));
        }, timeoutMs);
      }),
    ]);
  }, [connect]);

  const handleStreamEvent = (event: StreamEvent) => {
    switch (event.type) {
      case "connected":
        // Connection established
        break;

      case "node_started": {
        const { nodeRunId, nodeName, nodeType } = event.data;
        stepStreamTerminalRef.current = false;
        const isSameNode = activeNodeRunIdRef.current === nodeRunId;
        activeNodeRunIdRef.current = nodeRunId;
        setActiveNodeInfo({ nodeRunId, nodeName, nodeType });
        setIsStreaming(true);
        setCurrentPhase("preparing");
        // SSE 重连/回放会再次收到 node_started：同一节点不清空已展示的流式/集群进度。
        if (!isSameNode) {
          setStreamingText("");
          setToolCalls([]);
          setExecutionSteps([]);
          setStreamStartedAt(Date.now());
          setClusterAgents([]);
        } else {
          setStreamStartedAt((startedAt) => startedAt ?? Date.now());
        }
        break;
      }

      case "agent_thinking": {
        const { phase, message } = event.data;
        setCurrentPhase(phase);
        setExecutionSteps((prev) => upsertPhaseStep(prev, phase, message));
        break;
      }

      case "agent_streaming": {
        const { accumulatedContent } = event.data;
        setStreamingText(accumulatedContent);
        setCurrentPhase("model_calling");
        setIsStreaming(true);
        setExecutionSteps((prev) => upsertFinalAnswerStep(prev, accumulatedContent, true));
        break;
      }

      case "agent_tool_call": {
        const { toolName, toolType, status, result, durationMs } = event.data;
        setCurrentPhase("tool_calling");

        setToolCalls((prev) => {
          const existingIdx = prev.findIndex((t) => t.name === toolName);
          const updated: RuntimeCapabilityItem = {
            id: toolName,
            name: toolName,
            kind: toolType,
            status: status === "started" ? "running" : status === "completed" ? "done" : "error",
            statusLabel: status === "started" ? "调用中" : status === "completed" ? "调用完成" : "调用失败",
            summary: status === "started" ? `正在调用 ${toolName}...` : status === "completed" ? `调用成功` : `调用失败`,
            durationMs,
            resultSummary: result,
          };

          if (existingIdx >= 0) {
            const copy = [...prev];
            copy[existingIdx] = updated;
            setExecutionSteps((steps) => upsertToolStep(steps, updated));
            return copy;
          }
          setExecutionSteps((steps) => upsertToolStep(steps, updated));
          return [...prev, updated];
        });
        break;
      }

      case "cluster_agent": {
        const {
          agentIndex,
          agentName,
          eventType,
          accumulatedContent,
          toolName,
          toolType,
          toolStatus,
          outputSummary,
          result,
          durationMs,
          errorCode,
          errorMessage,
        } = event.data;

        setClusterAgents((prev) => {
          const copy = [...prev];
          let agent = copy.find((a) => a.index === agentIndex);

          if (!agent) {
            agent = {
              index: agentIndex,
              name: agentName,
              status: "pending",
              streamingText: "",
              outputSummary: "",
              toolCalls: [],
            };
            copy.push(agent);
            copy.sort((a, b) => a.index - b.index);
          }

          if (eventType === "started") {
            agent.status = "running";
            agent.streamingText = "";
            agent.toolCalls = [];
          } else if (eventType === "phase") {
            agent.status = "running";
          } else if (eventType === "streaming" && accumulatedContent) {
            agent.status = "running";
            agent.streamingText = accumulatedContent;
          } else if (eventType === "completed") {
            agent.status = "completed";
            const bestText = pickBestAgentOutput(
              outputSummary,
              accumulatedContent,
              agent.streamingText,
              agent.outputSummary
            );
            agent.outputSummary = bestText;
            agent.streamingText = bestText;
          } else if (eventType === "failed") {
            agent.status = "failed";
            agent.errorMessage = formatRuntimeErrorMessage(errorCode, errorMessage);
            agent.outputSummary = agent.errorMessage;
          } else if (eventType === "tool_call" && toolName) {
            const existingToolIdx = agent.toolCalls.findIndex((t) => t.name === toolName);
            const updatedTool: RuntimeCapabilityItem = {
              id: toolName,
              name: toolName,
              kind: toolType === "skill" ? "skill" : toolType === "agent" ? "agent" : "mcp",
              status: toolStatus === "started" ? "running" : toolStatus === "completed" ? "done" : "error",
              statusLabel: toolStatus === "started" ? "调用中" : toolStatus === "completed" ? "调用完成" : "调用失败",
              summary: toolStatus === "started" ? `正在调用 ${toolName}...` : toolStatus === "completed" ? `调用成功` : `调用失败`,
              durationMs,
              resultSummary: result,
            };

            if (existingToolIdx >= 0) {
              agent.toolCalls[existingToolIdx] = updatedTool;
            } else {
              agent.toolCalls.push(updatedTool);
            }
          }

          return copy;
        });
        break;
      }

      case "node_completed":
        markStepStreamTerminal();
        setIsStreaming(false);
        setCurrentPhase("completed");
        setExecutionSteps((prev) => finalizeFinalAnswerStep(prev));
        setActiveNodeInfo(null);
        activeNodeRunIdRef.current = null;
        break;

      case "node_failed": {
        const { errorCode, errorMessage } = event.data;
        markStepStreamTerminal();
        setIsStreaming(false);
        setCurrentPhase("failed");
        setActiveNodeInfo(null);
        setError(formatRuntimeErrorMessage(errorCode, errorMessage));
        break;
      }

      case "run_paused":
        setIsStreaming(false);
        setActiveNodeInfo(null);
        break;

      case "run_completed":
        markStepStreamTerminal();
        setIsStreaming(false);
        setActiveNodeInfo(null);
        setConnectionState("disconnected");
        break;

      case "heartbeat":
        // 活性时间戳已在 data 行统一刷新，这里无需额外处理。
        break;
    }
  };

  useEffect(() => {
    stepStreamTerminalRef.current = false;
    return () => {
      disconnect();
    };
  }, [runId, disconnect]);

  return {
    events,
    streamingText,
    isStreaming,
    currentPhase,
    activeNodeInfo,
    toolCalls,
    executionSteps,
    streamStartedAt,
    clusterAgents,
    connectionState,
    error,
    lastEventAt,
    reconnectFailures,
    connect,
    ensureConnected,
    disconnect,
    isStepStreamTerminal,
    clearStepStreamTerminal,
  };
}
