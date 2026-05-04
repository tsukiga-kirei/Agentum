import { AlertTriangle, CheckCircle2, ClipboardCheck, Clock3, FileText, ListChecks, Mail, RotateCcw, ScrollText, TableProperties, UserCheck, Wrench } from "lucide-react";
import type { AuditEvent, DeliveryRecord, WorkflowRunState, WorkflowVariableContract } from "../../types/workflow-contract";

type RunStep = {
  name: string;
  type: string;
  state: "已完成" | "运行中" | "等待输入" | "等待审核" | "失败";
  duration: string;
  summary: string;
};

const currentRun = {
  id: "run_20260504_001",
  workflow: "需求分析与评审流程",
  version: "v1.2",
  state: "paused" as WorkflowRunState,
  currentNode: "人工审核",
  pauseReason: "智能体识别到高风险范围变更，需要流程负责人确认。",
  waitingFor: "产品负责人 / 法务观察员",
  requestId: "req_run_6f9c21",
};

const runSteps: RunStep[] = [
  { name: "补充业务材料", type: "用户输入", state: "已完成", duration: "2m 10s", summary: "收集需求背景、附件和交付物要求。" },
  { name: "智能体分析", type: "智能体", state: "已完成", duration: "38s", summary: "调用需求拆解与风险识别 Skill，生成 analysis_result 和 risk_level。" },
  { name: "并行获取数据", type: "并行节点组", state: "已完成", duration: "1m 12s", summary: "文件读取 MCP、知识库检索和数据库只读查询均已返回。" },
  { name: "人工审核", type: "人工审核", state: "等待审核", duration: "暂停 18m", summary: "等待负责人确认风险等级和交付范围。" },
  { name: "邮件交付", type: "交付", state: "等待输入", duration: "-", summary: "审核后生成邮件交付记录。" },
];

const variableSnapshots: WorkflowVariableContract[] = [
  { name: "project_info", sourceNode: "补充业务材料", type: "object", description: "项目背景、目标和约束", sensitive: false, deliverable: false },
  { name: "attachments", sourceNode: "补充业务材料", type: "file", description: "需求附件清单", sensitive: true, deliverable: false },
  { name: "analysis_result", sourceNode: "智能体分析", type: "object", description: "需求拆解、风险和追问建议", sensitive: false, deliverable: true },
  { name: "risk_level", sourceNode: "智能体分析", type: "decision", description: "高风险", sensitive: false, deliverable: false },
  { name: "research_pack", sourceNode: "并行获取数据", type: "object", description: "知识库和外部数据摘要", sensitive: false, deliverable: true },
];

const auditEvents: AuditEvent[] = [
  { id: "audit_001", time: "15:20:11", actor: "张予安", resourceType: "WorkflowRun", action: "execute", result: "success", summary: "启动需求分析与评审流程 v1.2。" },
  { id: "audit_002", time: "15:22:44", actor: "Agent Runtime", resourceType: "NodeRun", action: "invoke_mcp", result: "success", summary: "调用文件读取 MCP，参数已脱敏，审计编号 mcp_8732。" },
  { id: "audit_003", time: "15:23:18", actor: "Agent Runtime", resourceType: "NodeRun", action: "parse_output", result: "warning", summary: "risk_level=high，流程暂停等待人工审核。" },
  { id: "audit_004", time: "15:25:02", actor: "系统", resourceType: "WorkflowRun", action: "pause", result: "success", summary: "暂停事件已写入 waiting_event。" },
];

const deliveryRecords: DeliveryRecord[] = [
  { id: "delivery_doc", target: "Word / PDF", status: "pending", artifact: "需求评审报告草稿", retryable: true },
  { id: "delivery_mail", target: "邮件发送 MCP", status: "pending", artifact: "评审结论邮件", retryable: true },
];

export function RunAuditPage() {
  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px] xl:items-center">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">阶段一：运行详情与审计证据链</p>
            <h2 className="mt-2 text-xl font-semibold">{currentRun.workflow}</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              当前流程暂停在“{currentRun.currentNode}”，原因是：{currentRun.pauseReason}
            </p>
          </div>
          <div className="rounded-[var(--radius-md)] border border-amber-200 bg-amber-50 p-4 text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-100">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <AlertTriangle className="h-4 w-4" aria-hidden="true" />
              等待处理
            </div>
            <p className="mt-2 text-sm">等待对象：{currentRun.waitingFor}</p>
            <p className="mt-1 text-xs">Run：{currentRun.id} · {currentRun.version} · {currentRun.requestId}</p>
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <div className="agent-card">
          <SectionHeader icon={ListChecks} title="运行步骤" description="展示流程为什么停住、下一步由谁处理" />
          <div className="divide-y divide-[var(--color-border-light)]">
            {runSteps.map((step, index) => (
              <article key={step.name} className="grid gap-3 px-4 py-3 md:grid-cols-[40px_minmax(0,1fr)_120px] md:items-center">
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-[var(--color-bg-hover)] text-sm font-semibold">{index + 1}</span>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-semibold">{step.name}</h3>
                    <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">{step.type}</span>
                    <StateBadge state={step.state} />
                  </div>
                  <p className="agent-muted mt-2 text-sm">{step.summary}</p>
                </div>
                <p className="text-xs text-[var(--color-text-tertiary)]">{step.duration}</p>
              </article>
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={ClipboardCheck} title="恢复操作" description="前端先模拟暂停恢复入口，后续接入恢复事件 API" />
          <div className="space-y-3 p-4">
            <button type="button" className="agent-button agent-button-primary h-10 w-full px-3 text-sm">
              <UserCheck className="h-4 w-4" aria-hidden="true" />
              审核通过并继续
            </button>
            <button type="button" className="agent-button h-10 w-full px-3 text-sm">
              <RotateCcw className="h-4 w-4" aria-hidden="true" />
              驳回到智能体分析
            </button>
            <p className="agent-muted rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] p-3 text-sm leading-6">
              恢复事件需要记录操作人、审核意见、附件和来源渠道；后端仍需重新校验权限。
            </p>
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <div className="agent-card">
          <SectionHeader icon={TableProperties} title="变量快照" description="运行中的变量值、来源和交付可用性" />
          <div className="grid gap-3 p-4 md:grid-cols-2">
            {variableSnapshots.map((variable) => (
              <article key={variable.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">{variable.name}</h3>
                  <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">{variable.type}</span>
                </div>
                <p className="agent-muted mt-2 text-xs">{variable.description}</p>
                <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">来源：{variable.sourceNode}</p>
                {variable.sensitive ? <p className="mt-2 rounded bg-red-50 px-2 py-1 text-xs font-medium text-red-700">敏感变量，默认脱敏展示</p> : null}
              </article>
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={Mail} title="交付记录" description="基础交付能力先覆盖文档与邮件两类目标" />
          <div className="space-y-3 p-4">
            {deliveryRecords.map((record) => (
              <article key={record.id} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">{record.target}</h3>
                  <span className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">待交付</span>
                </div>
                <p className="agent-muted mt-2 text-sm">{record.artifact}</p>
                <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">失败后{record.retryable ? "可重试" : "不可重试"}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="agent-card">
        <SectionHeader icon={ScrollText} title="审计日志" description="MCP、模型输出解析、暂停和人工操作都进入证据链" />
        <div className="divide-y divide-[var(--color-border-light)]">
          {auditEvents.map((event) => (
            <article key={event.id} className="grid gap-3 px-4 py-3 md:grid-cols-[110px_140px_minmax(0,1fr)_120px] md:items-center">
              <span className="text-xs text-[var(--color-text-tertiary)]">{event.time}</span>
              <span className="text-sm font-medium">{event.actor}</span>
              <p className="agent-muted text-sm">{event.resourceType} · {event.action} · {event.summary}</p>
              <StateDot result={event.result} />
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}

function SectionHeader({ icon: Icon, title, description }: { icon: typeof ListChecks; title: string; description: string }) {
  return (
    <div className="px-4 pb-2 pt-4">
      <div className="flex items-center gap-2">
        <Icon className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
        <h2 className="text-base font-semibold">{title}</h2>
      </div>
      <p className="agent-muted mt-1 text-sm">{description}</p>
    </div>
  );
}

function StateBadge({ state }: { state: RunStep["state"] }) {
  if (state === "已完成") {
    return <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />已完成</span>;
  }

  if (state === "等待审核" || state === "等待输入") {
    return <span className="inline-flex items-center gap-1 rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"><Clock3 className="h-3.5 w-3.5" aria-hidden="true" />{state}</span>;
  }

  return <span className="rounded bg-sky-100 px-2 py-1 text-xs font-medium text-sky-800">{state}</span>;
}

function StateDot({ result }: { result: AuditEvent["result"] }) {
  const className =
    result === "success"
      ? "bg-emerald-100 text-emerald-800"
      : result === "failed"
        ? "bg-red-100 text-red-700"
        : "bg-amber-100 text-amber-800";

  return (
    <span className={`inline-flex w-fit items-center gap-1 rounded px-2 py-1 text-xs font-medium ${className}`}>
      {result === "warning" ? <Wrench className="h-3.5 w-3.5" aria-hidden="true" /> : <FileText className="h-3.5 w-3.5" aria-hidden="true" />}
      {result}
    </span>
  );
}
