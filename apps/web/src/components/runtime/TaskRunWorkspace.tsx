import React, { useState, useEffect, useMemo, useRef } from "react";
import { Segmented } from "antd";
import type { 
  RuntimePreview, 
  RuntimePreviewStep, 
  RuntimeStepState, 
  RuntimeNodeKind,
  RuntimeNodeField,
  RuntimeChatMessage,
  RuntimeCapabilityItem,
  StreamEvent,
} from "../../types/runtime-types";
import type { WorkbenchRunDetail } from "../../types/workbench";
import { useRunStream } from "../../hooks/useRunStream";
import { StepProgressRail } from "./StepProgressRail";
import { StepActionBar } from "./StepActionBar";
import { SingleAgentPanel } from "./SingleAgentPanel";
import { UserInputPanel } from "./UserInputPanel";
import { MultiAgentPanel } from "./MultiAgentPanel";
import { DeliveryPreviewPanel } from "./DeliveryPreviewPanel";
import { DeliveryResultPanel } from "./DeliveryResultPanel";
import { resolveDirectDeliveryContent } from "../../utils/deliveryContent";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { workbenchApi } from "../../services/apiClient";
import { formatRuntimeErrorMessage } from "../../utils/runtimeErrors";
import { mergeClusterAgents, parseClusterAgentSummariesFromOutputs, clusterAgentDisplayText } from "../../utils/clusterAgentsMerge";
import { WorkbenchGlobalActions } from "../workbench/SurfacePageLayout";
import { 
  Save, 
  Trash2, 
  History, 
  LayoutDashboard, 
  Activity, 
  FileText, 
  Package, 
  FileCheck,
  RotateCcw,
  CheckCircle2,
  Send
} from "lucide-react";

interface TaskRunWorkspaceProps {
  run: WorkbenchRunDetail;
  tenantId: string;
  token: string;
  onBack: () => void;
  onSave: () => void;
  onDelete: () => void;
  onReload: (updated: WorkbenchRunDetail) => void;
}

type RunWorkspaceTab = "overview" | "current" | "trace" | "deliveries";

/** 看门狗判定：activeJob 显示运行中但超过该阈值无任何事件（含 heartbeat）即视为异常。 */
const WATCHDOG_STALE_THRESHOLD_MS = 60_000;
/** 看门狗判定：SSE 连续重连失败达到该次数即视为前后端关联失效。 */
const WATCHDOG_RECONNECT_FAILURE_LIMIT = 3;

function isRunFlowCompleted(run: WorkbenchRunDetail): boolean {
  // 仅以任务终态为准；progress=100% 但 state=paused 表示最后节点待确认，不能误判为已完成。
  return run.state === "completed";
}

/** 后端在途作业是否仍活跃（queued/running）。 */
function isActiveJobAlive(run: WorkbenchRunDetail): boolean {
  return !!run.activeJob && (run.activeJob.status === "queued" || run.activeJob.status === "running");
}

/** 重新进入/刷新时：未完成且可编辑的任务默认打开「当前处理」。 */
function resolveInitialRunTab(run: WorkbenchRunDetail): RunWorkspaceTab {
  if (run.readOnly || isRunFlowCompleted(run)) {
    return "overview";
  }
  return "current";
}

export function TaskRunWorkspace({
  run: initialRun,
  tenantId,
  token,
  onBack,
  onSave,
  onDelete,
  onReload,
}: TaskRunWorkspaceProps) {
  const [runDetail, setRunDetail] = useState<WorkbenchRunDetail>(initialRun);
  const [activeRunTab, setActiveRunTab] = useState<RunWorkspaceTab>(() => resolveInitialRunTab(initialRun));
  const [selectedTraceStepIndex, setSelectedTraceStepIndex] = useState<number | null>(null);
  const [isAdvancing, setIsAdvancing] = useState(false);
  const [advanceError, setAdvanceError] = useState<string | null>(null);
  // 前端看门狗：SSE 连续失败或后台长时间无事件且探测失败时，主动亮出被动「恢复进度」按钮。
  const [watchdogStaleMessage, setWatchdogStaleMessage] = useState<string | null>(null);
  const processedStreamEventsRef = useRef(0);

  const stream = useRunStream(tenantId, runDetail.id, token);

  async function reloadRunDetail(): Promise<WorkbenchRunDetail | null> {
    try {
      const updated = await workbenchApi.getRun(tenantId, token, runDetail.id);
      setRunDetail(updated);
      onReload(updated);
      return updated;
    } catch (error: unknown) {
      console.error("刷新任务运行态失败", error);
      return null;
    }
  }

  useEffect(() => {
    if (stream.events.length <= processedStreamEventsRef.current) {
      return;
    }
    const newEvents = stream.events.slice(processedStreamEventsRef.current);
    processedStreamEventsRef.current = stream.events.length;
    const shouldReload = newEvents.some(
      (event) =>
        event.type === "node_completed"
        || event.type === "run_paused"
        || event.type === "run_completed"
        || event.type === "node_failed"
    );
    const failedEvent = newEvents.find((event) => event.type === "node_failed");
    if (failedEvent && failedEvent.type === "node_failed") {
      setAdvanceError(formatRuntimeErrorMessage(failedEvent.data.errorCode, failedEvent.data.errorMessage));
      setActiveRunTab("current");
      stream.disconnect();
    }
    if (newEvents.some((event) => event.type === "run_completed")) {
      setActiveRunTab("deliveries");
      stream.disconnect();
    }
    if (shouldReload) {
      void reloadRunDetail();
    }
  }, [stream.events, tenantId, token, runDetail.id, onReload, stream.disconnect]);

  useEffect(() => {
    if (stream.error) {
      setAdvanceError(stream.error);
      setActiveRunTab("current");
    }
  }, [stream.error]);

  useEffect(() => {
    processedStreamEventsRef.current = 0;
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    stream.disconnect();
  }, [runDetail.id, stream.disconnect]);

  useEffect(() => {
    return () => {
      stream.disconnect();
    };
  }, [stream.disconnect]);

  // Sync state if initialRun updates from parent
  useEffect(() => {
    setRunDetail(initialRun);
  }, [initialRun]);

  // 切换任务或刷新后，未完成流程默认回到「当前处理」页签。
  useEffect(() => {
    setActiveRunTab(resolveInitialRunTab(runDetail));
    setSelectedTraceStepIndex(null);
  }, [runDetail.id]);

  // 2. Derive preview representation from raw run detail
  const basePreview = useMemo(() => {
    return buildRuntimePreviewFromRun(runDetail);
  }, [runDetail]);

  // 3. Merge live SSE stream states into the preview steps list
  const preview = useMemo((): RuntimePreview => {
    if (!basePreview) return basePreview;

    const streamActiveNodeId = stream.activeNodeInfo?.nodeRunId;
    const mergingLiveStep =
      stream.isStreaming
      && stream.connectionState === "connected"
      && !!streamActiveNodeId;

    const updatedSteps = basePreview.steps.map((step) => {
      // 后端已标记失败/完成时，不再被 SSE 流式态覆盖为「运行中」。
      if (step.state === "failed" || step.state === "done") {
        return step;
      }
      if (mergingLiveStep && step.nodeRunId === streamActiveNodeId) {
        const chatMessages = [...(step.chatMessages || [])];
        if (stream.streamingText) {
          const lastMsg = chatMessages[chatMessages.length - 1];
          if (lastMsg && lastMsg.role === "assistant" && lastMsg.streaming) {
            chatMessages[chatMessages.length - 1] = {
              ...lastMsg,
              content: stream.streamingText,
            };
          } else {
            chatMessages.push({
              id: "streaming-message",
              role: "assistant",
              author: step.title,
              content: stream.streamingText,
              streaming: true,
            });
          }
        }

        return {
          ...step,
          state: "running" as const,
          agentPhase: stream.currentPhase || undefined,
          chatMessages,
          capabilities: stream.toolCalls.length > 0 ? stream.toolCalls : step.capabilities,
        };
      }
      return step;
    });

    return {
      ...basePreview,
      statusLabel: mergingLiveStep
        ? "正在执行"
        : runDetail.state === "paused"
        ? "已暂停"
        : basePreview.statusLabel,
      steps: updatedSteps,
    };
  }, [
    basePreview,
    stream.activeNodeInfo,
    stream.streamingText,
    stream.currentPhase,
    stream.toolCalls,
    stream.isStreaming,
    stream.connectionState,
    runDetail.state,
  ]);

  const backendStepIndex = resolveActiveStepIndex(preview.steps, runDetail);
  const streamingStepIndex = stream.activeNodeInfo
    ? preview.steps.findIndex((step) => step.nodeRunId === stream.activeNodeInfo?.nodeRunId)
    : -1;
  const currentStepIndex = streamingStepIndex >= 0 ? streamingStepIndex : backendStepIndex;
  const activeStep = preview.steps[currentStepIndex] ?? preview.steps[backendStepIndex] ?? preview.steps[0];
  const isLastStep = currentStepIndex === preview.steps.length - 1;
  const isLiveExecuting =
    stream.isStreaming
    && stream.connectionState === "connected"
    && stream.activeNodeInfo?.nodeRunId === activeStep.nodeRunId;

  const isFlowCompleted = isRunFlowCompleted(runDetail);

  const stepErrorMessage =
    resolveStepErrorMessage(activeStep)
    ?? advanceError
    ?? stream.error;

  const isStreamableStep =
    activeStep.kind === "agent" || activeStep.kind === "multiAgent";

  const activeJobAlive = isActiveJobAlive(runDetail);
  const stepCanceled = activeStep.state === "canceled";

  // 刷新/重进时：后端作业仍在执行（activeJob queued/running 或节点 running）→ 自动重连 SSE
  // 并整步回放进度，做到无感恢复；绝不重复触发 advance。
  useEffect(() => {
    if (runDetail.readOnly) {
      return;
    }
    const backendStillExecuting = activeJobAlive || activeStep.state === "running";
    if (!backendStillExecuting) {
      return;
    }
    if (stream.connectionState === "connected" || stream.connectionState === "connecting") {
      return;
    }
    void stream.connect({ replay: true });
  }, [
    runDetail.readOnly,
    runDetail.id,
    activeJobAlive,
    activeStep.state,
    activeStep.nodeRunId,
    stream.connectionState,
    stream.connect,
  ]);

  // 节点到达终态后清除看门狗异常标记（恢复进度/重新执行成功后不再残留提示）。
  useEffect(() => {
    if (activeStep.state === "done" || activeStep.state === "pending") {
      setWatchdogStaleMessage(null);
    }
  }, [activeStep.nodeRunId, activeStep.state]);

  // 失败或被中断的步骤自动切到「当前处理」，让用户第一时间看到恢复按钮。
  useEffect(() => {
    if ((activeStep.state === "failed" || activeStep.state === "canceled") && !isFlowCompleted && !runDetail.readOnly) {
      setActiveRunTab("current");
    }
  }, [activeStep.nodeRunId, activeStep.state, isFlowCompleted, runDetail.readOnly]);

  // activeJob 轮询兜底：后台执行期间每 10s 拉一次任务详情，即使 SSE 完全失效，
  // 节点完成/失败的状态也能收敛到前端（同时为看门狗提供探测样本）。
  useEffect(() => {
    if (runDetail.readOnly || !activeJobAlive) {
      return;
    }
    const timer = window.setInterval(() => {
      void reloadRunDetail();
    }, 10_000);
    return () => {
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runDetail.readOnly, runDetail.id, activeJobAlive]);

  // 前端看门狗：满足任一条件即判定异常，亮出被动「恢复进度」按钮。
  // 1) SSE 连续重连失败达到阈值（前后端关联失效）；
  // 2) activeJob 显示运行中，但超过阈值无任何事件（含 heartbeat），且 getRun 探测后仍无进展。
  useEffect(() => {
    if (runDetail.readOnly || watchdogStaleMessage) {
      return;
    }
    if (stream.reconnectFailures >= WATCHDOG_RECONNECT_FAILURE_LIMIT) {
      setWatchdogStaleMessage("与执行服务的连接持续失败，页面已无法获取最新进度。");
      return;
    }
    if (!activeJobAlive) {
      return;
    }
    const timer = window.setInterval(() => {
      const lastSeen = stream.lastEventAt;
      const sinceLastEvent = lastSeen === null ? Number.POSITIVE_INFINITY : Date.now() - lastSeen;
      if (stream.connectionState === "connected" && sinceLastEvent < WATCHDOG_STALE_THRESHOLD_MS) {
        return;
      }
      // 长时间无事件：主动探测一次后端，若作业已消失则由轮询兜底收敛，否则判定执行无响应。
      void (async () => {
        const probed = await reloadRunDetail();
        if (!probed) {
          setWatchdogStaleMessage("无法从服务端获取任务状态，执行进度可能已中断。");
          return;
        }
        const probeLastSeen = stream.lastEventAt;
        const probeSince = probeLastSeen === null ? Number.POSITIVE_INFINITY : Date.now() - probeLastSeen;
        if (isActiveJobAlive(probed) && probeSince >= WATCHDOG_STALE_THRESHOLD_MS) {
          setWatchdogStaleMessage("后台执行长时间无响应（超过 1 分钟未收到任何进度心跳）。");
        }
      })();
    }, 15_000);
    return () => {
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    runDetail.readOnly,
    runDetail.id,
    activeJobAlive,
    watchdogStaleMessage,
    stream.reconnectFailures,
    stream.connectionState,
  ]);

  useEffect(() => {
    if (isFlowCompleted && activeRunTab === "current") {
      setActiveRunTab("overview");
    }
  }, [isFlowCompleted, activeRunTab]);

  const initialAutoStartRef = useRef(false);

  useEffect(() => {
    initialAutoStartRef.current = false;
  }, [runDetail.id, runDetail.nodes]);

  // 智能体/多智能体/交付执行完成后切到「当前处理」，展示结果与「确认并执行下一步」。
  useEffect(() => {
    if (isFlowCompleted || runDetail.readOnly) {
      return;
    }
    if (
      activeStep.state === "done"
      && (activeStep.kind === "agent" || activeStep.kind === "multiAgent" || activeStep.kind === "delivery")
    ) {
      setActiveRunTab("current");
    }
  }, [activeStep.nodeRunId, activeStep.state, activeStep.kind, isFlowCompleted, runDetail.readOnly]);

  // 进入即执行：当前步骤为待执行智能体/集群且后端无在途作业时自动启动。
  // 节点 canceled（主动中断）或 failed 时不自动启动，由用户点「重新执行 / 恢复进度」。
  useEffect(() => {
    if (initialAutoStartRef.current) {
      return;
    }
    if (runDetail.readOnly || isAdvancing || isLiveExecuting || activeJobAlive) {
      return;
    }
    if (activeStep.state !== "pending") {
      return;
    }
    if (activeStep.kind !== "agent" && activeStep.kind !== "multiAgent") {
      return;
    }
    initialAutoStartRef.current = true;
    void handleAdvanceStep();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runDetail.id, activeStep.nodeRunId, activeStep.state, activeStep.kind, activeJobAlive]);

  const isStreamReconnecting =
    isStreamableStep
    && activeStep.state === "running"
    && (stream.connectionState === "connecting" || stream.connectionState === "reconnecting");

  const clusterAgentsForPanel = useMemo(() => {
    if (activeStep.kind !== "multiAgent") {
      return [];
    }
    const configAgents = Array.isArray(activeStep.configSnapshot?.clusterAgents)
      ? (activeStep.configSnapshot?.clusterAgents as Array<Record<string, unknown>>)
      : [];
    const stepRunning =
      activeStep.state === "running"
      || isLiveExecuting;

    return mergeClusterAgents({
      configAgents,
      outputs: activeStep.outputs,
      streamAgents: stream.clusterAgents,
      stepState: activeStep.state,
      stepRunning,
    });
  }, [
    stream.clusterAgents,
    isLiveExecuting,
    activeStep,
  ]);

  function handleTabChange(tab: RunWorkspaceTab) {
    setActiveRunTab(tab);
    if (tab === "trace") {
      setSelectedTraceStepIndex(null);
    }
  }

  function handleStepSelect(step: RuntimePreviewStep, index: number) {
    if (index === currentStepIndex) {
      if (isFlowCompleted) {
        if (step.state === "done") {
          setSelectedTraceStepIndex(index);
          setActiveRunTab("trace");
        } else {
          setActiveRunTab("overview");
        }
        return;
      }
      setActiveRunTab("current");
      return;
    }

    if (step.state === "done") {
      setSelectedTraceStepIndex(index);
      setActiveRunTab("trace");
      return;
    }

    setSelectedTraceStepIndex(index);
    if (!isFlowCompleted) {
      setActiveRunTab("current");
    }
  }

  // 4. Action Handlers: Advance Step
  // advance 仅创建执行作业并立即返回（202 语义），真实进度由 SSE 事件与 activeJob 轮询驱动，
  // 不再阻塞等待节点完成。
  async function handleAdvanceStep() {
    if (isAdvancing) {
      return;
    }
    setIsAdvancing(true);
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    try {
      // 先建 SSE 连接再入队，确保 node_started 起的所有事件都能实时收到。
      await stream.ensureConnected();
      const afterAdvance = await workbenchApi.advanceStep(tenantId, token, runDetail.id);
      setRunDetail(afterAdvance);
      onReload(afterAdvance);
    } catch (error: unknown) {
      console.error("推进步骤失败", error);
      const reloaded = await reloadRunDetail();
      if (reloaded && isActiveJobAlive(reloaded)) {
        setAdvanceError(null);
      } else {
        const message = error instanceof Error ? error.message : "推进步骤失败";
        setAdvanceError(message);
        setActiveRunTab("current");
      }
    } finally {
      setIsAdvancing(false);
    }
  }

  // Submit User Input payload
  async function handleCompleteTodo(payload: Record<string, unknown>) {
    if (!runDetail.openTodo?.openTodoId) return;
    try {
      const updated = await workbenchApi.completeTodoWithPayload(
        tenantId, 
        token, 
        runDetail.openTodo.openTodoId, 
        payload
      );
      setRunDetail(updated);
      onReload(updated);
      // 输入类待办完成后，若下一步为待执行智能体，则自动启动（仍不等同于完成后自动跳步）。
      const nextPreview = buildRuntimePreviewFromRun(updated);
      const nextStepIndex = resolveActiveStepIndex(nextPreview.steps, updated);
      const nextStep = nextPreview.steps[nextStepIndex];
      if (
        nextStep
        && (nextStep.kind === "agent" || nextStep.kind === "multiAgent")
        && nextStep.state === "pending"
      ) {
        initialAutoStartRef.current = true;
        await handleAdvanceStep();
      }
    } catch (e: unknown) {
      console.error("提交资料失败", e);
    }
  }

  // Handle human review approval
  async function handleApprove(comment: string) {
    if (!runDetail.openTodo?.openTodoId) return;
    try {
      const updated = await workbenchApi.completeTodo(
        tenantId, 
        token, 
        runDetail.openTodo.openTodoId, 
        comment, 
        { approved: true }
      );
      setRunDetail(updated);
      onReload(updated);
    } catch (e: any) {
      console.error("审核通过操作失败", e);
    }
  }

  // Handle human review rejection
  async function handleReject(comment: string) {
    if (!runDetail.openTodo?.openTodoId) return;
    try {
      const updated = await workbenchApi.completeTodo(
        tenantId, 
        token, 
        runDetail.openTodo.openTodoId, 
        comment, 
        { approved: false }
      );
      setRunDetail(updated);
      onReload(updated);
    } catch (e: any) {
      console.error("驳回操作失败", e);
    }
  }

  // 主动中断：后端将节点置为 canceled 并清空该步骤全部运行数据，前端断开 SSE。
  // 中断后只能「重新执行」整步重跑，状态由节点 canceled 驱动（不再使用 sessionStorage 标记）。
  async function handleInterruptStream() {
    stream.disconnect();
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    try {
      const updated = await workbenchApi.interruptRun(tenantId, token, runDetail.id);
      setRunDetail(updated);
      onReload(updated);
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "中断执行失败";
      console.error("中断执行失败", error);
      setAdvanceError(message);
      await reloadRunDetail();
    }
  }

  // 主动「重新执行」：清空当前节点全部数据（含已成功子智能体）后从头重跑。
  async function handleRestartStep() {
    if (!activeStep.nodeRunId || isAdvancing) {
      return;
    }
    setIsAdvancing(true);
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    stream.disconnect();
    try {
      const updated = await workbenchApi.restartNode(tenantId, token, runDetail.id, activeStep.nodeRunId);
      setRunDetail(updated);
      onReload(updated);
      // restart 已重置 Redis Stream，回放即可拿到新一轮 node_started 起的全部事件。
      await stream.connect({ replay: true });
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "重新执行失败";
      console.error("重新执行当前步骤失败", error);
      setAdvanceError(message);
      await reloadRunDetail();
    } finally {
      setIsAdvancing(false);
    }
  }

  // 被动「恢复进度」：保留已成功子智能体结果，仅重跑失败/未完成部分，损失最小。
  async function handleRecoverStep() {
    if (!activeStep.nodeRunId || isAdvancing) {
      return;
    }
    setIsAdvancing(true);
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    stream.disconnect();
    try {
      const updated = await workbenchApi.recoverNode(tenantId, token, runDetail.id, activeStep.nodeRunId);
      setRunDetail(updated);
      onReload(updated);
      await stream.connect({ replay: true });
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "恢复进度失败";
      console.error("恢复当前步骤进度失败", error);
      setAdvanceError(message);
      await reloadRunDetail();
    } finally {
      setIsAdvancing(false);
    }
  }

  async function handleSaveAnswer(content: string) {
    const targetStep = preview.steps[resolveActiveStepIndex(preview.steps, runDetail)];
    if (!targetStep?.nodeRunId || isAdvancing) {
      return;
    }

    setIsAdvancing(true);
    setAdvanceError(null);

    try {
      const updated = await workbenchApi.updateFinalAnswer(
        tenantId,
        token,
        runDetail.id,
        targetStep.nodeRunId,
        content,
      );
      setRunDetail(updated);
      onReload(updated);
    } catch (error: unknown) {
      const reason = error instanceof Error ? error.message : "保存最终答案失败";
      setAdvanceError(reason);
    } finally {
      setIsAdvancing(false);
    }
  }

  async function handleFollowUpStep(followUpMessage: string) {
    const targetStep = preview.steps[resolveActiveStepIndex(preview.steps, runDetail)];
    if (!targetStep?.nodeRunId || isAdvancing) {
      return;
    }

    setIsAdvancing(true);
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    initialAutoStartRef.current = true;
    stream.disconnect();

    try {
      const updated = await workbenchApi.followUpNode(
        tenantId,
        token,
        runDetail.id,
        targetStep.nodeRunId,
        followUpMessage,
      );
      setRunDetail(updated);
      onReload(updated);
      await stream.connect({ replay: true });
      await stream.ensureConnected();
    } catch (error: unknown) {
      console.error("追问失败", error);
      const reloaded = await reloadRunDetail();
      if (reloaded && isActiveJobAlive(reloaded)) {
        setAdvanceError(null);
        return;
      }
      const reason = error instanceof Error ? error.message : "追问失败";
      setAdvanceError(reason);
      setActiveRunTab("current");
    } finally {
      setIsAdvancing(false);
    }
  }

  async function handleRegenerateStep() {
    const targetStep = preview.steps[resolveActiveStepIndex(preview.steps, runDetail)];
    if (!targetStep?.nodeRunId || isAdvancing) {
      return;
    }

    setIsAdvancing(true);
    setAdvanceError(null);
    setWatchdogStaleMessage(null);
    // rollback 会把智能体节点重置为 pending，需阻止「进入即执行」与本次手动 advance 并发入队。
    initialAutoStartRef.current = true;
    stream.disconnect();

    try {
      const updated = await workbenchApi.rollbackRun(tenantId, token, runDetail.id, targetStep.nodeRunId);
      setRunDetail(updated);
      onReload(updated);
      await stream.connect({ replay: true });
      await stream.ensureConnected();
      const afterAdvance = await workbenchApi.advanceStep(tenantId, token, runDetail.id);
      setRunDetail(afterAdvance);
      onReload(afterAdvance);
    } catch (error: unknown) {
      console.error("重新执行失败", error);
      const reloaded = await reloadRunDetail();
      if (reloaded && isActiveJobAlive(reloaded)) {
        setAdvanceError(null);
        return;
      }
      const message = error instanceof Error ? error.message : "重新执行失败";
      setAdvanceError(message);
      setActiveRunTab("current");
    } finally {
      setIsAdvancing(false);
    }
  }

  async function handleRollback(nodeRunId: string) {
    try {
      const updated = await workbenchApi.rollbackRun(tenantId, token, runDetail.id, nodeRunId);
      setRunDetail(updated);
      onReload(updated);
      // 回退成功后流程回到目标节点，顺势切回“当前处理”页签并清除历史选中，
      // 避免停留在“执行历史”页签让用户误以为还在查看旧快照。
      setSelectedTraceStepIndex(null);
      setActiveRunTab("current");
    } catch (e: any) {
      console.error("回退步骤失败", e);
    }
  }

  const runWorkspaceTabs = [
    { key: "overview" as const, label: "任务总览", icon: LayoutDashboard },
    ...(!isFlowCompleted ? [{ key: "current" as const, label: "当前处理", icon: Activity }] : []),
    { key: "trace" as const, label: "执行历史", icon: FileText },
    { key: "deliveries" as const, label: "产品交付", icon: Package },
  ];

  const tabSegmentedOptions = runWorkspaceTabs.map((tab) => {
    const Icon = tab.icon;
    return {
      value: tab.key,
      label: (
        <span className="login-portal-option">
          <Icon className="login-portal-option-icon" aria-hidden="true" />
          <span>{tab.label}</span>
        </span>
      ),
    };
  });

  return (
    <section className="workbench-task-workspace sys-fade-in flex flex-col h-full bg-[var(--color-bg-page)] overflow-hidden" aria-label="任务处理工作区">
      {/* 5a. Topbar bar actions */}
      <header className="surface-page-chrome pb-4 border-b border-[var(--color-border-light)] flex flex-col gap-4">
        {/* Row 1: Title, Page Actions, and Global Actions (Theme/Role switcher) */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between w-full">
          <div className="flex min-w-0 gap-4">
            <div className="workbench-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)] bg-blue-500/10 text-blue-500">
              <Activity className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">
                  {runDetail.title}
                </h1>
                {!runDetail.saved && <span className="rounded-full bg-amber-50 dark:bg-amber-950/40 px-2.5 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400 ring-1 ring-amber-200 dark:ring-amber-900/50">草稿</span>}
                {runDetail.saved && !runDetail.readOnly && <span className="rounded-full bg-blue-50 dark:bg-blue-950/40 px-2.5 py-0.5 text-xs font-medium text-blue-600 dark:text-blue-450 ring-1 ring-blue-200 dark:ring-blue-900/50">已保存</span>}
                {runDetail.readOnly && <span className="rounded-full bg-slate-100 dark:bg-slate-800 px-2.5 py-0.5 text-xs font-medium text-slate-500 dark:text-slate-400 ring-1 ring-slate-200 dark:ring-slate-700/50">只读</span>}
              </div>
              <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed text-slate-500 dark:text-slate-400">
                {runDetail.workflowName} · 任务单号 {preview.runId} · v{preview.workflowVersion} · 当前步骤：<strong>{activeStep.title}</strong>
              </p>
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-3 sm:pt-0.5">
            <div className="workbench-run-actions flex items-center gap-2 shrink-0">
              {!runDetail.saved && (
                <button type="button" className="sys-btn sys-btn--primary flex items-center gap-1.5 text-xs px-3 py-1.5" onClick={onSave}>
                  <Save size={14} />
                  保存
                </button>
              )}
              {!runDetail.readOnly && (
                <button type="button" className="sys-btn sys-btn--danger flex items-center gap-1.5 text-xs px-3 py-1.5" onClick={onDelete}>
                  <Trash2 size={14} />
                  删除
                </button>
              )}
              <button type="button" className="sys-btn sys-btn--default flex items-center gap-1.5 text-xs px-3 py-1.5" onClick={onBack}>
                <History size={14} />
                返回列表
              </button>
            </div>
            <div className="border-l border-slate-200 dark:border-slate-850 pl-3 h-6 flex items-center">
              <WorkbenchGlobalActions />
            </div>
          </div>
        </div>

        {/* Row 2: Tab Switcher (Segmented) */}
        <div className="system-mgmt-module-switch border-t border-slate-100 dark:border-slate-800/80 pt-3">
          <div className="system-mgmt-segmented-scroll">
            <Segmented<RunWorkspaceTab>
              value={activeRunTab}
              options={tabSegmentedOptions}
              onChange={(value) => handleTabChange(value)}
              className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
            />
          </div>
        </div>
      </header>

      {/* 5b. Main Workspace Stepper and Tabs */}
      <div className="workbench-task-layout overflow-hidden">
        <StepProgressRail
          preview={preview}
          activeStepIndex={currentStepIndex}
          selectedStepIndex={selectedTraceStepIndex}
          activeRunTab={activeRunTab}
          onStepSelect={handleStepSelect}
        />

        <section className="workbench-task-main flex-1 flex flex-col bg-white dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-xl shadow-sm overflow-hidden">
          {/* Panel Container Scroll */}
          <div className="flex-1 overflow-y-auto p-6 min-h-0">
            {activeRunTab === "overview" && (
              <RunOverviewPanel run={runDetail} preview={preview} />
            )}

            {activeRunTab === "current" && (
              <div className="space-y-4 max-w-4xl mx-auto">
                <header className="flex justify-between items-center gap-3 border-b border-slate-100 dark:border-slate-850 pb-3 mb-2">
                  <h3 className="text-sm font-bold text-slate-800 dark:text-slate-200 truncate">{activeStep.title}</h3>
                  <span className={`shrink-0 text-xs px-2 py-0.5 rounded-full font-medium ${
                    isAdvancing
                      ? "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                      : activeStep.state === "waiting"
                      ? "bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400"
                      : activeStep.state === "failed"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
                      : activeStep.state === "canceled"
                      ? "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
                      : activeStep.state === "done"
                      ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                      : activeStep.state === "pending"
                      ? "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
                      : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                  }`}>
                    {isAdvancing
                      ? "启动中"
                      : isLiveExecuting
                      ? "运行中"
                      : activeStep.state === "waiting"
                      ? "等待输入"
                      : activeStep.state === "failed"
                      ? "执行错误"
                      : activeStep.state === "canceled"
                      ? "已中断"
                      : activeStep.state === "done"
                      ? "已完成"
                      : activeStep.state === "pending"
                      ? "待执行"
                      : "运行中"}
                  </span>
                </header>

                {/* Render corresponding panel depending on node type */}
                {activeStep.kind === "input" ? (
                  <UserInputPanel
                    activeStep={activeStep}
                    readOnly={runDetail.readOnly}
                    onSubmit={handleCompleteTodo}
                  />
                ) : activeStep.kind === "agent" ? (
                  <SingleAgentPanel
                    activeStep={activeStep}
                    streamingText={stream.streamingText}
                    isStreaming={isLiveExecuting}
                    executionSteps={stream.executionSteps}
                    streamStartedAt={stream.streamStartedAt}
                    readOnly={runDetail.readOnly}
                    onSaveAnswer={(content) => handleSaveAnswer(content)}
                    onFollowUp={(followUpMessage) => handleFollowUpStep(followUpMessage)}
                  />
                ) : activeStep.kind === "multiAgent" ? (
                  <MultiAgentPanel
                    activeStep={activeStep}
                    clusterAgents={clusterAgentsForPanel}
                    isStreaming={isLiveExecuting}
                  />
                ) : activeStep.kind === "approval" ? (
                  <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-850 p-5 max-w-2xl mx-auto">
                    <div className="text-center py-10 text-slate-400">
                      <FileCheck size={28} className="mx-auto mb-2 text-amber-500" />
                    </div>
                  </div>
                ) : activeStep.kind === "delivery" ? (
                  activeStep.state === "pending" && !isAdvancing ? (
                    <DeliveryPreviewPanel activeStep={activeStep} runDetail={runDetail} />
                  ) : activeStep.state === "done" ? (
                    <DeliveryResultPanel activeStep={activeStep} />
                  ) : (
                  <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-850 p-5 space-y-4 max-w-2xl mx-auto">
                    <div className="text-center py-8 text-slate-500 dark:text-slate-400 text-sm leading-relaxed">
                      <Package size={28} className={`mx-auto mb-2 ${activeStep.state === "failed" ? "text-rose-500" : "text-emerald-500"}`} />
                      {activeStep.state === "failed"
                        ? (
                          <>
                            <p className="text-rose-600 dark:text-rose-400 font-medium mb-2">交付执行失败</p>
                            <p>{resolveStepErrorMessage(activeStep) ?? "请检查交付能力配置，或改用「直接输出交付」模式。"}</p>
                          </>
                        )
                        : isAdvancing
                          ? "正在执行交付步骤，请稍候..."
                          : "交付步骤执行中，请稍候..."}
                    </div>
                  </div>
                  )
                ) : (
                  <div className="text-center py-8 text-slate-400 text-xs">
                    正在执行后台自动系统节点...
                  </div>
                )}
              </div>
            )}

            {activeRunTab === "trace" && (
              <RunTracePanel
                preview={preview}
                readOnly={runDetail.readOnly}
                selectedStepIndex={selectedTraceStepIndex}
                onRollback={handleRollback}
                onOpenDeliveries={() => {
                  const deliveryIndex = preview.steps.findIndex((item) => item.kind === "delivery");
                  if (deliveryIndex >= 0) {
                    setSelectedTraceStepIndex(deliveryIndex);
                  }
                  setActiveRunTab("deliveries");
                }}
              />
            )}

            {activeRunTab === "deliveries" && (
              <RunDeliveriesPanel preview={preview} isFlowCompleted={isFlowCompleted} />
            )}
          </div>

          {/* 5c. Action Controller bar */}
          {activeRunTab === "current" && (
            <StepActionBar
              activeStep={activeStep}
              isStreaming={isLiveExecuting}
              isAdvancing={isAdvancing}
              isReconnecting={isStreamReconnecting}
              isRunCompleted={isFlowCompleted}
              isLastStep={isLastStep}
              stepCanceled={stepCanceled}
              stepFailed={runDetail.state === "failed" || activeStep.state === "failed"}
              watchdogStale={!!watchdogStaleMessage}
              failureMessage={watchdogStaleMessage ?? stepErrorMessage}
              readOnly={runDetail.readOnly}
              onAdvance={handleAdvanceStep}
              onCompleteTodo={(comment) => handleCompleteTodo({ comment })}
              onApprove={handleApprove}
              onReject={handleReject}
              onRetry={handleRegenerateStep}
              onBack={onBack}
              onInterrupt={handleInterruptStream}
              onRestart={handleRestartStep}
              onRecover={handleRecoverStep}
            />
          )}
        </section>
      </div>
    </section>
  );
}

// ============================================================================
// Internal helper components
// ============================================================================

function RunOverviewPanel({ run, preview }: { run: WorkbenchRunDetail; preview: RuntimePreview }) {
  return (
    <div className="workbench-panel-grid max-w-4xl mx-auto space-y-4">
      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
        <h3 className="text-sm font-bold text-slate-805 dark:text-slate-250 mb-2 flex items-center gap-1.5">
          <LayoutDashboard size={16} className="text-blue-500" /> 任务概览
        </h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-xs text-slate-400 block">运行编号</span>
            <strong className="text-sm text-slate-700 dark:text-slate-350 font-mono mt-0.5 block">{preview.runId}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-xs text-slate-400 block">流程版本</span>
            <strong className="text-sm text-slate-700 dark:text-slate-350 font-mono mt-0.5 block">v{preview.workflowVersion}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-xs text-slate-400 block">发起人</span>
            <strong className="text-sm text-slate-700 dark:text-slate-350 mt-0.5 block">{preview.ownerName}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-xs text-slate-400 block">开始时间</span>
            <strong className="text-sm text-slate-700 dark:text-slate-350 mt-0.5 block">{preview.startedAt}</strong>
          </div>
        </div>
      </section>

      {/* Global Event Timeline */}
      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
        <h3 className="text-sm font-bold text-slate-850 dark:text-slate-250 mb-4 flex items-center gap-1.5">
          <History size={16} className="text-indigo-500" /> 任务日志与时间线
        </h3>
        {preview.events.length === 0 ? (
          <div className="text-center py-6 text-slate-400 text-sm">暂无任务日志。</div>
        ) : (
          <div className="space-y-3">
            {preview.events.map((event) => (
              <div key={event.id} className="flex gap-3 text-sm">
                <div className={`mt-1 h-2 w-2 rounded-full shrink-0 ${
                  event.tone === "success" ? "bg-emerald-500" : event.tone === "warning" ? "bg-amber-500" : "bg-blue-500"
                }`} />
                <div className="min-w-0 flex-1 border-b border-slate-100 dark:border-slate-850 pb-2 last:border-b-0">
                  <div className="flex items-center gap-2">
                    <strong className="text-slate-850 dark:text-slate-200 font-bold">{event.title}</strong>
                    <span className="text-xs text-slate-400">{event.time}</span>
                  </div>
                  <p className="text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">{event.description}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function RunTracePanel({
  preview,
  readOnly,
  selectedStepIndex,
  onRollback,
  onOpenDeliveries,
}: {
  preview: RuntimePreview;
  readOnly: boolean;
  selectedStepIndex: number | null;
  onRollback: (nodeRunId: string) => void;
  onOpenDeliveries?: () => void;
}) {
  const steps = preview.steps.filter((s) => s.state !== "pending");
  const fallbackIndex = Math.max(0, lastExecutableStepIndex(steps));
  const selectedIdx = selectedStepIndex !== null ? selectedStepIndex : fallbackIndex;
  
  // Trace panel defaults to hiding step details if selectedStepIndex is null
  const step = selectedStepIndex !== null && preview.steps[selectedIdx] && preview.steps[selectedIdx].state !== "pending"
    ? preview.steps[selectedIdx]
    : null;

  // Filter events: strictly display events related to this step by matching event.nodeId === step.nodeKey
  const events = step
    ? preview.events.filter((event) => event.nodeId === step.nodeKey)
    : [];

  return (
    <div className="max-w-5xl mx-auto space-y-4">
      <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 p-5">
        {step ? (
          <div className="space-y-5">
            <header className="flex justify-between items-center border-b border-slate-100 dark:border-slate-850 pb-3">
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-base font-bold text-slate-800 dark:text-slate-200">{step.title}</h4>
                  <span className={`text-xs px-2.5 py-0.5 rounded-full font-medium ${
                    step.state === "done"
                      ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                      : step.state === "failed"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
                      : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                  }`}>
                    {step.state === "done" ? "已完成" : step.state === "failed" ? "已失败" : step.state === "waiting" ? "等待中" : "执行中"}
                  </span>
                </div>
                <small className="text-xs text-slate-400 mt-1 block">
                  {step.description} · 完成时间：{step.completedAt || "—"}
                </small>
              </div>
              {!readOnly && (step.state === "done" || step.state === "failed") && (
                <button
                  type="button"
                  onClick={() => onRollback(step.nodeRunId)}
                  className="sys-btn sys-btn--danger text-xs px-2.5 py-1 inline-flex items-center gap-1.5"
                >
                  <RotateCcw size={12} />
                  回退到此步骤重新开始
                </button>
              )}
            </header>

            {/* Custom tailored layouts for different step kinds */}
            {step.kind === "input" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350 mb-3">输入项配置与录入结果</h5>
                <div className="space-y-3">
                  {step.inputs?.map((input) => (
                    <CollapsibleField 
                      key={input.label} 
                      label={input.label} 
                      value={input.value || "—"} 
                    />
                  ))}
                </div>
              </div>
            )}

            {step.kind === "agent" && (
              <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
                    <span className="text-xs text-slate-400 block">执行模型</span>
                    <strong className="text-sm text-slate-700 dark:text-slate-300 mt-1 block">
                      {step.outputs?.find((f) => f.label === "modelName")?.value || "GLM-5.1"}
                    </strong>
                  </div>
                  <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
                    <span className="text-xs text-slate-400 block">执行模式</span>
                    <strong className="text-sm text-slate-700 dark:text-slate-300 mt-1 block">ReAct 智能体模式</strong>
                  </div>
                </div>

                {step.capabilities && step.capabilities.length > 0 && (
                  <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                    <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350 mb-3">工具与 MCP 调用记录</h5>
                    <div className="space-y-2">
                      {step.capabilities.map((tool) => (
                        <div 
                          key={tool.id}
                          className="p-3 rounded bg-white dark:bg-slate-950 border border-slate-100 dark:border-slate-855 text-sm flex justify-between items-center"
                        >
                          <div>
                            <span className="text-[10px] font-bold text-slate-400 uppercase">{tool.kind}</span>
                            <strong className="text-sm text-slate-800 dark:text-slate-200 block mt-0.5">{tool.name}</strong>
                          </div>
                          <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                            tool.status === "done" ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400" : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                          }`}>
                            {tool.statusLabel}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                  <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350 mb-2">输出结论</h5>
                  <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg">
                    <MarkdownRenderer content={step.outputs?.find((f) => f.label === "final_answer" || f.label === "agent_response")?.value || "—"} />
                  </div>
                </div>
              </div>
            )}

            {step.kind === "multiAgent" && (
              <div className="space-y-4">
                <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350">智能体集群执行报告</h5>
                <div className="space-y-3">
                  {parseClusterAgentSummariesFromOutputs(step.outputs).map((agent: Record<string, unknown>, idx: number) => (
                    <div key={idx} className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                      <div className="flex items-center justify-between border-b border-slate-100 dark:border-slate-850 pb-2 mb-3">
                        <span className="text-sm font-bold text-slate-800 dark:text-slate-200">
                          {stringifyValue(agent.name) || `子智能体 ${idx + 1}`}
                        </span>
                        <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400 font-medium">已完成</span>
                      </div>
                      <MarkdownRenderer
                        content={clusterAgentDisplayText(agent) || "已完成"}
                      />
                    </div>
                  ))}
                </div>
              </div>
            )}

            {step.kind === "approval" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4 space-y-4">
                <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350">审核结果汇总</h5>
                <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg flex items-start gap-4">
                  <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${
                    step.outputs?.find((f) => f.label === "approved")?.value === "false"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
                      : "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                  }`}>
                    {step.outputs?.find((f) => f.label === "approved")?.value === "false" ? "审核驳回" : "审核通过"}
                  </span>
                  <div className="flex-1">
                    <span className="text-xs text-slate-400 font-semibold block">审核批注</span>
                    <p className="text-sm text-slate-800 dark:text-slate-200 mt-1 italic">
                      "{step.outputs?.find((f) => f.label === "comment")?.value || "无批注内容"}"
                    </p>
                  </div>
                </div>
              </div>
            )}

            {step.kind === "delivery" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4 space-y-4">
                <div className="flex items-center justify-between gap-3">
                  <h5 className="text-sm font-bold text-slate-700 dark:text-slate-350">产品交付结果</h5>
                  {onOpenDeliveries ? (
                    <button
                      type="button"
                      className="sys-btn sys-btn--default text-xs px-2.5 py-1"
                      onClick={onOpenDeliveries}
                    >
                      查看完整产品交付
                    </button>
                  ) : null}
                </div>
                <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg space-y-3">
                  <span className="text-xs text-slate-400 font-semibold block">交付状态</span>
                  <strong className="text-sm text-slate-800 dark:text-slate-200 block font-mono">
                    {step.outputs?.find((f) => f.label === "summary")?.value || "交付文件已生成并归档。"}
                  </strong>
                  {resolveDirectDeliveryContent(step) ? (
                    <div className="pt-2 border-t border-slate-100 dark:border-slate-850">
                      <span className="text-xs text-slate-400 font-semibold block mb-2">交付配置输出</span>
                      <MarkdownRenderer content={resolveDirectDeliveryContent(step)} />
                    </div>
                  ) : null}
                </div>
              </div>
            )}

            {/* Fallback to original layout for custom kinds */}
            {step.kind !== "input" && step.kind !== "agent" && step.kind !== "multiAgent" && step.kind !== "approval" && step.kind !== "delivery" && (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                <SnapshotFieldList title="输入参数" fields={step.inputs || []} monospace />
                <SnapshotFieldList title="输出快照" fields={step.outputs || []} markdown />
              </div>
            )}
          </div>
        ) : (
          <div className="trace-empty-enter flex flex-col items-center justify-center text-center py-16 px-6">
            {/* 浮动图标 + 扩散光环，营造“等待选择步骤”的轻量引导感 */}
            <div className="relative mb-6 flex items-center justify-center">
              <span className="trace-empty-ring absolute h-24 w-24 rounded-full bg-blue-400/15 dark:bg-blue-500/15" />
              <span className="trace-empty-orb relative flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-blue-950/50 dark:to-indigo-950/40 ring-1 ring-blue-100 dark:ring-blue-900/60 shadow-sm">
                <History size={34} className="text-blue-500 dark:text-blue-400" />
              </span>
            </div>
            <h4 className="text-lg font-semibold text-slate-700 dark:text-slate-200">查看步骤历史与快照</h4>
            <p className="mt-2 max-w-md text-sm leading-relaxed text-slate-400 dark:text-slate-500">
              在左侧流程轨中选择一个已执行的步骤，这里会展示该步骤专属的输入输出快照、执行记录和事件线。
            </p>
            <div className="mt-6 flex items-center gap-2 rounded-full bg-slate-50 dark:bg-slate-900 px-3.5 py-1.5 text-xs font-medium text-slate-400 dark:text-slate-500 ring-1 ring-inset ring-slate-100 dark:ring-slate-800">
              <span className="inline-flex h-1.5 w-1.5 rounded-full bg-emerald-400" />
              已完成与失败的步骤均可回看
            </div>
          </div>
        )}
      </section>

      {step && (
        <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 p-5">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h4 className="text-base font-bold text-slate-800 dark:text-slate-200">步骤运行日志</h4>
              <p className="text-xs text-slate-400 mt-1">仅显示与当前选中步骤相关的事件线记录。</p>
            </div>
            <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400">
              {events.length} 条事件
            </span>
          </div>
          {events.length === 0 ? (
            <div className="text-center py-8 text-slate-400 text-sm">当前步骤暂无特定运行事件。</div>
          ) : (
            <div className="space-y-3">
              {events.map((event) => (
                <div key={event.id} className="flex gap-3">
                  <div className={`mt-1 h-2 w-2 rounded-full shrink-0 ${
                    event.tone === "success"
                      ? "bg-emerald-500"
                      : event.tone === "warning"
                      ? "bg-amber-500"
                      : "bg-blue-500"
                  }`} />
                  <div className="min-w-0 flex-1 border-b border-slate-100 dark:border-slate-855 pb-3 last:border-b-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <strong className="text-sm text-slate-800 dark:text-slate-200">{event.title}</strong>
                      <span className="text-xs text-slate-400">{event.time}</span>
                    </div>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">{event.description}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
}

function CollapsibleField({
  label,
  value,
  monospace = false,
  markdown = false,
}: {
  label: string;
  value: string;
  monospace?: boolean;
  markdown?: boolean;
}) {
  const [isExpanded, setIsExpanded] = useState(false);
  const shouldCollapse = value.length > 250;

  return (
    <div className="text-sm min-w-0 relative mb-3 last:mb-0">
      <span className="text-slate-400 block text-xs">{label}</span>
      <div className={`mt-0.5 relative transition-all duration-200 ${shouldCollapse && !isExpanded ? "max-h-24 overflow-hidden" : ""}`}>
        {markdown ? (
          <MarkdownRenderer content={value || "—"} compact className="mt-1" />
        ) : (
          <p className={`text-slate-755 dark:text-slate-355 mt-0.5 break-words ${monospace ? "font-mono" : "font-sans whitespace-pre-wrap"}`}>
            {value || "—"}
          </p>
        )}
        {shouldCollapse && !isExpanded && (
          <div className="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-slate-50 dark:from-slate-900 to-transparent pointer-events-none" />
        )}
      </div>
      {shouldCollapse && (
        <button
          type="button"
          onClick={() => setIsExpanded(!isExpanded)}
          className="text-blue-500 hover:text-blue-600 dark:text-blue-400 mt-1 text-xs font-semibold flex items-center gap-0.5"
        >
          {isExpanded ? "收起内容" : "展开全文"}
        </button>
      )}
    </div>
  );
}

function SnapshotFieldList({
  title,
  fields,
  monospace = false,
  markdown = false,
}: {
  title: string;
  fields: RuntimeNodeField[];
  monospace?: boolean;
  markdown?: boolean;
}) {
  return (
    <div className="space-y-2 min-w-0">
      <span className="text-xs font-bold text-slate-400 block">{title}</span>
      <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-3 space-y-3 min-h-[96px]">
        {fields.length === 0 ? (
          <p className="text-sm text-slate-400">无快照数据。</p>
        ) : (
          fields.map((field) => (
            <CollapsibleField
              key={field.label}
              label={field.label}
              value={field.value}
              monospace={monospace}
              markdown={markdown}
            />
          ))
        )}
      </div>
    </div>
  );
}

function RunDeliveriesPanel({
  preview,
  isFlowCompleted,
}: {
  preview: RuntimePreview;
  isFlowCompleted: boolean;
}) {
  const list = preview.deliveries || [];
  const deliveryStep = preview.steps.find((step) => step.kind === "delivery");
  const deliveryConfig = deliveryStep?.configSnapshot ?? {};
  const deliveryMode = readConfigString(deliveryConfig.deliveryMode, "direct");
  const capabilityId = readConfigString(deliveryConfig.deliveryCapabilityId, "none").toLowerCase();
  const isDirectDelivery =
    deliveryMode === "direct"
    || capabilityId === "none"
    || capabilityId === "custom"
    || capabilityId === "";

  const directDeliveryContent = resolveDirectDeliveryContent(deliveryStep);

  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    if (directDeliveryContent) {
      navigator.clipboard.writeText(directDeliveryContent);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="max-w-3xl mx-auto space-y-5">
      {isFlowCompleted ? (
        <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-emerald-50 dark:bg-emerald-950/40 flex items-center justify-center text-emerald-500">
              <CheckCircle2 size={22} />
            </div>
            <div>
              <h4 className="text-base font-bold text-slate-800 dark:text-slate-200">产品交付成功</h4>
              <p className="text-xs text-slate-400 mt-1">业务流程已顺利执行完毕，交付结果已成功归档并输出。</p>
            </div>
          </div>
          <span className="text-sm px-2.5 py-1 rounded-full font-bold bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400">
            已交付
          </span>
        </div>
      ) : (
        <div className="bg-white dark:bg-slate-950 rounded-xl border border-amber-200 dark:border-amber-900/50 p-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-amber-50 dark:bg-amber-950/40 flex items-center justify-center text-amber-500">
              <Package size={22} />
            </div>
            <div>
              <h4 className="text-base font-bold text-slate-800 dark:text-slate-200">交付进行中</h4>
              <p className="text-xs text-slate-400 mt-1">流程尚未完成，交付结果将在全部步骤执行完毕后展示。</p>
            </div>
          </div>
          <span className="text-sm px-2.5 py-1 rounded-full font-bold bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400">
            待完成
          </span>
        </div>
      )}

      {/* Delivery Channels List */}
      <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 space-y-3">
        <h4 className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
          <Send size={16} className="text-emerald-500" /> 交付通道与方式
        </h4>
        {list.length === 0 ? (
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 flex justify-between items-center text-sm">
            <div>
              <strong className="text-slate-800 dark:text-slate-200 font-medium block">直接输出交付</strong>
              <span className="text-slate-400 block mt-0.5 whitespace-pre-wrap">
                {isFlowCompleted
                  ? "按流程「交付配置」渲染并输出正文。"
                  : "交付步骤执行后需先在「当前处理」确认完成，归档结果才会在此展示。"}
              </span>
            </div>
            <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
              isFlowCompleted
                ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                : "bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400"
            }`}>
              {isFlowCompleted ? "已归档" : "待确认"}
            </span>
          </div>
        ) : (
          <div className="space-y-2">
            {list.map((item, index) => (
              <div 
                key={index} 
                className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 flex justify-between items-center text-sm"
              >
                <div>
                  <strong className="text-slate-800 dark:text-slate-200 font-medium block">{item.name}</strong>
                  <span className="text-slate-400 block mt-0.5">
                    {isFlowCompleted
                      ? item.meta
                      : "交付已生成，待您在「当前处理」点击「确认完成」后归档展示。"}
                  </span>
                </div>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                  isFlowCompleted
                    ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                    : "bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400"
                }`}>
                  {isFlowCompleted ? item.status : "待确认"}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {isDirectDelivery && isFlowCompleted && directDeliveryContent ? (
        <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
          <header className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex justify-between items-center bg-slate-50/50 dark:bg-slate-900/10">
            <h4 className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
              <FileText size={16} className="text-blue-500" /> 直接输出交付（交付配置）
            </h4>
            <button
              type="button"
              onClick={handleCopy}
              className="text-sm font-semibold text-blue-500 hover:text-blue-650 dark:text-blue-400 flex items-center gap-1"
            >
              {copied ? "已复制" : "复制交付内容"}
            </button>
          </header>
          <div className="p-6 overflow-y-auto max-h-[500px] prose dark:prose-invert max-w-none bg-white dark:bg-slate-950">
            <MarkdownRenderer content={directDeliveryContent} />
          </div>
        </div>
      ) : null}
    </div>
  );
}

// ============================================================================
// Runtime Preview mapping helpers
// ============================================================================

function buildRuntimePreviewFromRun(run: WorkbenchRunDetail): RuntimePreview {
  const activeNode = run.currentNodeName ?? run.nodes.find((node: any) => node.state === "waiting" || node.state === "running")?.name ?? "已结束";
  
  const steps = run.nodes
    .filter((node: any) => node.nodeType !== "trigger")
    .map((node: any): RuntimePreviewStep => {
      const state = mapNodeState(node.state);
      return {
        nodeRunId: node.id,
        nodeKey: node.nodeId,
        title: node.name,
        subtitle: node.stateLabel,
        state,
        kind: mapNodeKind(node.nodeType),
        description: nodeDescription(node.nodeType, node.config),
        inputs: resolveStepInputs(node),
        outputs: objectToFields(node.outputs),
        completedAt: state === "done" ? formatTime(run.updatedAt) : undefined,
        chatMessages: nodeMessages(node),
        capabilities: nodeCapabilities(node),
        configSnapshot: node.config,
        allowsFollowUp: node.nodeType === "agent" || node.nodeType === "parallel_group"
          ? readBooleanConfig(node.config?.allowQuestion)
          : false,
        allowsRegenerate: node.nodeType === "agent" || node.nodeType === "parallel_group"
          ? readBooleanConfig(node.config?.allowUserEdit) || node.config?.outputMode === "追问确认"
          : false,
        allowsInterrupt: node.state === "running",
      };
    });

  return {
    runId: run.runNumber,
    statusLabel: run.stateLabel,
    activeNode,
    progress: run.progressPercent,
    startedAt: formatDateTime(run.startedAt),
    ownerName: run.ownerName,
    workflowVersion: run.workflowVersionNumber,
    steps,
    agents: run.nodes
      .filter((node: any) => node.nodeType === "agent" || node.nodeType === "parallel_group")
      .map((node: any) => ({
        name: node.name,
        capability: node.nodeType === "parallel_group" ? "智能体集群节点" : "智能体节点",
        status: node.stateLabel,
        statusTone: node.state === "completed" ? "done" : node.state === "waiting" ? "waiting" : "running",
        output: stringifyValue(node.outputs.summary ?? "等待节点输出"),
        duration: "记录中",
      })),
    events: run.events.map((event: any) => ({
      id: event.id,
      time: formatTime(event.eventTime),
      title: event.title,
      description: event.description,
      tone: event.eventType === "node_failed" ? "warning" : event.eventType === "node_waiting" ? "warning" : event.eventType === "node_completed" || event.eventType === "run_completed" ? "success" : "info",
      stepTitle: run.nodes.find((node: any) => node.nodeId === event.nodeId)?.name ?? "任务",
      nodeId: event.nodeId,
    })),
    deliveries: run.nodes
      .filter((node: any) => node.nodeType === "delivery")
      .map((node: any) => ({
        name: node.name,
        status: node.stateLabel,
        meta: stringifyValue(node.outputs.summary ?? "交付确认后生成"),
      })),
  };
}

function mapNodeState(state: string): RuntimeStepState {
  if (state === "completed") return "done";
  if (state === "waiting") return "waiting";
  if (state === "running") return "running";
  if (state === "failed") return "failed";
  if (state === "canceled") return "canceled";
  return "pending";
}

function mapNodeKind(nodeType: string): RuntimeNodeKind {
  if (nodeType === "trigger") return "launch";
  if (nodeType === "user_input") return "input";
  if (nodeType === "parallel_group") return "multiAgent";
  if (nodeType === "human_review") return "approval";
  if (nodeType === "delivery") return "delivery";
  return "agent";
}

function objectToFields(values: Record<string, unknown>): RuntimeNodeField[] {
  return Object.entries(values ?? {}).map(([label, value]) => ({ label, value: stringifyValue(value) }));
}

type InputFieldConfigShape = {
  id: string;
  label: string;
  variable: string;
  placeholder: string;
  defaultValue?: string;
};

function isInputFieldConfig(value: unknown): value is InputFieldConfigShape {
  return typeof value === "object"
    && value !== null
    && typeof (value as InputFieldConfigShape).label === "string"
    && typeof (value as InputFieldConfigShape).variable === "string";
}

function resolveStepInputs(node: any): RuntimeNodeField[] {
  const configs = node.config?.inputFields;
  // 用户输入提交后写入 outputSnapshot，历史回看需合并 inputs 与 outputs。
  const submittedValues = { ...(node.inputs || {}), ...(node.outputs || {}) };
  if (Array.isArray(configs)) {
    return configs.filter(isInputFieldConfig).map((cfg) => ({
      label: cfg.label,
      value: stringifyValue(submittedValues[cfg.variable] ?? submittedValues[cfg.label] ?? cfg.defaultValue ?? ""),
    }));
  }
  if (node.nodeType === "user_input") {
    return objectToFields(submittedValues);
  }
  return [];
}

function readBooleanConfig(value: unknown): boolean {
  return value === true || value === "true";
}

function readConfigString(value: unknown, fallback = ""): string {
  if (value === null || value === undefined) {
    return fallback;
  }
  const text = String(value).trim();
  return text || fallback;
}

function nodeDescription(nodeType: string, config: any): string {
  if (nodeType === "trigger") return "手动触发启动任务运行。";
  if (nodeType === "user_input") return config?.placeholder || "等待业务人员补充录入字段。";
  if (nodeType === "agent") return config?.summary || "AI 智能体基于上下文模型完成推理。";
  if (nodeType === "parallel_group") return "多个子智能体并发执行获取与汇总结论。";
  if (nodeType === "human_review") return "当前步骤需要人工审核通过后继续。";
  if (nodeType === "delivery") return "生成归档成果文件并进行分发。";
  return "工作流内部自动节点。";
}

function nodeMessages(node: any): RuntimeChatMessage[] {
  const messages: RuntimeChatMessage[] = [];
  const outputs = node.outputs || {};

  if (node.nodeType === "agent" || node.nodeType === "parallel_group") {
    if (Array.isArray(outputs.chatMessages)) {
      outputs.chatMessages.forEach((item: any, index: number) => {
        const role = item?.role === "user" ? "user" : "assistant";
        const content = stringifyValue(item?.content ?? "");
        if (!content) {
          return;
        }
        messages.push({
          id: `${node.id}-chat-${index}`,
          role,
          author: role === "user" ? "我" : node.name,
          content,
        });
      });
    }
    if (messages.length === 0 && node.state !== "failed") {
      const content = outputs.final_answer || outputs.agent_response || outputs.summary || "";
      if (content) {
        messages.push({
          id: node.id + "-msg",
          role: "assistant",
          author: node.name,
          content,
        });
      }
    }
    if (node.state === "failed") {
      const errorMessage = stringifyValue(outputs.errorMessage || outputs.summary || "节点执行失败");
      messages.push({
        id: node.id + "-error",
        role: "assistant",
        author: node.name,
        content: `**执行失败**\n\n${errorMessage}`,
      });
    }
  }
  return messages;
}

function nodeCapabilities(node: any): RuntimeCapabilityItem[] {
  const list: RuntimeCapabilityItem[] = [];
  const outputs = node.outputs || {};
  
  if (node.nodeType === "agent" && outputs.modelName) {
    list.push({
      id: "model-call",
      name: outputs.modelName,
      kind: "agent",
      status: "done",
      statusLabel: "调用完成",
      summary: `调用模型 ${outputs.modelName} 推理。`,
    });
  }
  if (Array.isArray(outputs.toolCalls)) {
    outputs.toolCalls.forEach((tool: any, index: number) => {
      const status = tool.status === "failed" || tool.status === "error" ? "error" : "done";
      list.push({
        id: `${node.id}-tool-${index}`,
        name: stringifyValue(tool.toolName || tool.name || "工具调用"),
        kind: tool.toolType === "skill" ? "skill" : tool.toolType === "agent" ? "agent" : "mcp",
        status,
        statusLabel: status === "error" ? "调用失败" : "调用完成",
        summary: stringifyValue(tool.summary || tool.result || "智能体已完成该工具调用。"),
        resultSummary: stringifyValue(tool.summary || tool.result || ""),
      });
    });
  }
  return list;
}

function resolveStepErrorMessage(step: RuntimePreviewStep): string | null {
  if (step.state !== "failed") {
    return null;
  }
  const errorMessage = step.outputs?.find((field) => field.label === "errorMessage")?.value;
  const errorCode = step.outputs?.find((field) => field.label === "errorCode")?.value;
  return formatRuntimeErrorMessage(errorCode, errorMessage);
}

function resolveActiveStepIndex(steps: RuntimePreviewStep[], run?: WorkbenchRunDetail | null): number {
  if (run?.currentNodeKey) {
    const byKey = steps.findIndex((step) => step.nodeKey === run.currentNodeKey);
    if (byKey >= 0) {
      return byKey;
    }
  }
  if (run?.currentNodeName) {
    const byName = steps.findIndex((step) => step.title === run.currentNodeName);
    if (byName >= 0) {
      return byName;
    }
  }

  const failedIndex = steps.findIndex((step) => step.state === "failed" || step.state === "canceled");
  if (failedIndex >= 0) {
    return failedIndex;
  }
  const activeIndex = steps.findIndex((step) => step.state === "running" || step.state === "waiting");
  if (activeIndex >= 0) {
    return activeIndex;
  }
  const pendingIndex = steps.findIndex((step) => step.state === "pending");
  if (pendingIndex >= 0 && steps.slice(0, pendingIndex).every((step) => step.state === "done")) {
    return pendingIndex;
  }
  return lastExecutableStepIndex(steps);
}

function lastExecutableStepIndex(steps: RuntimePreviewStep[]): number {
  for (let index = steps.length - 1; index >= 0; index--) {
    const state = steps[index].state;
    if (state === "failed" || state === "canceled" || state === "running" || state === "waiting" || state === "done") {
      return index;
    }
  }
  return 0;
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch (_) {
      return "[Object]";
    }
  }
  return String(value);
}

function formatTime(value: string): string {
  return value ? new Date(value).toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" }) : "—";
}

function formatDateTime(value: string): string {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
}
