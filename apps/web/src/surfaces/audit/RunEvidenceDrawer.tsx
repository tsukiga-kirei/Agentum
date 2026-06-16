import { useEffect, useState } from "react";
import { Drawer, Spin, Tabs, Tag, Empty, Button } from "antd";
import { 
  X, Clock, User, ClipboardList, Activity, Sparkles, Cpu, 
  Send, AlertCircle, FileText, Code2, Lock, EyeOff, CheckCircle2 
} from "lucide-react";
import { auditApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type { AuditEvidence } from "../../types/audit";

interface RunEvidenceDrawerProps {
  runId: string | null;
  onClose: () => void;
}

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
      case "running": return <Tag color="processing">执行中</Tag>;
      case "paused": return <Tag color="warning">已暂停</Tag>;
      case "completed": return <Tag color="success">已完成</Tag>;
      case "failed": return <Tag color="error">已失败</Tag>;
      case "canceled": return <Tag color="default">已取消</Tag>;
      default: return <Tag color="default">{s}</Tag>;
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
      case "trigger": return <Activity size={14} />;
      case "input": return <ClipboardList size={14} />;
      case "agent": return <Sparkles size={14} />;
      case "cluster": return <Sparkles size={14} />;
      case "delivery": return <Send size={14} />;
      default: return <Code2 size={14} />;
    }
  };

  return (
    <Drawer
      open={!!runId}
      onClose={onClose}
      width={900}
      title={
        <div className="flex items-center justify-between pr-4">
          <div className="flex items-center gap-2">
            <ClipboardList className="text-primary-500" size={18} />
            <span className="font-semibold text-zinc-800 dark:text-zinc-100">运行全链路审计证据链</span>
          </div>
        </div>
      }
      closable={false}
      extra={
        <Button 
          type="text" 
          icon={<X size={18} className="text-zinc-400 hover:text-zinc-600" />} 
          onClick={onClose} 
        />
      }
      rootClassName={themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"}
    >
      <Spin spinning={loading}>
        {evidence ? (
          <div className="flex flex-col h-full space-y-6">
            {/* 顶层实例概要 */}
            <div className="p-4 bg-zinc-50 dark:bg-zinc-800/40 border border-zinc-100 dark:border-zinc-800 rounded-xl space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <h3 className="font-bold text-zinc-800 dark:text-zinc-100 text-base">
                  {evidence.runInfo.title}
                </h3>
                {formatState(evidence.runInfo.state)}
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-xs text-zinc-500">
                <div className="flex items-center gap-1.5">
                  <Code2 size={14} className="text-zinc-400" />
                  <span>流程: {evidence.runInfo.workflowName} (v{evidence.runInfo.versionNumber})</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <User size={14} className="text-zinc-400" />
                  <span>发起人: {evidence.runInfo.operatorName}</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <Clock size={14} className="text-zinc-400" />
                  <span>启动于: {formatDate(evidence.runInfo.startedAt)}</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <CheckCircle2 size={14} className="text-zinc-400" />
                  <span>结束于: {formatDate(evidence.runInfo.completedAt)}</span>
                </div>
              </div>
            </div>

            {/* 证据链详情主面板：左侧节点选择，右侧日志展示 */}
            <div className="flex flex-1 gap-6 min-h-[480px]">
              {/* 左侧流程节点步骤轨道 */}
              <div className="w-1/4 border-r border-zinc-100 dark:border-zinc-800 pr-4 space-y-2 max-h-[550px] overflow-y-auto">
                <div className="text-xs font-semibold text-zinc-400 uppercase tracking-wider mb-3">流程步骤轨道</div>
                {evidence.nodeRuns.map((node) => {
                  const isActive = node.nodeKey === activeNodeKey;
                  const isNodeFailed = node.state === "failed";
                  return (
                    <div
                      key={node.id}
                      onClick={() => setActiveNodeKey(node.nodeKey)}
                      className={`flex items-center gap-2 p-2.5 rounded-lg cursor-pointer text-sm transition-all border ${
                        isActive
                          ? "bg-primary-50 dark:bg-primary-950/20 border-primary-200 dark:border-primary-900 text-primary-700 dark:text-primary-400 font-medium"
                          : isNodeFailed
                          ? "bg-red-50/40 dark:bg-red-950/10 border-red-100 dark:border-red-950 text-red-600 dark:text-red-400"
                          : "bg-white dark:bg-zinc-900 border-zinc-100 dark:border-zinc-800 text-zinc-600 dark:text-zinc-400 hover:bg-zinc-50 dark:hover:bg-zinc-800/60"
                      }`}
                    >
                      <div className={`p-1.5 rounded-md ${
                        isActive 
                          ? "bg-primary-100 dark:bg-primary-900/60 text-primary-600 dark:text-primary-400" 
                          : isNodeFailed
                          ? "bg-red-100 dark:bg-red-900/30 text-red-600"
                          : "bg-zinc-100 dark:bg-zinc-800 text-zinc-400"
                      }`}>
                        {getNodeIcon(node.nodeType)}
                      </div>
                      <span className="truncate flex-1">{node.name}</span>
                    </div>
                  );
                })}
              </div>

              {/* 右侧选定节点之下的日志和环境细节 */}
              <div className="flex-1 space-y-4 max-h-[550px] overflow-y-auto pr-1">
                {activeNode ? (
                  <div className="space-y-5">
                    {/* 节点标题及基本元数据 */}
                    <div className="flex items-center justify-between border-b border-zinc-100 dark:border-zinc-800 pb-3">
                      <div>
                        <h4 className="font-bold text-zinc-800 dark:text-zinc-100">{activeNode.name}</h4>
                        <div className="text-xs text-zinc-400 mt-0.5">
                          标识: {activeNode.nodeKey} · 类型: {activeNode.nodeType}
                        </div>
                      </div>
                      <div className="text-xs text-zinc-500">
                        耗时: {activeNode.startedAt && activeNode.completedAt 
                          ? `${new Date(activeNode.completedAt).getTime() - new Date(activeNode.startedAt).getTime()} ms`
                          : "—"}
                      </div>
                    </div>

                    <Tabs defaultActiveKey="variables" className="audit-drawer-tabs">
                      {/* 页签 1: 变量与数据快照 */}
                      <Tabs.TabPane tab="数据变量快照" key="variables">
                        <div className="space-y-3">
                          {activeNodeVariables.length === 0 ? (
                            <Empty description="该节点无变量输出" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                          ) : (
                            <div className="space-y-3">
                              {activeNodeVariables.map((v) => (
                                <div key={v.id} className="p-3 bg-zinc-50 dark:bg-zinc-950 border border-zinc-100 dark:border-zinc-800/80 rounded-xl space-y-2">
                                  <div className="flex items-center justify-between">
                                    <span className="font-mono text-sm text-zinc-700 dark:text-zinc-300 font-semibold flex items-center gap-1.5">
                                      {v.variableName}
                                      {v.sensitive && (
                                        <Tag color="red" icon={<Lock size={10} />} className="flex items-center gap-1 px-1.5 py-0">敏感</Tag>
                                      )}
                                    </span>
                                    <span className="text-xs text-zinc-400">类型: {v.valueType}</span>
                                  </div>
                                  <div className="relative">
                                    <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-800 dark:text-zinc-200">
                                      {v.sensitive ? (
                                        <span className="text-red-400 italic flex items-center gap-1">
                                          <EyeOff size={12} /> ****** (敏感信息，审计日志已自动遮蔽)
                                        </span>
                                      ) : (
                                        formatJson(v.value)
                                      )}
                                    </pre>
                                  </div>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </Tabs.TabPane>

                      {/* 页签 2: 模型推理审查 */}
                      <Tabs.TabPane tab={`模型调用 (${activeNodeModelCalls.length})`} key="models">
                        <div className="space-y-4">
                          {activeNodeModelCalls.length === 0 ? (
                            <Empty description="该节点无大模型调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                          ) : (
                            activeNodeModelCalls.map((m) => (
                              <div key={m.id} className="border border-zinc-100 dark:border-zinc-800 rounded-xl p-4 space-y-3 bg-zinc-50/40 dark:bg-zinc-950/20">
                                <div className="flex justify-between items-center text-xs">
                                  <span className="font-bold text-zinc-700 dark:text-zinc-300 flex items-center gap-1">
                                    <Sparkles size={12} className="text-yellow-500" />
                                    使用模型: {m.modelName}
                                  </span>
                                  <span className="text-zinc-400">耗时: {m.latencyMs ? `${m.latencyMs} ms` : "—"}</span>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">提示词快照 (Prompt Snapshot)</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(m.promptSnapshot)}
                                  </pre>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">模型输出结果 (Response Snapshot)</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(m.responseSnapshot)}
                                  </pre>
                                </div>
                              </div>
                            ))
                          )}
                        </div>
                      </Tabs.TabPane>

                      {/* 页签 3: MCP网关工具审查 */}
                      <Tabs.TabPane tab={`MCP工具/Skill (${activeNodeMcpCalls.length})`} key="mcp">
                        <div className="space-y-4">
                          {activeNodeMcpCalls.length === 0 ? (
                            <Empty description="该节点无外部工具 (MCP) 调用记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                          ) : (
                            activeNodeMcpCalls.map((m) => (
                              <div key={m.id} className="border border-zinc-100 dark:border-zinc-800 rounded-xl p-4 space-y-3 bg-zinc-50/40 dark:bg-zinc-950/20">
                                <div className="flex justify-between items-center text-xs">
                                  <span className="font-bold text-zinc-700 dark:text-zinc-300 flex items-center gap-1">
                                    <Cpu size={12} className="text-blue-500" />
                                    调用能力: {m.capabilityCode} · 工具: {m.toolName}
                                  </span>
                                  <span className="text-zinc-400">
                                    状态: {m.status === "success" ? <span className="text-green-500 font-semibold">成功</span> : <span className="text-red-500 font-semibold">失败</span>}
                                    {m.latencyMs ? ` · 耗时 ${m.latencyMs} ms` : ""}
                                  </span>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">入参载荷 (Request Arguments)</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(m.requestPayload)}
                                  </pre>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">观察结果 (Response Outcome)</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(m.responsePayload)}
                                  </pre>
                                </div>
                              </div>
                            ))
                          )}
                        </div>
                      </Tabs.TabPane>

                      {/* 页签 4: 交付物推送审计 */}
                      <Tabs.TabPane tab={`交付推送 (${activeNodeDeliveries.length})`} key="deliveries">
                        <div className="space-y-4">
                          {activeNodeDeliveries.length === 0 ? (
                            <Empty description="该节点无推送交付记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                          ) : (
                            activeNodeDeliveries.map((d) => (
                              <div key={d.id} className="border border-zinc-100 dark:border-zinc-800 rounded-xl p-4 space-y-3 bg-zinc-50/40 dark:bg-zinc-950/20">
                                <div className="flex justify-between items-center text-xs">
                                  <span className="font-bold text-zinc-700 dark:text-zinc-300 flex items-center gap-1.5">
                                    <Send size={12} className="text-primary-500" />
                                    方式: {d.deliveryType} · 标题: {d.title}
                                  </span>
                                  <span>
                                    {d.status === "success" ? <Tag color="success">交付成功</Tag> : <Tag color="error">交付失败</Tag>}
                                  </span>
                                </div>
                                <div className="grid grid-cols-2 gap-4 text-xs text-zinc-500">
                                  <div>目标地址/Key: <span className="font-mono text-zinc-700 dark:text-zinc-300">{d.target || "—"}</span></div>
                                  <div>交付时间: <span className="text-zinc-700 dark:text-zinc-300">{formatDate(d.createdAt)}</span></div>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">交付载荷</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(d.payload)}
                                  </pre>
                                </div>
                                <div className="space-y-2">
                                  <div className="text-xs text-zinc-400 uppercase tracking-wider font-semibold">交付结果快照</div>
                                  <pre className="p-3 bg-white dark:bg-zinc-900 border border-zinc-100 dark:border-zinc-800 rounded-lg text-xs font-mono overflow-auto text-zinc-700 dark:text-zinc-300">
                                    {formatJson(d.resultSnapshot)}
                                  </pre>
                                </div>
                                {d.errorMessage && (
                                  <div className="p-3 bg-red-50 dark:bg-red-950/20 border border-red-100 dark:border-red-950/60 rounded-lg flex items-start gap-2 text-xs text-red-600">
                                    <AlertCircle size={14} className="mt-0.5 shrink-0" />
                                    <div>
                                      <div className="font-semibold">失败原因</div>
                                      <div>{d.errorMessage}</div>
                                    </div>
                                  </div>
                                )}
                              </div>
                            ))
                          )}
                        </div>
                      </Tabs.TabPane>
                    </Tabs>
                  </div>
                ) : (
                  <div className="h-full flex items-center justify-center">
                    <Empty description="请在左侧选择具体节点以查看审计细节" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                  </div>
                )}
              </div>
            </div>

            {/* 底部全景辅助信息：轨迹时间线与全局变量池 */}
            <div className="border-t border-zinc-100 dark:border-zinc-800 pt-4">
              <Tabs defaultActiveKey="timeline" className="audit-drawer-tabs">
                <Tabs.TabPane tab="工作流轨迹事件时间线" key="timeline">
                  <div className="space-y-4 max-h-40 overflow-y-auto pr-1">
                    {evidence.runEvents.length === 0 ? (
                      <Empty description="无事件记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : (
                      <div className="relative border-l border-zinc-100 dark:border-zinc-800 pl-4 ml-2 space-y-4 text-xs">
                        {evidence.runEvents.map((evt) => (
                          <div key={evt.id} className="relative">
                            <span className="absolute -left-[21px] top-1 w-2.5 h-2.5 rounded-full border bg-white dark:bg-zinc-900 border-zinc-300 dark:border-zinc-700" />
                            <div className="flex items-center justify-between text-zinc-400">
                              <span className="font-semibold text-zinc-600 dark:text-zinc-300">{evt.title}</span>
                              <span>{formatDate(evt.eventTime)}</span>
                            </div>
                            <div className="text-zinc-500 mt-1">
                              {evt.description} {evt.operatorName ? `(操作人: ${evt.operatorName})` : ""}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </Tabs.TabPane>

                <Tabs.TabPane tab="流程全局变量最终快照" key="all_vars">
                  <div className="grid grid-cols-2 gap-3 max-h-40 overflow-y-auto pr-1">
                    {evidence.variableSnapshots.map((v) => (
                      <div key={v.id} className="p-2 border border-zinc-100 dark:border-zinc-800 rounded-lg flex items-center justify-between text-xs bg-zinc-50/40 dark:bg-zinc-950/20">
                        <span className="font-mono text-zinc-600 dark:text-zinc-300 flex items-center gap-1">
                          {v.variableName}
                          {v.sensitive && <Lock size={10} className="text-red-400" />}
                        </span>
                        <span className="text-zinc-400">
                          {v.sensitive ? <span className="italic text-red-400">******</span> : String(v.value)}
                        </span>
                      </div>
                    ))}
                  </div>
                </Tabs.TabPane>
              </Tabs>
            </div>
          </div>
        ) : (
          <Empty description="无法加载证据链数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        )}
      </Spin>
    </Drawer>
  );
}
