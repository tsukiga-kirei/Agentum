import { Bot, BrainCircuit, DatabaseZap, FileText, Mail, Plus } from "lucide-react";
import type { AgentTemplateAsset, DeliveryCapabilityAsset, McpServiceAsset, PromptTemplateAsset } from "../../types/workflow-contract";

type SkillAsset = {
  name: string;
  version: string;
  category: string;
  status: "已发布" | "草稿";
  usage: string;
};

type AssetCreateEntry = {
  title: string;
  detail: string;
  action: string;
};

// 能力资产页先用静态资产解释治理对象，后续应替换为智能体模板、Skill、MCP、提示词和交付能力 API。
const skills: SkillAsset[] = [
  { name: "需求拆解 Skill", version: "v2", category: "taskGuidance", status: "已发布", usage: "需求分析智能体" },
  { name: "追问澄清 Skill", version: "v1", category: "questionGuidance", status: "已发布", usage: "需求分析智能体" },
  { name: "风险识别 Skill", version: "v3", category: "riskGuidance", status: "已发布", usage: "审核判断智能体" },
  { name: "报告撰写 Skill", version: "v1", category: "outputGuidance", status: "草稿", usage: "报告组装智能体" },
];

const agentTemplates: AgentTemplateAsset[] = [
  {
    id: "agt_requirement_analysis",
    name: "需求分析智能体",
    version: "v3",
    status: "published",
    skills: ["需求拆解 Skill", "追问澄清 Skill", "文档摘要 Skill"],
    mcpServices: ["文件读取 MCP"],
    promptTemplates: ["需求追问模板"],
    model: "gpt-4.1 / temperature 0.2",
    outputMode: "pause_then_continue",
  },
  {
    id: "agt_report_assembler",
    name: "报告组装智能体",
    version: "v1",
    status: "draft",
    skills: ["报告撰写 Skill", "语言润色 Skill"],
    mcpServices: ["文档生成 MCP", "邮件发送 MCP"],
    promptTemplates: ["报告组装模板", "正式邮件模板"],
    model: "待选择",
    outputMode: "once",
  },
];

const mcpServices: McpServiceAsset[] = [
  { id: "mcp_file_reader", name: "文件读取 MCP", version: "v1", riskLevel: "medium", authorizedRoles: ["流程设计者", "执行人"], auditRequired: true },
  { id: "mcp_doc_generator", name: "文档生成 MCP", version: "v1", riskLevel: "medium", authorizedRoles: ["流程设计者"], auditRequired: true },
  { id: "mcp_mail_sender", name: "邮件发送 MCP", version: "v1", riskLevel: "high", authorizedRoles: ["空间管理员", "流程设计者"], auditRequired: true },
];

const promptTemplates: PromptTemplateAsset[] = [
  { id: "prompt_requirement_question", name: "需求追问模板", version: "v2", category: "追问确认", status: "published", usage: "需求分析智能体" },
  { id: "prompt_report_assembly", name: "报告组装模板", version: "v1", category: "结果组装", status: "published", usage: "报告组装智能体" },
  { id: "prompt_delivery_mail", name: "正式邮件模板", version: "v1", category: "交付话术", status: "draft", usage: "邮件交付节点" },
];

const deliveryCapabilities: DeliveryCapabilityAsset[] = [
  { id: "delivery_doc", name: "Word / PDF 生成", channel: "document", version: "v1", riskLevel: "medium", status: "published" },
  { id: "delivery_mail", name: "邮件发送", channel: "email", version: "v1", riskLevel: "high", status: "published" },
  { id: "delivery_oa", name: "OA 流程创建", channel: "oa", version: "v0", riskLevel: "high", status: "draft" },
];

// 这里保留各类资产独立入口，避免回到泛化“导入流程”的入口；真实创建动作后续归属各资产模块。
const assetCreateEntries: AssetCreateEntry[] = [
  { title: "新增智能体模板", detail: "配置系统提示词、模型、输出 Schema 和交互模式。", action: "添加模板" },
  { title: "新增 Skill", detail: "沉淀需求拆解、追问澄清、风险识别等方法论能力。", action: "添加 Skill" },
  { title: "新增 MCP", detail: "登记外部工具能力；凭证和租户授权由系统管理处理。", action: "添加 MCP" },
  { title: "新增提示词模板", detail: "维护追问、组装、交付话术等可复用 Prompt。", action: "添加模板" },
  { title: "新增交付能力", detail: "登记文档、邮件、OA、IM、Webhook 或数据库交付能力。", action: "添加能力" },
];

export function AssetsPage() {
  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5">
        <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">阶段一：智能体与能力资产</p>
            <h2 className="mt-2 text-xl font-semibold text-[var(--color-text-primary)]">先把模板、Skills、MCP、提示词和交付能力做成可治理资产</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              当前页面使用内置资产模拟后端资产查询 API，重点验证流程设计节点能引用资产版本并形成复用。
            </p>
          </div>
          <div className="grid grid-cols-3 gap-2 text-center">
            <AssetMetric label="智能体" value={String(agentTemplates.length)} />
            <AssetMetric label="Skills" value={String(skills.length)} />
            <AssetMetric label="MCP" value={String(mcpServices.length)} />
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <div className="agent-card">
          <SectionHeader icon={Bot} title="智能体模板" description="模板引用 Skill、MCP、模型和输出模式，不复制资产内容" actionLabel="新增智能体" />
          <div className="grid gap-3 p-4 md:grid-cols-2">
            {agentTemplates.map((agent) => (
              <article key={agent.id} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">{agent.name}</h3>
                  <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                    {agent.version}
                  </span>
                </div>
                <p className="agent-muted mt-3 text-sm">模型：{agent.model}</p>
                <p className="agent-muted mt-1 text-sm">输出模式：{outputModeLabel[agent.outputMode]}</p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {agent.skills.map((skill) => (
                    <span key={skill} className="rounded bg-indigo-100 px-2 py-1 text-xs font-medium text-indigo-800">{skill}</span>
                  ))}
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {agent.mcpServices.map((mcp) => (
                    <span key={mcp} className="rounded bg-sky-100 px-2 py-1 text-xs font-medium text-sky-800">{mcp}</span>
                  ))}
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {agent.promptTemplates.map((prompt) => (
                    <span key={prompt} className="rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800">{prompt}</span>
                  ))}
                </div>
                <button type="button" className="agent-button mt-4 h-8 px-2.5 text-xs">
                  <Plus className="h-3.5 w-3.5" aria-hidden="true" />
                  添加到流程节点
                </button>
              </article>
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={Plus} title="资产创建入口" description="资产页只管理可复用能力，流程导入动作放到流程设计里" />
          <div className="space-y-3 p-4">
            {assetCreateEntries.map((entry) => (
              <CreateEntry key={entry.title} entry={entry} />
            ))}
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-2">
        <div className="agent-card">
          <SectionHeader icon={BrainCircuit} title="Skills 库" description="按注入区域管理方法论，后续接入 Skill 发布 API" actionLabel="新增 Skill" />
          <div className="divide-y divide-[var(--color-border-light)]">
            {skills.map((skill) => (
              <Row key={skill.name} title={skill.name} meta={`${skill.version} · ${skill.category}`} badge={skill.status} detail={`已装配：${skill.usage}`} actionLabel="添加" />
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={DatabaseZap} title="MCP 服务" description="外部系统和工具调用能力，具体授权由系统管理和权限管理控制" actionLabel="新增 MCP" />
          <div className="space-y-4 p-4">
            {mcpServices.map((mcp) => (
              <article key={mcp.id} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">{mcp.name}</h3>
                  <span className={mcp.riskLevel === "high" ? "rounded bg-red-100 px-2 py-1 text-xs font-medium text-red-700" : "rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"}>
                    {mcp.riskLevel === "high" ? "高风险" : "中风险"}
                  </span>
                </div>
                <p className="agent-muted mt-2 text-xs">可调用角色：{mcp.authorizedRoles.join("、")}；审计：{mcp.auditRequired ? "开启" : "关闭"}</p>
                <button type="button" className="agent-button mt-3 h-8 px-2.5 text-xs">
                  <Plus className="h-3.5 w-3.5" aria-hidden="true" />
                  添加授权
                </button>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-2">
        <div className="agent-card">
          <SectionHeader icon={FileText} title="提示词模板" description="面向追问、组装和交付话术的可版本化模板" actionLabel="新增提示词" />
          <div className="divide-y divide-[var(--color-border-light)]">
            {promptTemplates.map((template) => (
              <Row key={template.id} title={template.name} meta={`${template.version} · ${template.category}`} badge={template.status === "published" ? "已发布" : "草稿"} detail={`已引用：${template.usage}`} actionLabel="添加" />
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={Mail} title="交付能力" description="交付节点引用的能力资产，通道和凭证在系统管理中配置" actionLabel="新增交付能力" />
          <div className="grid gap-3 p-4 md:grid-cols-3">
            {deliveryCapabilities.map((capability) => (
              <article key={capability.id} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                <div className="flex items-center justify-between gap-2">
                  <h3 className="text-sm font-semibold">{capability.name}</h3>
                  <span className={capability.riskLevel === "high" ? "rounded bg-red-100 px-2 py-1 text-xs font-medium text-red-700" : "rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"}>
                    {capability.riskLevel === "high" ? "高" : "中"}
                  </span>
                </div>
                <p className="agent-muted mt-2 text-xs">{capability.version} · {deliveryChannelLabel[capability.channel]}</p>
                <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">{capability.status === "published" ? "可被交付节点引用" : "待发布"}</p>
                <button type="button" className="agent-button mt-3 h-8 px-2.5 text-xs">
                  <Plus className="h-3.5 w-3.5" aria-hidden="true" />
                  添加到交付节点
                </button>
              </article>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

const outputModeLabel: Record<AgentTemplateAsset["outputMode"], string> = {
  once: "一次性输出",
  ask_then_confirm: "追问确认",
  pause_then_continue: "分析后暂停",
};

function AssetMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-3">
      <p className="text-xs text-[var(--color-text-secondary)]">{label}</p>
      <p className="mt-1 text-xl font-semibold">{value}</p>
    </div>
  );
}

function SectionHeader({ icon: Icon, title, description, actionLabel }: { icon: typeof Bot; title: string; description: string; actionLabel?: string }) {
  return (
    <div className="flex flex-col gap-3 px-4 pb-2 pt-4 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <div className="flex items-center gap-2">
          <Icon className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
          <h2 className="text-base font-semibold">{title}</h2>
        </div>
        <p className="agent-muted mt-1 text-sm">{description}</p>
      </div>
      {actionLabel ? (
        <button type="button" className="agent-button h-8 shrink-0 px-2.5 text-xs">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {actionLabel}
        </button>
      ) : null}
    </div>
  );
}

function CreateEntry({ entry }: { entry: AssetCreateEntry }) {
  return (
    <article className="rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{entry.title}</h3>
          <p className="agent-muted mt-2 text-sm leading-6">{entry.detail}</p>
        </div>
        <button type="button" className="agent-button h-8 shrink-0 px-2 text-xs">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {entry.action}
        </button>
      </div>
    </article>
  );
}

const deliveryChannelLabel: Record<DeliveryCapabilityAsset["channel"], string> = {
  document: "文档生成",
  email: "邮件",
  oa: "OA",
  im: "IM",
  webhook: "Webhook",
  database: "数据库",
};

function Row({ title, meta, badge, detail, actionLabel }: { title: string; meta: string; badge: string; detail: string; actionLabel?: string }) {
  return (
    <article className="grid gap-3 px-4 py-3 md:grid-cols-[minmax(0,1fr)_auto_auto] md:items-center">
      <div>
        <h3 className="text-sm font-semibold">{title}</h3>
        <p className="agent-muted mt-1 text-xs">{meta}</p>
        <p className="mt-1 text-xs text-[var(--color-text-tertiary)]">{detail}</p>
      </div>
      <span className="w-fit rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
        {badge}
      </span>
      {actionLabel ? (
        <button type="button" className="agent-button h-8 w-fit px-2.5 text-xs">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {actionLabel}
        </button>
      ) : null}
    </article>
  );
}
