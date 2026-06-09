import { useState, useEffect, useRef, useCallback } from "react";
import type {
  StreamEvent,
  RunStreamState,
  StreamConnectionState,
  AgentPhase,
  RuntimeCapabilityItem,
} from "../types/runtime-types";
import { API_BASE_URL } from "../services/apiClient";

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
  const [clusterAgents, setClusterAgents] = useState<
    RunStreamState["clusterAgents"]
  >([]);
  const [connectionState, setConnectionState] =
    useState<StreamConnectionState>("disconnected");
  const [error, setError] = useState<string | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectionStateRef = useRef<StreamConnectionState>("disconnected");
  const connectSessionRef = useRef(0);
  const connectPromiseRef = useRef<Promise<void> | null>(null);
  const pendingConnectResolversRef = useRef<Array<() => void>>([]);

  const disconnect = useCallback(() => {
    connectSessionRef.current += 1;
    connectPromiseRef.current = null;
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
  }, []);

  const resolvePendingConnectors = useCallback(() => {
    pendingConnectResolversRef.current.forEach((resolve) => resolve());
    pendingConnectResolversRef.current = [];
  }, []);

  const connect = useCallback((): Promise<void> => {
    if (connectionStateRef.current === "connected") {
      return Promise.resolve();
    }
    if (connectionStateRef.current === "connecting" && connectPromiseRef.current) {
      return connectPromiseRef.current;
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

    const url = `${API_BASE_URL}/api/tenants/${tenantId}/workbench/runs/${runId}/stream`;

    connectPromiseRef.current = (async function startStream() {
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
        setConnectionState("connected");
        resolvePendingConnectors();

        const reader = response.body?.getReader();
        if (!reader) {
          throw new Error("Response body is not readable");
        }

        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        let currentEventType = "";

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

            if (trimmed.startsWith("event:")) {
              currentEventType = trimmed.substring(6).trim();
            } else if (trimmed.startsWith("data:")) {
              const dataStr = trimmed.substring(5).trim();
              if (dataStr === "[DONE]") {
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
                } as StreamEvent;

                setEvents((prev) => [...prev, streamEvent]);
                handleStreamEvent(streamEvent);
              } catch (e) {
                console.error("Failed to parse SSE data line:", dataStr, e);
              }

              currentEventType = "";
            }
          }
        }

        if (session === connectSessionRef.current) {
          connectionStateRef.current = "disconnected";
          setConnectionState("disconnected");
        }
      } catch (err: unknown) {
        if (session !== connectSessionRef.current) {
          return;
        }
        if (err instanceof Error && err.name === "AbortError") {
          return;
        }
        console.error("SSE connection error:", err);
        connectionStateRef.current = "error";
        setConnectionState("error");
        setError(err instanceof Error ? err.message : "连接断开");
        resolvePendingConnectors();

        if (!controller.signal.aborted) {
          setConnectionState("reconnecting");
          reconnectTimeoutRef.current = setTimeout(() => {
            if (session !== connectSessionRef.current) {
              return;
            }
            connectionStateRef.current = "disconnected";
            setConnectionState("disconnected");
            void connect();
          }, 3000);
        }
      } finally {
        if (connectPromiseRef.current) {
          connectPromiseRef.current = null;
        }
      }
    })();

    return connectPromiseRef.current;
  }, [tenantId, runId, token, disconnect, resolvePendingConnectors]);

  const ensureConnected = useCallback(async (timeoutMs = 8000) => {
    if (connectionStateRef.current === "connected") {
      return;
    }
    const connectPromise = connect();
    if (connectionStateRef.current === "connected") {
      return;
    }
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        reject(new Error("SSE 连接超时，请稍后重试"));
      }, timeoutMs);
      pendingConnectResolversRef.current.push(() => {
        window.clearTimeout(timer);
        if (connectionStateRef.current === "connected") {
          resolve();
          return;
        }
        reject(new Error("SSE 连接失败"));
      });
    });
    await connectPromise;
  }, [connect]);

  const handleStreamEvent = (event: StreamEvent) => {
    switch (event.type) {
      case "connected":
        // Connection established
        break;

      case "node_started": {
        const { nodeRunId, nodeName, nodeType } = event.data;
        setActiveNodeInfo({ nodeRunId, nodeName, nodeType });
        setStreamingText("");
        setIsStreaming(true);
        setCurrentPhase("preparing");
        setToolCalls([]);
        setClusterAgents([]);
        break;
      }

      case "agent_thinking": {
        const { phase } = event.data;
        setCurrentPhase(phase);
        break;
      }

      case "agent_streaming": {
        const { accumulatedContent } = event.data;
        setStreamingText(accumulatedContent);
        setCurrentPhase("model_calling");
        setIsStreaming(true);
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
            return copy;
          } else {
            return [...prev, updated];
          }
        });
        break;
      }

      case "cluster_agent": {
        const {
          agentIndex,
          agentName,
          eventType,
          deltaContent,
          accumulatedContent,
          toolName,
          toolType,
          toolStatus,
          outputSummary,
          result,
          durationMs,
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
            agent.outputSummary = outputSummary || "";
          } else if (eventType === "failed") {
            agent.status = "failed";
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
        setIsStreaming(false);
        setCurrentPhase("completed");
        setActiveNodeInfo(null);
        break;

      case "node_failed": {
        const { errorMessage } = event.data;
        setIsStreaming(false);
        setCurrentPhase("failed");
        setActiveNodeInfo(null);
        setError(errorMessage);
        break;
      }

      case "run_paused":
        setIsStreaming(false);
        setActiveNodeInfo(null);
        break;

      case "run_completed":
        setIsStreaming(false);
        setActiveNodeInfo(null);
        setConnectionState("disconnected");
        break;

      case "heartbeat":
        break;
    }
  };

  useEffect(() => {
    return () => {
      disconnect();
    };
  }, [disconnect]);

  return {
    events,
    streamingText,
    isStreaming,
    currentPhase,
    activeNodeInfo,
    toolCalls,
    clusterAgents,
    connectionState,
    error,
    connect,
    ensureConnected,
    disconnect,
  };
}
