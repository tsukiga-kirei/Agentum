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
  const reconnectTimeoutRef = useRef<any>(null);
  const connectionStateRef = useRef<StreamConnectionState>("disconnected");

  // Keep ref of connection state to avoid stale closure in loops
  useEffect(() => {
    connectionStateRef.current = connectionState;
  }, [connectionState]);

  const disconnect = useCallback(() => {
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

  const connect = useCallback(() => {
    if (connectionStateRef.current === "connected" || connectionStateRef.current === "connecting") {
      return;
    }

    disconnect();
    setConnectionState("connecting");
    setError(null);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const url = `${API_BASE_URL}/api/tenants/${tenantId}/workbench/runs/${runId}/stream`;

    async function startStream() {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: "text/event-stream",
          },
          signal: controller.signal,
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        setConnectionState("connected");

        const reader = response.body?.getReader();
        if (!reader) {
          throw new Error("Response body is not readable");
        }

        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        let currentEventType = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          // The last line might be incomplete, keep it in buffer
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
                disconnect();
                break;
              }

              try {
                const parsedData = JSON.parse(dataStr);
                const streamEvent = {
                  type: currentEventType || "message",
                  data: parsedData,
                } as StreamEvent;

                setEvents((prev) => [...prev, streamEvent]);

                // Dispatch stream event to update state
                handleStreamEvent(streamEvent);
              } catch (e) {
                console.error("Failed to parse SSE data line:", dataStr, e);
              }

              currentEventType = "";
            }
          }
        }
      } catch (err: any) {
        if (err.name === "AbortError") {
          return;
        }
        console.error("SSE connection error:", err);
        setConnectionState("error");
        setError(err.message || "连接断开");

        // Simple reconnect logic after 3 seconds
        if (controller.signal.aborted === false) {
          setConnectionState("reconnecting");
          reconnectTimeoutRef.current = setTimeout(() => {
            connect();
          }, 3000);
        }
      }
    }

    startStream();
  }, [tenantId, runId, token, disconnect]);

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
          toolStatus,
          outputSummary,
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
              kind: "mcp", // default to mcp in subagents for now
              status: toolStatus === "started" ? "running" : toolStatus === "completed" ? "done" : "error",
              statusLabel: toolStatus === "started" ? "调用中" : toolStatus === "completed" ? "调用完成" : "调用失败",
              summary: toolStatus === "started" ? `正在调用 ${toolName}...` : toolStatus === "completed" ? `调用成功` : `调用失败`,
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
        break;

      case "node_failed": {
        const { errorMessage } = event.data;
        setIsStreaming(false);
        setCurrentPhase("failed");
        setError(errorMessage);
        break;
      }

      case "run_paused":
        setIsStreaming(false);
        break;

      case "run_completed":
        setIsStreaming(false);
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
    disconnect,
  };
}
