import { useEffect, useState } from "react";
import { Drawer, Spin, Tabs, Tag, Empty } from "antd";
import type { TabsProps } from "antd";
import {
  Clock, User, ClipboardList, Activity, Sparkles, Cpu,
  Send, AlertCircle, Code2, Lock, EyeOff, CheckCircle2,
  Timer, FileText
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
          <div className="run-evidence-tab-content">
            {activeNodeVariables.length === 0 ? (
              <Empty description="该节点无变量输出" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeVariables.map((v) => (
                <div key={v.id} className="run-evidence-var-card">
                  <div className="run-evidence-var-header">
                    <span className="run-evidence-var-name">
                      {v.variableName}
                      {v.sensitive && (
                        <Tag color="red" icon={<Lock size={10} />} className="flex items-center gap-1 px-1.5 py-0">敏感</Tag>
                      )}
                    </span>
                    <span className="run-evidence-var-type">类型: {v.valueType}</span>
                  </div>
                  <pre className="run-evidence-code">
                    {v.sensitive ? (
                      <span className="run-evidence-sensitive">
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
          <div className="run-evidence-tab-content">
            {activeNodeModelCalls.length === 0 ? (
              <Empty description="该节点无大模型调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeModelCalls.map((m) => (
                <div key={m.id} className="run-evidence-call-card">
                  <div className="run-evidence-call-header">
                    <span className="run-evidence-call-title">
                      <Sparkles size={13} className="text-yellow-500" />
                      使用模型: {m.modelName}
                    </span>
                    <span className="run-evidence-call-meta">
                      <Timer size={11} className="inline mr-1 opacity-50" />
                      {m.latencyMs ? `${m.latencyMs} ms` : "—"}
                    </span>
                  </div>
                  <div>
                    <p className="run-evidence-section-label">提示词快照 (Prompt Snapshot)</p>
                    <pre className="run-evidence-code">{formatJson(m.promptSnapshot)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label">模型输出结果 (Response Snapshot)</p>
                    <pre className="run-evidence-code">{formatJson(m.responseSnapshot)}</pre>
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
          <div className="run-evidence-tab-content">
            {activeNodeMcpCalls.length === 0 ? (
              <Empty description="该节点无外部工具 (MCP) 调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeMcpCalls.map((m) => (
                <div key={m.id} className="run-evidence-call-card">
                  <div className="run-evidence-call-header">
                    <span className="run-evidence-call-title">
                      <Cpu size={13} className="text-blue-500" />
                      调用能力: {m.capabilityCode} · 工具: {m.toolName}
                    </span>
                    <span className="run-evidence-call-meta">
                      {m.status === "success" ? <span className="text-green-500 font-semibold">成功</span> : <span className="text-red-500 font-semibold">失败</span>}
                      {m.latencyMs ? ` · ${m.latencyMs} ms` : ""}
                    </span>
                  </div>
                  <div>
                    <p className="run-evidence-section-label">入参载荷 (Request Arguments)</p>
                    <pre className="run-evidence-code">{formatJson(m.requestPayload)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label">观察结果 (Response Outcome)</p>
                    <pre className="run-evidence-code">{formatJson(m.responsePayload)}</pre>
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
          <div className="run-evidence-tab-content">
            {activeNodeDeliveries.length === 0 ? (
              <Empty description="该节点无推送交付记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              activeNodeDeliveries.map((d) => (
                <div key={d.id} className="run-evidence-call-card">
                  <div className="run-evidence-call-header">
                    <span className="run-evidence-call-title">
                      <Send size={13} className="text-primary-500" />
                      方式: {d.deliveryType} · 标题: {d.title}
                    </span>
                    <span>
                      {d.status === "success" ? <Tag color="success">交付成功</Tag> : <Tag color="error">交付失败</Tag>}
                    </span>
                  </div>
                  <div className="run-evidence-delivery-meta">
                    <div>目标地址/Key: <span>{d.target || "—"}</span></div>
                    <div>交付时间: <span>{formatDate(d.createdAt)}</span></div>
                  </div>
                  <div>
                    <p className="run-evidence-section-label">交付载荷</p>
                    <pre className="run-evidence-code">{formatJson(d.payload)}</pre>
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <p className="run-evidence-section-label">交付结果快照</p>
                    <pre className="run-evidence-code">{formatJson(d.resultSnapshot)}</pre>
                  </div>
                  {d.errorMessage && (
                    <div className="run-evidence-error">
                      <AlertCircle size={14} className="mt-0.5 shrink-0" />
                      <div>
                        <div className="run-evidence-error-title">失败原因</div>
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
                <div key={v.id} className="run-evidence-var-card">
                  <div className="run-evidence-var-header">
                    <span className="run-evidence-var-name">
                      {v.variableName}
                      {v.sensitive && <Lock size={10} className="text-red-400" />}
                    </span>
                    <span className="run-evidence-var-type">类型: {v.valueType}</span>
                  </div>
                  <pre className="run-evidence-code">
                    {v.sensitive ? (
                      <span className="run-evidence-sensitive">
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
          <ClipboardList className="text-primary-500" size={18} />
          <span className="font-semibold text-zinc-800 dark:text-zinc-100">运行全链路审计证据链</span>
        </div>
      }
      closable={true}
      rootClassName={themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"}
    >
      {/* sys-drawer-section 直接作为 drawer body 的子级，确保 flex: 1 + overflow-y: auto 正常工作 */}
      <div className="sys-drawer-section sys-drawer-section-enter">
        {loading ? (
          <div className="flex items-center justify-center" style={{ minHeight: 320 }}>
            <Spin size="large" />
          </div>
        ) : evidence ? (
          <>
            {/* 顶部 hero 概要卡片：参考 workflow-detail-drawer-hero 品牌色渐变风格 */}
            <div className="run-evidence-hero">
              <div className="run-evidence-hero-main">
                <span className="run-evidence-hero-icon" aria-hidden="true">
                  <ClipboardList size={20} />
                </span>
                <div className="run-evidence-hero-body">
                  <div className="run-evidence-hero-title">
                    <h3>{evidence.runInfo.title}</h3>
                    {formatState(evidence.runInfo.state)}
                  </div>
                  <div className="run-evidence-hero-meta">
                    <span className="run-evidence-hero-meta-item">
                      <Code2 size={13} />
                      流程: {evidence.runInfo.workflowName} (v{evidence.runInfo.versionNumber})
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <User size={13} />
                      发起人: {evidence.runInfo.operatorName}
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <Clock size={13} />
                      启动于: {formatDate(evidence.runInfo.startedAt)}
                    </span>
                    <span className="run-evidence-hero-meta-item">
                      <CheckCircle2 size={13} />
                      结束于: {formatDate(evidence.runInfo.completedAt)}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* 证据链详情主面板：左侧节点步骤轨道 + 右侧节点日志详情 */}
            <div className="run-evidence-body">
              {/* 左侧流程节点步骤轨道 */}
              <div className="run-evidence-sidebar">
                <div className="run-evidence-sidebar-title">流程步骤轨道</div>
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
              <div className="run-evidence-content">
                {activeNode ? (
                  <>
                    {/* 节点标题及基本元数据 */}
                    <div className="run-evidence-node-header">
                      <div>
                        <h4>{activeNode.name}</h4>
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
                      className="agent-admin-tabs"
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

            {/* 底部全景辅助信息：轨迹时间线与全局变量池 */}
            <div className="run-evidence-footer">
              <Tabs
                defaultActiveKey="timeline"
                className="agent-admin-tabs"
                items={buildFooterTabItems()}
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

