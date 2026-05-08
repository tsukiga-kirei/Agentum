import { Boxes, Building2, DatabaseZap, KeyRound, Mail, ServerCog, Settings2 } from "lucide-react";

type TenantSummary = {
  name: string;
  code: string;
  status: "启用" | "暂停";
  modelScope: string;
  capabilityScope: string;
};

type ModelProvider = {
  name: string;
  provider: string;
  status: "可用" | "待配置";
  tenants: string;
};

type SystemCapability = {
  name: string;
  type: "MCP" | "Skill" | "交付" | "提示词";
  risk: "低" | "中" | "高";
  grant: string;
};

// 系统管理页当前是平台治理视角的静态样例，后续应由租户、模型供应商、全局能力授权 API 替换。
const tenants: TenantSummary[] = [
  { name: "Agentum 演示组织", code: "demo", status: "启用", modelScope: "默认模型池", capabilityScope: "需求 / 报告能力包" },
  { name: "法务试点租户", code: "legal-pilot", status: "启用", modelScope: "低温度审核模型", capabilityScope: "合同审查能力包" },
  { name: "经营分析租户", code: "ops-bi", status: "暂停", modelScope: "待分配", capabilityScope: "只读数据能力包" },
];

const modelProviders: ModelProvider[] = [
  { name: "OpenAI 兼容供应商", provider: "openai-compatible", status: "可用", tenants: "demo、legal-pilot" },
  { name: "本地私有模型", provider: "local", status: "待配置", tenants: "未授权" },
];

// 全局能力先按类型和风险展示治理边界，真实凭证、配额和租户授权不进入前端明文。
const systemCapabilities: SystemCapability[] = [
  { name: "文件读取 MCP", type: "MCP", risk: "中", grant: "授权给 demo / legal-pilot" },
  { name: "邮件发送 MCP", type: "MCP", risk: "高", grant: "仅 demo 测试环境" },
  { name: "需求拆解 Skill", type: "Skill", risk: "低", grant: "全部启用租户" },
  { name: "报告撰写提示词模板", type: "提示词", risk: "低", grant: "demo" },
  { name: "OA 交付能力", type: "交付", risk: "高", grant: "待授权" },
];

export function SystemManagementPage() {
  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_390px] xl:items-center">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">系统管理</p>
            <h2 className="mt-2 text-xl font-semibold">先由平台放出底层能力，再由租户分配具体使用权限</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              当前页面使用静态数据呈现系统管理员视角。后续需要接入租户、模型供应商、全局能力、租户授权和凭证策略 API。
            </p>
          </div>
          <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <ServerCog className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
              平台到租户的授权链路
            </div>
            <p className="agent-muted mt-2 text-sm leading-6">
              系统管理员注册模型、MCP、Skills 和交付能力后，只把可用范围授权给租户；租户管理员再控制部门、角色、智能体和流程能否使用。
            </p>
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_430px]">
        <div className="agent-card">
          <SectionHeader icon={Building2} title="租户管理" description="租户是最高隔离边界，后续承载额度、能力包和数据保留策略" />
          <div className="divide-y divide-[var(--color-border-light)]">
            {tenants.map((tenant) => (
              <article key={tenant.code} className="grid gap-3 px-4 py-4 md:grid-cols-[minmax(0,1fr)_120px] md:items-center">
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-semibold">{tenant.name}</h3>
                    <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                      {tenant.code}
                    </span>
                  </div>
                  <p className="agent-muted mt-2 text-sm">{tenant.modelScope} · {tenant.capabilityScope}</p>
                </div>
                <span className={tenant.status === "启用" ? "w-fit rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800" : "w-fit rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"}>
                  {tenant.status}
                </span>
              </article>
            ))}
          </div>
        </div>

        <div className="agent-card">
          <SectionHeader icon={DatabaseZap} title="模型供应商" description="模型密钥留在服务端，租户只获得可用模型范围" />
          <div className="space-y-3 p-4">
            {modelProviders.map((model) => (
              <article key={model.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">{model.name}</h3>
                  <span className={model.status === "可用" ? "rounded bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-800" : "rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"}>
                    {model.status}
                  </span>
                </div>
                <p className="agent-muted mt-2 text-xs">{model.provider} · 授权：{model.tenants}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="agent-card">
        <SectionHeader icon={Boxes} title="全局能力授权" description="MCP、Skills、提示词模板和交付能力先在系统层注册，再按租户放开" />
        <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-5">
          {systemCapabilities.map((capability) => (
            <article key={capability.name} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                  {capability.type}
                </span>
                <RiskBadge risk={capability.risk} />
              </div>
              <h3 className="mt-3 text-sm font-semibold">{capability.name}</h3>
              <p className="agent-muted mt-2 text-xs leading-5">{capability.grant}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-3">
        <SystemNotice icon={KeyRound} title="凭证策略" detail="生产凭证、测试凭证和租户凭证需要隔离；前端只展示脱敏状态。" />
        <SystemNotice icon={Mail} title="交付通道" detail="邮件、OA、IM、Webhook、数据库写入都应作为交付能力注册并授权。" />
        <SystemNotice icon={Settings2} title="系统参数" detail="数据保留天数、租户额度、并发限制和审计级别应进入系统配置。" />
      </section>
    </div>
  );
}

function SectionHeader({ icon: Icon, title, description }: { icon: typeof Building2; title: string; description: string }) {
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

function RiskBadge({ risk }: { risk: SystemCapability["risk"] }) {
  const className =
    risk === "高"
      ? "bg-red-100 text-red-700"
      : risk === "中"
        ? "bg-amber-100 text-amber-800"
        : "bg-emerald-100 text-emerald-800";

  return <span className={`rounded px-2 py-1 text-xs font-medium ${className}`}>{risk}风险</span>;
}

function SystemNotice({ icon: Icon, title, detail }: { icon: typeof KeyRound; title: string; detail: string }) {
  return (
    <article className="agent-card p-4">
      <div className="flex items-center gap-2">
        <Icon className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
        <h3 className="text-sm font-semibold">{title}</h3>
      </div>
      <p className="agent-muted mt-2 text-sm leading-6">{detail}</p>
    </article>
  );
}
