import React, { useState, useEffect, useMemo, useRef } from "react";
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
import { AgentChatPanel } from "./AgentChatPanel";
import { UserInputPanel } from "./UserInputPanel";
import { MultiAgentPanel } from "./MultiAgentPanel";
import { MarkdownRenderer } from "./MarkdownRenderer";
import { workbenchApi } from "../../services/apiClient";
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
  const [activeRunTab, setActiveRunTab] = useState<RunWorkspaceTab>("current");
  const [selectedTraceStepIndex, setSelectedTraceStepIndex] = useState<number | null>(null);
  const [isAdvancing, setIsAdvancing] = useState(false);
  const [advanceError, setAdvanceError] = useState<string | null>(null);
  const processedStreamEventsRef = useRef(0);
  
  // 1. Establish SSE Connection via useRunStream hook
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
    if (shouldReload) {
      void reloadRunDetail();
    }
  }, [stream.events, tenantId, token, runDetail.id, onReload]);

  useEffect(() => {
    processedStreamEventsRef.current = 0;
    setAdvanceError(null);
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

  const streamEventsRef = useRef(stream.events);
  useEffect(() => {
    streamEventsRef.current = stream.events;
  }, [stream.events]);

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
  const isLiveExecuting =
    stream.isStreaming
    && stream.connectionState === "connected"
    && stream.activeNodeInfo?.nodeRunId === activeStep.nodeRunId;

  // Auto advance for agent / parallel group / delivery steps if paused
  useEffect(() => {
    if (runDetail.readOnly || isAdvancing || isLiveExecuting) {
      return;
    }
    const autoAdvanceKinds = ["agent", "multiAgent", "delivery"];
    if (
      runDetail.state === "paused" &&
      activeStep &&
      autoAdvanceKinds.includes(activeStep.kind) &&
      activeStep.state !== "failed" &&
      activeStep.state !== "done"
    ) {
      void handleAdvanceStep();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runDetail.state, activeStep, isAdvancing, isLiveExecuting, runDetail.readOnly]);

  const clusterAgentsForPanel = useMemo(() => {
    if (stream.clusterAgents.length > 0) {
      return stream.clusterAgents;
    }
    if (activeStep.kind !== "multiAgent") {
      return [];
    }
    const configAgents = Array.isArray(activeStep.configSnapshot?.clusterAgents)
      ? (activeStep.configSnapshot?.clusterAgents as Array<Record<string, unknown>>)
      : [];
    const outputAgents = parseClusterAgentSummaries(activeStep.outputs);

    if (activeStep.state === "done") {
      const completed = outputAgents ?? configAgents;
      return completed.map((agent: Record<string, unknown>, index: number) => ({
        index,
        name: stringifyValue(agent.name || agent.label || `子智能体 ${index + 1}`),
        status: "completed" as const,
        streamingText: "",
        outputSummary: stringifyValue(agent.summary || agent.outputSummary || "已完成"),
        toolCalls: [],
      }));
    }

    const stepRunning =
      activeStep.state === "running"
      || isLiveExecuting;

    return configAgents.map((agent, index) => ({
      index,
      name: stringifyValue(agent.name || `子智能体 ${index + 1}`),
      status: stepRunning ? ("running" as const) : ("pending" as const),
      streamingText: "",
      outputSummary: "",
      toolCalls: [],
    }));
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
    if (step.state === "done") {
      setSelectedTraceStepIndex(index);
      setActiveRunTab("trace");
      return;
    }

    if (step.state === "running" || step.state === "waiting") {
      setSelectedTraceStepIndex(index);
      setActiveRunTab("current");
    }
  }

  // 4. Action Handlers: Advance Step
  async function handleAdvanceStep() {
    if (isAdvancing) {
      return;
    }
    setIsAdvancing(true);
    setAdvanceError(null);
    const eventCountBefore = stream.events.length;
    const progressBefore = runDetail.progressPercent;
    const runStateBefore = runDetail.state;
    const nodeStatesBefore = new Map(runDetail.nodes.map((node) => [node.id, node.state]));
    try {
      await stream.ensureConnected();
      const afterAdvance = await workbenchApi.advanceStep(tenantId, token, runDetail.id);
      setRunDetail(afterAdvance);
      onReload(afterAdvance);
      await waitForAdvanceResult({
        eventCountBefore,
        streamEventsRef,
        reloadRunDetail,
        progressBefore,
        runStateBefore,
        nodeStatesBefore,
      });
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "推进步骤失败";
      console.error("推进步骤失败", error);
      setAdvanceError(message);
      await reloadRunDetail();
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
    } catch (e: any) {
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

  async function handleRegenerateStep() {
    const targetStep = preview.steps[resolveActiveStepIndex(preview.steps, runDetail)];
    if (!targetStep?.nodeRunId) {
      return;
    }

    try {
      const updated = await workbenchApi.rollbackRun(tenantId, token, runDetail.id, targetStep.nodeRunId);
      setRunDetail(updated);
      onReload(updated);
      await stream.ensureConnected();
      const afterAdvance = await workbenchApi.advanceStep(tenantId, token, runDetail.id);
      setRunDetail(afterAdvance);
      onReload(afterAdvance);
    } catch (e: unknown) {
      console.error("重试节点失败", e);
    }
  }

  async function handleRollbackPrevious() {
    const currentIdx = resolveActiveStepIndex(preview.steps, runDetail);
    for (let index = currentIdx - 1; index >= 0; index -= 1) {
      if (preview.steps[index].state === "done") {
        await handleRollback(preview.steps[index].nodeRunId);
        return;
      }
    }
  }

  async function handleRollback(nodeRunId: string) {
    try {
      const updated = await workbenchApi.rollbackRun(tenantId, token, runDetail.id, nodeRunId);
      setRunDetail(updated);
      onReload(updated);
    } catch (e: any) {
      console.error("回退步骤失败", e);
    }
  }

  const runWorkspaceTabs = [
    { key: "overview" as const, label: "任务总览", icon: LayoutDashboard },
    { key: "current" as const, label: "当前处理", icon: Activity },
    { key: "trace" as const, label: "执行历史", icon: FileText },
    { key: "deliveries" as const, label: "产品交付", icon: Package },
  ];

  return (
    <section className="workbench-task-workspace sys-fade-in flex flex-col h-full bg-slate-50 dark:bg-slate-900 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-800" aria-label="任务处理工作区">
      {/* 5a. Topbar bar actions */}
      <header className="workbench-task-topbar flex justify-between items-center px-6 py-4 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950">
        <div className="workbench-task-title space-y-1">
          <div className="workbench-run-kicker flex items-center gap-2 text-[10px] text-slate-400">
            <span>业务工作台 / 任务运行</span>
            {!runDetail.saved && <span className="px-1.5 py-0.5 rounded bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400 font-semibold scale-90">草稿</span>}
            {runDetail.saved && !runDetail.readOnly && <span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-450 font-semibold scale-90">已保存</span>}
            {runDetail.readOnly && <span className="px-1.5 py-0.5 rounded bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400 font-semibold scale-90">只读</span>}
          </div>
          <div className="workbench-run-title-row flex items-center gap-2">
            <h2 className="text-sm font-bold text-slate-800 dark:text-slate-200 leading-none">{runDetail.title}</h2>
          </div>
          <p className="text-[10px] text-slate-500 dark:text-slate-400">
            {runDetail.workflowName} · 任务单号 {preview.runId} · v{preview.workflowVersion} · 当前步骤：<strong>{activeStep.title}</strong>
          </p>
        </div>
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

        <section className="workbench-task-main flex-1 flex flex-col bg-white dark:bg-slate-950 overflow-hidden">
          {/* Tab Navigation */}
          <nav className="workbench-runtime-tabs flex border-b border-slate-100 dark:border-slate-800/80 px-4 bg-slate-50/50 dark:bg-slate-900/10" aria-label="任务处理页签">
            {runWorkspaceTabs.map((tab) => {
              const Icon = tab.icon;
              const isActive = activeRunTab === tab.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  className={`flex items-center gap-1.5 px-4 py-3 border-b-2 text-xs font-semibold transition-all ${
                    isActive 
                      ? "border-blue-500 text-blue-600 dark:text-blue-450" 
                      : "border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200"
                  }`}
                  onClick={() => handleTabChange(tab.key)}
                >
                  <Icon size={14} />
                  {tab.label}
                </button>
              );
            })}
          </nav>

          {/* Panel Container Scroll */}
          <div className="flex-1 overflow-y-auto p-6 min-h-0">
            {activeRunTab === "overview" && (
              <RunOverviewPanel run={runDetail} preview={preview} />
            )}

            {activeRunTab === "current" && (
              <div className="space-y-4 max-w-4xl mx-auto">
                <header className="flex justify-between items-center border-b border-slate-100 dark:border-slate-850 pb-3 mb-2">
                  <div>
                    <h3 className="text-xs font-bold text-slate-800 dark:text-slate-200">当前节点：{activeStep.title}</h3>
                    <p className="text-[10px] text-slate-400 dark:text-slate-500 mt-1">{activeStep.description}</p>
                    {runDetail.state === "paused" && !isLiveExecuting && !isAdvancing ? (
                      <p className="text-[10px] text-amber-600 dark:text-amber-400 mt-1">
                        任务已暂停，停在「{runDetail.currentNodeName ?? activeStep.title}」。点击底部按钮继续执行。
                      </p>
                    ) : null}
                    {advanceError ? (
                      <p className="text-[10px] text-rose-600 dark:text-rose-400 mt-1">{advanceError}</p>
                    ) : null}
                  </div>
                  <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                    isAdvancing
                      ? "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                      : activeStep.state === "waiting"
                      ? "bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400"
                      : activeStep.state === "failed"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
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
                  <AgentChatPanel
                    activeStep={activeStep}
                    streamingText={stream.streamingText}
                    isStreaming={isLiveExecuting}
                    currentPhase={stream.currentPhase}
                    toolCalls={stream.toolCalls}
                  />
                ) : activeStep.kind === "multiAgent" ? (
                  <MultiAgentPanel
                    activeStep={activeStep}
                    clusterAgents={clusterAgentsForPanel}
                    isStreaming={isLiveExecuting}
                  />
                ) : activeStep.kind === "approval" ? (
                  <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-850 p-5 space-y-4 max-w-2xl mx-auto">
                    <div className="text-center py-8 text-slate-400 text-xs">
                      <FileCheck size={28} className="mx-auto mb-2 text-amber-500" />
                      当前节点正等待人工审核。请点击下方操作按钮决定批准或驳回。
                    </div>
                  </div>
                ) : activeStep.kind === "delivery" ? (
                  <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-100 dark:border-slate-850 p-5 space-y-4 max-w-2xl mx-auto">
                    <div className="text-center py-8 text-slate-400 text-xs">
                      <Package size={28} className={`mx-auto mb-2 ${activeStep.state === "failed" ? "text-rose-500" : "text-emerald-500"}`} />
                      {activeStep.state === "failed"
                        ? (
                          <>
                            <p className="text-rose-600 dark:text-rose-400 font-medium mb-2">交付执行失败</p>
                            <p>{resolveStepErrorMessage(activeStep) ?? "请检查交付能力配置，或改用「直接输出交付」模式。"}</p>
                          </>
                        )
                        : activeStep.state === "done"
                          ? "交付步骤已完成，可在「生成报告」页签查看归档结果。"
                          : isAdvancing
                            ? "正在执行交付步骤，请稍候..."
                            : "当前为系统交付步骤，请点击下方「执行此步骤」生成并归档交付物。"}
                    </div>
                  </div>
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
                saved={runDetail.saved}
                selectedStepIndex={selectedTraceStepIndex}
                onRollback={handleRollback}
              />
            )}

            {activeRunTab === "deliveries" && (
              <RunDeliveriesPanel preview={preview} />
            )}
          </div>

          {/* 5c. Action Controller bar */}
          {activeRunTab === "current" && (
            <StepActionBar
              activeStep={activeStep}
              isStreaming={isLiveExecuting}
              isAdvancing={isAdvancing}
              isRunCompleted={preview.statusLabel === "已完成" || runDetail.state === "completed"}
              isRunFailed={runDetail.state === "failed" || activeStep.state === "failed"}
              isRunSaved={runDetail.saved}
              readOnly={runDetail.readOnly}
              onAdvance={handleAdvanceStep}
              onCompleteTodo={(comment) => handleCompleteTodo({ comment })}
              onApprove={handleApprove}
              onReject={handleReject}
              onRetry={handleRegenerateStep}
              onRollback={handleRollbackPrevious}
              onBack={onBack}
              onInterrupt={() => stream.disconnect()}
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
        <h3 className="text-xs font-semibold text-slate-805 dark:text-slate-250 mb-2 flex items-center gap-1.5">
          <LayoutDashboard size={16} className="text-blue-500" /> 任务概览
        </h3>
        <p className="text-[10px] text-slate-400 mb-4">
          任务运行详情来自后端运行实例，节点状态、输入输出和事件链路均按发布版本快照生成。
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-[10px] text-slate-400 block">运行编号</span>
            <strong className="text-xs text-slate-700 dark:text-slate-350 font-mono mt-0.5 block">{preview.runId}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-[10px] text-slate-400 block">流程版本</span>
            <strong className="text-xs text-slate-700 dark:text-slate-350 font-mono mt-0.5 block">v{preview.workflowVersion}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-[10px] text-slate-400 block">发起人</span>
            <strong className="text-xs text-slate-700 dark:text-slate-350 mt-0.5 block">{preview.ownerName}</strong>
          </div>
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
            <span className="text-[10px] text-slate-400 block">开始时间</span>
            <strong className="text-xs text-slate-700 dark:text-slate-350 mt-0.5 block">{preview.startedAt}</strong>
          </div>
        </div>
      </section>

      {/* Global Event Timeline */}
      <section className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
        <h3 className="text-xs font-semibold text-slate-850 dark:text-slate-250 mb-4 flex items-center gap-1.5">
          <History size={16} className="text-indigo-500" /> 任务日志与时间线
        </h3>
        {preview.events.length === 0 ? (
          <div className="text-center py-6 text-slate-400 text-xs">暂无任务日志。</div>
        ) : (
          <div className="space-y-3">
            {preview.events.map((event) => (
              <div key={event.id} className="flex gap-3 text-xs">
                <div className={`mt-1 h-2 w-2 rounded-full shrink-0 ${
                  event.tone === "success" ? "bg-emerald-500" : event.tone === "warning" ? "bg-amber-500" : "bg-blue-500"
                }`} />
                <div className="min-w-0 flex-1 border-b border-slate-100 dark:border-slate-850 pb-2 last:border-b-0">
                  <div className="flex items-center gap-2">
                    <strong className="text-slate-850 dark:text-slate-200 font-bold">{event.title}</strong>
                    <span className="text-[10px] text-slate-400">{event.time}</span>
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
  saved,
  selectedStepIndex,
  onRollback,
}: {
  preview: RuntimePreview;
  readOnly: boolean;
  saved: boolean;
  selectedStepIndex: number | null;
  onRollback: (nodeRunId: string) => void;
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
                  <h4 className="text-sm font-bold text-slate-800 dark:text-slate-200">{step.title}</h4>
                  <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                    step.state === "done"
                      ? "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                      : step.state === "failed"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
                      : "bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
                  }`}>
                    {step.state === "done" ? "已完成" : step.state === "failed" ? "已失败" : step.state === "waiting" ? "等待中" : "执行中"}
                  </span>
                </div>
                <small className="text-[10px] text-slate-400 mt-1 block">
                  {step.description} · 完成时间：{step.completedAt || "—"}
                </small>
              </div>
              {!readOnly && saved && (step.state === "done" || step.state === "failed") && (
                <button
                  type="button"
                  onClick={() => onRollback(step.nodeRunId)}
                  className="sys-btn sys-btn--danger text-[10px] px-2.5 py-1 inline-flex items-center gap-1.5"
                >
                  <RotateCcw size={12} />
                  回退到此步骤重新开始
                </button>
              )}
            </header>

            {/* Custom tailored layouts for different step kinds */}
            {step.kind === "input" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350 mb-3">输入项配置与录入结果</h5>
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
                    <span className="text-[10px] text-slate-400 block">执行模型</span>
                    <strong className="text-xs text-slate-700 dark:text-slate-300 mt-1 block">
                      {step.outputs?.find((f) => f.label === "modelName")?.value || "GLM-5.1"}
                    </strong>
                  </div>
                  <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800">
                    <span className="text-[10px] text-slate-400 block">执行模式</span>
                    <strong className="text-xs text-slate-700 dark:text-slate-300 mt-1 block">ReAct 智能体模式</strong>
                  </div>
                </div>

                {step.capabilities && step.capabilities.length > 0 && (
                  <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                    <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350 mb-3">工具与 MCP 调用记录</h5>
                    <div className="space-y-2">
                      {step.capabilities.map((tool) => (
                        <div 
                          key={tool.id}
                          className="p-3 rounded bg-white dark:bg-slate-950 border border-slate-100 dark:border-slate-850 text-xs flex justify-between items-center"
                        >
                          <div>
                            <span className="text-[9px] font-bold text-slate-400 uppercase">{tool.kind}</span>
                            <strong className="text-xs text-slate-800 dark:text-slate-200 block mt-0.5">{tool.name}</strong>
                          </div>
                          <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
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
                  <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350 mb-2">输出结论</h5>
                  <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg">
                    <MarkdownRenderer content={step.outputs?.find((f) => f.label === "final_answer" || f.label === "agent_response")?.value || "—"} />
                  </div>
                </div>
              </div>
            )}

            {step.kind === "multiAgent" && (
              <div className="space-y-4">
                <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350">智能体集群执行报告</h5>
                <div className="space-y-3">
                  {parseClusterAgentSummaries(step.outputs)?.map((agent: any, idx: number) => (
                    <div key={idx} className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4">
                      <div className="flex items-center justify-between border-b border-slate-100 dark:border-slate-850 pb-2 mb-3">
                        <span className="text-xs font-bold text-slate-800 dark:text-slate-200">{agent.name || `子智能体 ${idx + 1}`}</span>
                        <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400 font-medium">已完成</span>
                      </div>
                      <MarkdownRenderer content={agent.summary || agent.outputSummary || "已完成"} compact />
                    </div>
                  ))}
                </div>
              </div>
            )}

            {step.kind === "approval" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4 space-y-4">
                <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350">审核结果汇总</h5>
                <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg flex items-start gap-4">
                  <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${
                    step.outputs?.find((f) => f.label === "approved")?.value === "false"
                      ? "bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-400"
                      : "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400"
                  }`}>
                    {step.outputs?.find((f) => f.label === "approved")?.value === "false" ? "审核驳回" : "审核通过"}
                  </span>
                  <div className="flex-1">
                    <span className="text-[10px] text-slate-400 font-semibold block">审核批注</span>
                    <p className="text-xs text-slate-800 dark:text-slate-200 mt-1 italic">
                      "{step.outputs?.find((f) => f.label === "comment")?.value || "无批注内容"}"
                    </p>
                  </div>
                </div>
              </div>
            )}

            {step.kind === "delivery" && (
              <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-4 space-y-4">
                <h5 className="text-xs font-bold text-slate-700 dark:text-slate-350">产品交付结果</h5>
                <div className="bg-white dark:bg-slate-950 border border-slate-150 dark:border-slate-850 p-4 rounded-lg">
                  <span className="text-[10px] text-slate-400 font-semibold block">交付状态</span>
                  <strong className="text-xs text-slate-800 dark:text-slate-200 mt-1 block font-mono">
                    {step.outputs?.find((f) => f.label === "summary")?.value || "交付文件已生成并归档。"}
                  </strong>
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
          <div className="text-center py-12 text-slate-400 text-xs">
            左侧流程轨选择已执行步骤后，可查看该步骤专属的历史数据和快照。
          </div>
        )}
      </section>

      {step && (
        <section className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 p-5">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h4 className="text-sm font-bold text-slate-800 dark:text-slate-200">步骤运行日志</h4>
              <p className="text-[10px] text-slate-400 mt-1">仅显示与当前选中步骤相关的事件线记录。</p>
            </div>
            <span className="text-[10px] px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400">
              {events.length} 条事件
            </span>
          </div>
          {events.length === 0 ? (
            <div className="text-center py-8 text-slate-400 text-xs">当前步骤暂无特定运行事件。</div>
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
                  <div className="min-w-0 flex-1 border-b border-slate-100 dark:border-slate-850 pb-3 last:border-b-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <strong className="text-xs text-slate-800 dark:text-slate-200">{event.title}</strong>
                      <span className="text-[10px] text-slate-400">{event.time}</span>
                    </div>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">{event.description}</p>
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
    <div className="text-xs min-w-0 relative mb-3 last:mb-0">
      <span className="text-slate-400 block text-[10px]">{label}</span>
      <div className={`mt-0.5 relative transition-all duration-200 ${shouldCollapse && !isExpanded ? "max-h-24 overflow-hidden" : ""}`}>
        {markdown ? (
          <MarkdownRenderer content={value || "—"} compact className="mt-1" />
        ) : (
          <p className={`text-slate-755 dark:text-slate-350 mt-0.5 break-words ${monospace ? "font-mono" : "font-sans whitespace-pre-wrap"}`}>
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
          className="text-blue-500 hover:text-blue-600 dark:text-blue-400 mt-1 text-[10px] font-semibold flex items-center gap-0.5"
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
      <span className="text-[10px] font-bold text-slate-400 block">{title}</span>
      <div className="bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 p-3 space-y-3 min-h-[96px]">
        {fields.length === 0 ? (
          <p className="text-xs text-slate-400">无快照数据。</p>
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

function RunDeliveriesPanel({ preview }: { preview: RuntimePreview }) {
  const list = preview.deliveries || [];
  
  // Find final output markdown to display for "直接输出交付" (Direct Delivery)
  const finalAgentStep = preview.steps.find((s) => (s.kind === "agent" || s.kind === "multiAgent") && s.state === "done");
  const finalAnswer = finalAgentStep?.outputs?.find((f) => f.label === "final_answer" || f.label === "agent_response")?.value;

  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    if (finalAnswer) {
      navigator.clipboard.writeText(finalAnswer);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="max-w-3xl mx-auto space-y-5">
      {/* Top Status Card */}
      <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-emerald-50 dark:bg-emerald-950/40 flex items-center justify-center text-emerald-500">
            <CheckCircle2 size={22} />
          </div>
          <div>
            <h4 className="text-sm font-bold text-slate-800 dark:text-slate-200">产品交付成功</h4>
            <p className="text-[10px] text-slate-400 mt-1">业务流程已顺利执行完毕，交付结果已成功归档并输出。</p>
          </div>
        </div>
        <span className="text-xs px-2.5 py-1 rounded-full font-bold bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400">
          已交付
        </span>
      </div>

      {/* Delivery Channels List */}
      <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 p-5 space-y-3">
        <h4 className="text-xs font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
          <Send size={16} className="text-emerald-500" /> 交付通道与方式
        </h4>
        {list.length === 0 ? (
          <div className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 flex justify-between items-center text-xs">
            <div>
              <strong className="text-slate-800 dark:text-slate-200 font-medium block">直接输出交付</strong>
              <span className="text-slate-400 block mt-0.5">直接在平台内展示智能体推理结论。</span>
            </div>
            <span className="text-[10px] px-2 py-0.5 rounded-full font-medium bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400">
              就绪
            </span>
          </div>
        ) : (
          <div className="space-y-2">
            {list.map((item, index) => (
              <div 
                key={index} 
                className="p-3 bg-slate-50 dark:bg-slate-900 rounded-lg border border-slate-100 dark:border-slate-800 flex justify-between items-center text-xs"
              >
                <div>
                  <strong className="text-slate-800 dark:text-slate-200 font-medium block">{item.name}</strong>
                  <span className="text-slate-400 block mt-0.5">{item.meta}</span>
                </div>
                <span className="text-[10px] px-2 py-0.5 rounded-full font-medium bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-400">
                  {item.status}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Premium Document Output Viewer */}
      {finalAnswer && (
        <div className="bg-white dark:bg-slate-950 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
          <header className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex justify-between items-center bg-slate-50/50 dark:bg-slate-900/10">
            <h4 className="text-xs font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
              <FileText size={16} className="text-blue-500" /> 直接输出交付结论 (Markdown)
            </h4>
            <button
              type="button"
              onClick={handleCopy}
              className="text-xs font-semibold text-blue-500 hover:text-blue-650 dark:text-blue-400 flex items-center gap-1"
            >
              {copied ? "已复制" : "复制报告内容"}
            </button>
          </header>
          <div className="p-6 overflow-y-auto max-h-[500px] prose dark:prose-invert max-w-none bg-white dark:bg-slate-950">
            <MarkdownRenderer content={finalAnswer} />
          </div>
        </div>
      )}
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
        allowsFollowUp: node.nodeType === "agent" || node.nodeType === "parallel_group",
        allowsRegenerate: node.nodeType === "agent" || node.nodeType === "parallel_group",
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
  const outputs = node.inputs || {};
  if (Array.isArray(configs)) {
    return configs.filter(isInputFieldConfig).map((cfg) => ({
      label: cfg.label,
      value: stringifyValue(outputs[cfg.variable] ?? outputs[cfg.label] ?? cfg.defaultValue ?? ""),
    }));
  }
  return [];
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
    const content = outputs.final_answer || outputs.agent_response || outputs.summary || "";
    if (content && node.state !== "failed") {
      messages.push({
        id: node.id + "-msg",
        role: "assistant",
        author: node.name,
        content,
      });
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

function parseClusterAgentSummaries(outputs: RuntimePreviewStep["outputs"]): Array<Record<string, unknown>> | null {
  const field = outputs?.find((item) => item.label === "clusterAgents");
  if (!field?.value) {
    return null;
  }
  try {
    const parsed = JSON.parse(field.value);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function resolveStepErrorMessage(step: RuntimePreviewStep): string | null {
  if (step.state !== "failed") {
    return null;
  }
  const errorMessage = step.outputs?.find((field) => field.label === "errorMessage")?.value;
  if (errorMessage) {
    return errorMessage;
  }
  const errorCode = step.outputs?.find((field) => field.label === "errorCode")?.value;
  return errorCode || null;
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

  const failedIndex = steps.findIndex((step) => step.state === "failed");
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

interface WaitForAdvanceResultOptions {
  eventCountBefore: number;
  streamEventsRef: React.MutableRefObject<StreamEvent[]>;
  reloadRunDetail: () => Promise<WorkbenchRunDetail | null>;
  progressBefore: number;
  runStateBefore: WorkbenchRunDetail["state"];
  nodeStatesBefore: Map<string, string>;
}

async function waitForAdvanceResult(options: WaitForAdvanceResultOptions) {
  const {
    eventCountBefore,
    streamEventsRef,
    reloadRunDetail,
    progressBefore,
    runStateBefore,
    nodeStatesBefore,
  } = options;
  const terminalEvents = new Set(["node_completed", "run_paused", "run_completed", "node_failed"]);

  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (attempt > 0) {
      await sleep(2000);
    }

    const newEvents = streamEventsRef.current.slice(eventCountBefore);
    if (newEvents.some((event) => terminalEvents.has(event.type))) {
      await reloadRunDetail();
      return;
    }

    const updated = await reloadRunDetail();
    if (!updated) {
      continue;
    }
    if (updated.state === "completed" || updated.state === "failed") {
      return;
    }
    if (updated.progressPercent > progressBefore || updated.state !== runStateBefore) {
      return;
    }
    const nodeProgressed = updated.nodes.some((node) => {
      const before = nodeStatesBefore.get(node.id);
      return before !== node.state
        && (node.state === "completed" || node.state === "failed" || node.state === "waiting");
    });
    if (nodeProgressed) {
      return;
    }
  }
}

function sleep(ms: number) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

function lastExecutableStepIndex(steps: RuntimePreviewStep[]): number {
  for (let index = steps.length - 1; index >= 0; index--) {
    if (steps[index].state === "failed" || steps[index].state === "running" || steps[index].state === "waiting" || steps[index].state === "done") {
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
