import { useEffect, useState } from "react";
import { Drawer, Spin, Tabs, Tag, Empty, Collapse } from "antd";
import type { TabsProps } from "antd";
import {
  Clock, User, ClipboardList, Activity, Sparkles, Cpu,
  Send, AlertCircle, Code2, Lock, EyeOff, CheckCircle2,
  Timer, FileText, Sigma
} from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditEvidence } from "../../types/audit";

interface RunEvidenceDrawerProps {
  runId: string | null;
  onClose: () => void;
}

/**
 * 运行全链路审计证据链抽屉
 *
 * 布局：采用 sys-drawer-section 统一滚动模型，所有内容在一个可滚动区域内自然排列。
 * - 顶部 hero 概要卡片（参考 workflow-detail-drawer-hero 风格）
 * - 中间左右分栏：左侧节点步骤轨道 + 右侧节点详情面板
 * - 底部全景辅助：时间线 + 全局变量快照
 */
export function RunEvidenceDrawer({ runId, onClose }: RunEvidenceDrawerProps) {
  const token = useAuthStore((s) => s.token) || "";
  const activeRole = useAuthStore((s) => s.activeRole);
  const user = useAuthStore((s) => s.user);
  const tenantId = activeRole?.tenantId || user?.tenantId || "";
  const themeMode = useAuthStore((s) => s.themeMode);

  const [loading, setLoading] = useState(false);
  const [evidence, setEvidence] = useState<AuditEvidence | null>(null);
  const [activeNodeKey, setActiveNodeKey] = useState<string | null>(null);

  useEffect(() => {
    if (!runId || !tenantId) {
      setEvidence(null);
      setActiveNodeKey(null);
      return;
    }

    const loadEvidence = async () => {
      setLoading(true);
      try {
        const res = await auditApi.getEvidence(tenantId, runId, token);
        if (res) {
          setEvidence(res);
          if (res.nodeRuns && res.nodeRuns.length > 0) {
            setActiveNodeKey(res.nodeRuns[0].nodeKey);
          }
        }
      } catch (e) {
        console.error(e);
      } finally {
        setLoading(false);
      }
    };

    loadEvidence();
  }, [runId, tenantId, token]);

  const activeNode = evidence?.nodeRuns.find((n) => n.nodeKey === activeNodeKey);

  // 过滤出当前节点的审计事件与日志
  const activeNodeVariables = evidence?.variableSnapshots.filter((v) => v.sourceNodeKey === activeNodeKey) || [];
  const activeNodeModelCalls = evidence?.modelCallLogs.filter((m) => m.nodeRunId === activeNode?.id) || [];
  const activeNodeMcpCalls = evidence?.mcpCallLogs.filter((m) => m.nodeRunId === activeNode?.id) || [];
  const activeNodeDeliveries = evidence?.deliveryRecords.filter((d) => d.nodeRunId === activeNode?.id) || [];

  const formatState = (s: string) => {
    switch (s) {
      case "running":
        return (
          <span className="sys-status sys-status--active">
            <span className="sys-status-dot" />
            执行中
          </span>
        );
      case "paused":
        return (
          <span className="sys-status sys-status--paused">
            <span className="sys-status-dot" />
            已暂停
          </span>
        );
      case "completed":
        return (
          <span className="sys-status sys-status--success">
            <span className="sys-status-dot" />
            已完成
          </span>
        );
      case "failed":
        return (
          <span className="sys-status sys-status--inactive">
            <span className="sys-status-dot" />
            已失败
          </span>
        );
      case "canceled":
        return (
          <span className="sys-status sys-status--inactive">
            <span className="sys-status-dot" />
            已取消
          </span>
        );
      default:
        return (
          <span className="sys-status sys-status--inactive">
            <span className="sys-status-dot" />
            {s}
          </span>
        );
    }
  };

  const formatDate = (isoStr: string | null) => {
    if (!isoStr) return "—";
    return new Date(isoStr).toLocaleString("zh-CN", { hour12: false });
  };

  const formatJson = (val: any) => {
    if (typeof val === "string") {
      try {
        const parsed = JSON.parse(val);
        return JSON.stringify(parsed, null, 2);
      } catch {
        return val;
      }
    }
    return JSON.stringify(val, null, 2);
  };

  // 渲染左侧步骤轨的图标
  const getNodeIcon = (type: string) => {
    switch (type) {
      case "trigger": return <Activity size={13} />;
      case "input": return <ClipboardList size={13} />;
      case "agent": return <Sparkles size={13} />;
      case "cluster": return <Sparkles size={13} />;
      case "delivery": return <Send size={13} />;
      default: return <Code2 size={13} />;
    }
  };

  // 计算节点耗时的辅助函数
  const computeDuration = (startedAt: string | null, completedAt: string | null) => {
    if (!startedAt || !completedAt) return "—";
    const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
    return `${ms} ms`;
  };

  // 构建右侧节点详情的 Tab items（替代废弃的 Tabs.TabPane）
  const buildNodeTabItems = (): TabsProps["items"] => {
    return [
      {
        key: "variables",
        label: "数据变量快照",
        children: (
          <div className="run-evidence-tab-content pt-3">
            {activeNodeVariables.length === 0 ? (
              <Empty description="该节点无变量输出" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeVariables.map((v) => (
                <div key={v.id} className="run-evidence-var-card p-4 mb-3 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                  <div className="run-evidence-var-header flex items-center justify-between pb-2 mb-2 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                    <span className="run-evidence-var-name font-mono text-sm font-semibold text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                      {v.variableName}
                      {v.sensitive && (
                        <Tag color="red" icon={<Lock size={10} />} className="flex items-center gap-1 px-1.5 py-0">敏感</Tag>
                      )}
                    </span>
                    <span className="run-evidence-var-type text-xs text-zinc-400 bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5 rounded">类型: {v.valueType}</span>
                  </div>
                  <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">
                    {v.sensitive ? (
                      <span className="run-evidence-sensitive text-red-500 font-medium italic flex items-center gap-1.5">
                        <EyeOff size={12} /> ****** (敏感信息，审计日志已自动遮蔽)
                      </span>
                    ) : (
                      formatJson(v.value)
                    )}
                  </pre>
                </div>
              ))
            )}
          </div>
        ),
      },
      {
        key: "models",
        label: `模型调用 (${activeNodeModelCalls.length})`,
        children: (
          <div className="run-evidence-tab-content pt-3">
            {activeNodeModelCalls.length === 0 ? (
              <Empty description="该节点无大模型调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeModelCalls.map((m) => (
                <div key={m.id} className="run-evidence-call-card p-4 mb-4 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                  <div className="run-evidence-call-header flex items-center justify-between pb-2 mb-3 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                    <span className="run-evidence-call-title font-semibold text-sm text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                      <Sparkles size={13} className="text-yellow-500" />
                      使用模型: {m.modelName}
                    </span>
                    <span className="run-evidence-call-meta text-xs text-zinc-500 flex items-center gap-1">
                      <Timer size={11} className="inline mr-1 opacity-50" />
                      {m.latencyMs ? `${m.latencyMs} ms` : "—"}
                    </span>
                  </div>
                  <div className="mb-3 flex flex-wrap gap-2 text-xs text-zinc-500">
                    <span className="rounded-md bg-violet-50 px-2 py-1 text-violet-700 dark:bg-violet-950/40 dark:text-violet-300">总计 {m.tokenUsage.totalTokens.toLocaleString("zh-CN")}</span>
                    <span className="rounded-md bg-zinc-100 px-2 py-1 dark:bg-zinc-800">输入 {m.tokenUsage.inputTokens.toLocaleString("zh-CN")}</span>
                    <span className="rounded-md bg-zinc-100 px-2 py-1 dark:bg-zinc-800">输出 {m.tokenUsage.outputTokens.toLocaleString("zh-CN")}</span>
                  </div>
                  <div>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">提示词快照 (Prompt Snapshot)</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all mb-3">{formatJson(m.promptSnapshot)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">模型输出结果 (Response Snapshot)</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">{formatJson(m.responseSnapshot)}</pre>
                  </div>
                </div>
              ))
            )}
          </div>
        ),
      },
      {
        key: "mcp",
        label: `MCP工具/Skill (${activeNodeMcpCalls.length})`,
        children: (
          <div className="run-evidence-tab-content pt-3">
            {activeNodeMcpCalls.length === 0 ? (
              <Empty description="该节点无外部工具 (MCP) 调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeMcpCalls.map((m) => (
                <div key={m.id} className="run-evidence-call-card p-4 mb-4 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                  <div className="run-evidence-call-header flex items-center justify-between pb-2 mb-3 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                    <span className="run-evidence-call-title font-semibold text-sm text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                      <Cpu size={13} className="text-blue-500" />
                      调用能力: {m.capabilityCode} · 工具: {m.toolName}
                    </span>
                    <span className="run-evidence-call-meta text-xs text-zinc-500 flex items-center gap-1">
                      {m.status === "success" ? <span className="text-green-500 font-semibold">成功</span> : <span className="text-red-500 font-semibold">失败</span>}
                      {m.latencyMs ? ` · ${m.latencyMs} ms` : ""}
                    </span>
                  </div>
                  <div>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">入参载荷 (Request Arguments)</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all mb-3">{formatJson(m.requestPayload)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">观察结果 (Response Outcome)</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">{formatJson(m.responsePayload)}</pre>
                  </div>
                </div>
              ))
            )}
          </div>
        ),
      },
      {
        key: "deliveries",
        label: `交付推送 (${activeNodeDeliveries.length})`,
        children: (
          <div className="run-evidence-tab-content pt-3">
            {activeNodeDeliveries.length === 0 ? (
              <Empty description="该节点无推送交付记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeDeliveries.map((d) => (
                <div key={d.id} className="run-evidence-call-card p-4 mb-4 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                  <div className="run-evidence-call-header flex items-center justify-between pb-2 mb-3 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                    <span className="run-evidence-call-title font-semibold text-sm text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                      <Send size={13} className="text-[#8b5cf6]" />
                      方式: {d.deliveryType} · 标题: {d.title}
                    </span>
                    <span>
                      {d.status === "success" ? <Tag color="success">交付成功</Tag> : <Tag color="error">交付失败</Tag>}
                    </span>
                  </div>
                  <div className="run-evidence-delivery-meta grid grid-cols-2 gap-2 text-xs text-zinc-500 bg-zinc-100/80 dark:bg-zinc-800/80 p-2.5 rounded-lg border border-zinc-200 dark:border-zinc-700 mb-3">
                    <div>目标地址/Key: <span className="font-mono font-medium text-zinc-800 dark:text-zinc-200">{d.target || "—"}</span></div>
                    <div>交付时间: <span className="font-mono font-medium text-zinc-800 dark:text-zinc-200">{formatDate(d.createdAt)}</span></div>
                  </div>
                  <div>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">交付载荷</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all mb-3">{formatJson(d.payload)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label text-xs font-bold tracking-wider text-zinc-400 mb-1.5 uppercase">交付结果快照</p>
                    <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">{formatJson(d.resultSnapshot)}</pre>
                  </div>
                  {d.errorMessage && (
                    <div className="run-evidence-error flex items-start gap-2.5 p-3 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900/50 rounded-xl text-xs text-red-500 mt-3 shadow-xs">
                      <AlertCircle size={14} className="mt-0.5 shrink-0" />
                      <div>
                        <div className="run-evidence-error-title font-bold mb-1">失败原因</div>
                        <div>{d.errorMessage}</div>
                      </div>
                    </div>
                  )}
                </div>
              ))
            )}
          </div>
        ),
      },
    ];
  };

  // 构建底部全景辅助的 Tab items
  const buildFooterTabItems = (): TabsProps["items"] => {
    if (!evidence) return [];
    return [
      {
        key: "timeline",
        label: (
          <span className="flex items-center gap-1.5">
            <Activity size={13} />
            工作流轨迹事件时间线
          </span>
        ),
        children: (
          <div className="run-evidence-tab-content">
            {evidence.runEvents.length === 0 ? (
              <Empty description="无事件记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <div className="run-evidence-timeline">
                {evidence.runEvents.map((evt) => (
                  <div key={evt.id} className="run-evidence-timeline-event">
                    <span className="run-evidence-timeline-dot" />
                    <div className="run-evidence-timeline-event-header">
                      <span className="run-evidence-timeline-event-title">{evt.title}</span>
                      <span className="run-evidence-timeline-event-time">{formatDate(evt.eventTime)}</span>
                    </div>
                    <div className="run-evidence-timeline-event-desc">
                      {evt.description} {evt.operatorName ? `(操作人: ${evt.operatorName})` : ""}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ),
      },
      {
        key: "all_vars",
        label: (
          <span className="flex items-center gap-1.5">
            <FileText size={13} />
            流程全局变量最终快照
          </span>
        ),
        children: (
          <div className="run-evidence-tab-content">
            {evidence.variableSnapshots.length === 0 ? (
              <Empty description="无变量快照" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              evidence.variableSnapshots.map((v) => (
                <div key={v.id} className="run-evidence-var-card p-4 mb-3 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                  <div className="run-evidence-var-header flex items-center justify-between pb-2 mb-2 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                    <span className="run-evidence-var-name font-mono text-sm font-semibold text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                      {v.variableName}
                      {v.sensitive && <Lock size={10} className="text-red-400" />}
                    </span>
                    <span className="run-evidence-var-type text-xs text-zinc-400 bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5 rounded">类型: {v.valueType}</span>
                  </div>
                  <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">
                    {v.sensitive ? (
                      <span className="run-evidence-sensitive text-red-500 font-medium italic flex items-center gap-1.5">
                        <EyeOff size={12} /> ****** (敏感信息，审计日志已自动遮蔽)
                      </span>
                    ) : (
                      formatJson(v.value)
                    )}
                  </pre>
                </div>
              ))
            )}
          </div>
        ),
      },
    ];
  };

  return (
    <Drawer
      open={!!runId}
      onClose={onClose}
      width={1000}
      title={
        <div className="flex items-center gap-2">
          <ClipboardList style={{ color: "#8b5cf6" }} size={20} />
          <span className="font-semibold text-zinc-800 dark:text-zinc-100 text-lg">运行全链路审计证据链</span>
        </div>
      }
      closable={true}
      rootClassName={themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"}
    >
      {/* 恢复 sys-drawer-section 整体页面级可滑动模型，事件轨迹与变量快照在下方衔接并可一直往下滚 */}
      <div className="sys-drawer-section sys-drawer-section-enter">
        {loading ? (
          <div className="flex items-center justify-center" style={{ minHeight: 320 }}>
            <Spin size="large" />
          </div>
        ) : evidence ? (
          <>
            {/* 第一部分：顶部 hero 概要卡片 */}
            <div className="run-evidence-hero mb-6">
              <div className="run-evidence-hero-main">
                <span className="run-evidence-hero-icon" aria-hidden="true">
                  <ClipboardList size={22} />
                </span>
                <div className="run-evidence-hero-body">
                  <div className="run-evidence-hero-title">
                    <h3 className="text-lg font-bold">{evidence.runInfo.title}</h3>
                    {formatState(evidence.runInfo.state)}
                  </div>
                  <div className="run-evidence-hero-meta">
                    <span className="run-evidence-hero-meta-item">
                      <Code2 size={14} />
                      流程: {evidence.runInfo.workflowName} (v{evidence.runInfo.versionNumber})
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <User size={14} />
                      发起人: {evidence.runInfo.operatorName}
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <Clock size={14} />
                      启动于: {formatDate(evidence.runInfo.startedAt)}
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <CheckCircle2 size={14} />
                      结束于: {formatDate(evidence.runInfo.completedAt)}
                    </span>
                    <span className="run-evidence-hero-meta-item" title="本次运行全部模型调用累计">
                      <Sigma size={14} />
                      Token: {evidence.tokenUsage.totalTokens.toLocaleString("zh-CN")}（输入 {evidence.tokenUsage.inputTokens.toLocaleString("zh-CN")} / 输出 {evidence.tokenUsage.outputTokens.toLocaleString("zh-CN")}）
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* 第二部分：流程步骤与执行明细看板 - 支持折叠默认展开 */}
            <div className="mb-6">
              <Collapse
                ghost
                className="run-evidence-collapse run-evidence-steps-collapse"
                defaultActiveKey={["details"]}
                items={[
                  {
                    key: "details",
                    label: (
                      <span className="flex items-center gap-1.5 text-zinc-700 dark:text-zinc-300 font-semibold text-sm">
                        <ClipboardList size={14} style={{ color: "#8b5cf6" }} />
                        流程步骤与执行明细
                      </span>
                    ),
                    children: (
                      <div className="run-evidence-body" style={{ minHeight: 460 }}>
                        {/* 左侧流程节点步骤轨道 */}
                        <div className="run-evidence-sidebar" style={{ overflowY: "visible", height: "auto" }}>
                          <div className="run-evidence-node-list">
                            {evidence.nodeRuns.map((node) => {
                              const isActive = node.nodeKey === activeNodeKey;
                              const isNodeFailed = node.state === "failed";
                              const nodeClasses = [
                                "run-evidence-node",
                                isActive && "run-evidence-node--active",
                                isNodeFailed && "run-evidence-node--failed",
                              ].filter(Boolean).join(" ");

                              return (
                                <button
                                  key={node.id}
                                  type="button"
                                  onClick={() => setActiveNodeKey(node.nodeKey)}
                                  className={nodeClasses}
                                >
                                  <span className="run-evidence-node-icon">
                                    {getNodeIcon(node.nodeType)}
                                  </span>
                                  <span className="run-evidence-node-name">{node.name}</span>
                                </button>
                              );
                            })}
                          </div>
                        </div>

                        {/* 右侧选定节点之下的日志和环境细节 */}
                        <div className="run-evidence-content" style={{ overflowY: "visible", height: "auto" }}>
                          {activeNode ? (
                            <>
                              {/* 节点标题及基本元数据 */}
                              <div className="run-evidence-node-header">
                                <div>
                                  <h4 className="text-base font-semibold">{activeNode.name}</h4>
                                  <div className="run-evidence-node-header-sub">
                                    标识: {activeNode.nodeKey} · 类型: {activeNode.nodeType}
                                  </div>
                                </div>
                                <div className="run-evidence-node-header-duration">
                                  <Timer size={12} className="inline mr-1 opacity-50" />
                                  耗时: {computeDuration(activeNode.startedAt, activeNode.completedAt)}
                                </div>
                              </div>

                              <Tabs
                                defaultActiveKey="variables"
                                className="agent-admin-tabs run-evidence-tabs"
                                items={buildNodeTabItems()}
                              />
                            </>
                          ) : (
                            <div className="flex items-center justify-center" style={{ minHeight: 200 }}>
                              <Empty description="请在左侧选择具体节点以查看审计细节" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                            </div>
                          )}
                        </div>
                      </div>
                    )
                  }
                ]}
              />
            </div>

            {/* 第三部分：工作流全局辅助（时间线与全局变量）- 移出节点详情，放到整体面板下方，支持直接滚到底 */}
            <div className="mt-6">
              <Collapse
                ghost
                className="run-evidence-collapse"
                items={[
                  {
                    key: "timeline",
                    label: (
                      <span className="flex items-center gap-1.5 text-zinc-700 dark:text-zinc-300 font-semibold text-sm">
                        <Activity size={14} style={{ color: "#8b5cf6" }} />
                        工作流轨迹事件时间线
                      </span>
                    ),
                    children: (
                      <div className="pt-2">
                        {evidence.runEvents.length === 0 ? (
                          <Empty description="无事件记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                        ) : (
                          <div className="run-evidence-timeline">
                            {evidence.runEvents.map((evt) => (
                              <div key={evt.id} className="run-evidence-timeline-event">
                                <span className="run-evidence-timeline-dot" />
                                <div className="run-evidence-timeline-event-header">
                                  <span className="run-evidence-timeline-event-title">{evt.title}</span>
                                  <span className="run-evidence-timeline-event-time">{formatDate(evt.eventTime)}</span>
                                </div>
                                <div className="run-evidence-timeline-event-desc">
                                  {evt.description} {evt.operatorName ? `(操作人: ${evt.operatorName})` : ""}
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )
                  },
                  {
                    key: "all_vars",
                    label: (
                      <span className="flex items-center gap-1.5 text-zinc-700 dark:text-zinc-300 font-semibold text-sm">
                        <FileText size={14} style={{ color: "#8b5cf6" }} />
                        流程全局变量最终快照
                      </span>
                    ),
                    children: (
                      <div className="pt-2">
                        {evidence.variableSnapshots.length === 0 ? (
                          <Empty description="无变量快照" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                        ) : (
                          evidence.variableSnapshots.map((v) => (
                            <div key={v.id} className="run-evidence-var-card p-4 mb-3 bg-zinc-50/50 dark:bg-zinc-900/50 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xs hover:shadow-sm transition-all">
                              <div className="run-evidence-var-header flex items-center justify-between pb-2 mb-2 border-b border-dashed border-zinc-200 dark:border-zinc-700">
                                <span className="run-evidence-var-name font-mono text-sm font-semibold text-zinc-850 dark:text-zinc-200 flex items-center gap-2">
                                  {v.variableName}
                                  {v.sensitive && <Lock size={10} className="text-red-400" />}
                                </span>
                                <span className="run-evidence-var-type text-xs text-zinc-400 bg-zinc-100 dark:bg-zinc-800 px-2 py-0.5 rounded">类型: {v.valueType}</span>
                              </div>
                              <pre className="run-evidence-code m-0 p-3 bg-zinc-100/60 dark:bg-zinc-950/60 border border-zinc-200 dark:border-zinc-850 rounded-lg font-mono text-xs text-zinc-800 dark:text-zinc-200 overflow-x-auto whitespace-pre-wrap break-all">
                                {v.sensitive ? (
                                  <span className="run-evidence-sensitive text-red-500 font-medium italic flex items-center gap-1.5">
                                    <EyeOff size={12} /> ****** (敏感信息，审计日志已自动遮蔽)
                                  </span>
                                ) : (
                                  formatJson(v.value)
                                )}
                              </pre>
                            </div>
                          ))
                        )}
                      </div>
                    )
                  }
                ]}
              />
            </div>
          </>
        ) : (
          <div className="flex items-center justify-center" style={{ minHeight: 320 }}>
            <Empty description="无法加载证据链数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        )}
      </div>
    </Drawer>
  );
}
