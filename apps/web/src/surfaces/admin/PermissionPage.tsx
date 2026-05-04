import { Database, Eye, KeyRound, LockKeyhole, ShieldCheck, UserRoundCog } from "lucide-react";

type RolePolicy = {
  role: string;
  scope: string;
  actions: string[];
  sensitiveActions: string[];
};

const rolePolicies: RolePolicy[] = [
  { role: "系统管理员", scope: "全局配置、模型和系统级 MCP", actions: ["read", "create", "update", "delete"], sensitiveActions: ["manage_credential", "view_audit_log"] },
  { role: "空间管理员", scope: "业务空间成员、资产和权限", actions: ["read", "create", "update", "publish"], sensitiveActions: ["manage_permission", "invoke_sensitive_mcp"] },
  { role: "流程设计者", scope: "工作流定义、节点配置和测试运行", actions: ["read", "create", "update", "execute"], sensitiveActions: ["publish"] },
  { role: "审核人", scope: "人工审核节点和待办处理", actions: ["read", "approve"], sensitiveActions: ["download_file"] },
  { role: "执行人", scope: "发起流程、补充输入和查看结果", actions: ["read", "execute"], sensitiveActions: [] },
];

const protectedResources = [
  { type: "Workflow", policy: "发布前校验节点配置、变量引用和敏感交付目标。", owner: "流程设计者" },
  { type: "McpService", policy: "按角色、智能体、工作流和环境控制工具可见性。", owner: "能力管理员" },
  { type: "ToolCredential", policy: "前端只展示脱敏状态，绑定与更新必须审计。", owner: "空间管理员" },
  { type: "AuditLog", policy: "只允许审计角色和管理员查询，敏感参数默认脱敏。", owner: "审计人员" },
];

export function PermissionPage() {
  return (
    <div className="mx-auto max-w-[1400px] space-y-5 px-5 py-6 lg:px-6">
      <section className="agent-card p-5">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px] xl:items-center">
          <div>
            <p className="text-sm font-medium text-[var(--color-primary)]">阶段一：权限前端占位</p>
            <h2 className="mt-2 text-xl font-semibold">把权限、凭证和敏感操作从第一版就显式展示出来</h2>
            <p className="agent-muted mt-3 max-w-3xl text-sm leading-6">
              前端只负责展示权限边界和操作入口，真实鉴权、MCP 调用和凭证读取必须由后端再次校验。
            </p>
          </div>
          <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <LockKeyhole className="h-4 w-4" aria-hidden="true" />
              敏感操作前端不持有最终授权
            </div>
            <p className="mt-2 text-sm">发布、凭证、生产交付、敏感 MCP 均需要后端二次判断。</p>
          </div>
        </div>
      </section>

      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <div className="agent-card">
          <SectionHeader icon={UserRoundCog} title="角色策略" description="RBAC + 资源级权限的前端可视化" />
          <div className="divide-y divide-[var(--color-border-light)]">
            {rolePolicies.map((policy) => (
              <article key={policy.role} className="px-4 py-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <h3 className="text-sm font-semibold">{policy.role}</h3>
                    <p className="agent-muted mt-1 text-xs">{policy.scope}</p>
                  </div>
                  <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                    {policy.actions.length} 个基础动作
                  </span>
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {policy.actions.map((action) => (
                    <Tag key={action}>{action}</Tag>
                  ))}
                  {policy.sensitiveActions.map((action) => (
                    <span key={action} className="rounded bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">{action}</span>
                  ))}
                </div>
              </article>
            ))}
          </div>
        </div>

        <div className="space-y-5">
          <div className="agent-card">
            <SectionHeader icon={KeyRound} title="凭证与 MCP 安全" description="凭证不落前端，调用由网关统一审计" />
            <div className="space-y-3 p-4">
              <SecurityNotice title="凭证状态" detail="mcp_mail_sender 已绑定测试凭证，生产凭证需空间管理员审批。" />
              <SecurityNotice title="参数白名单" detail="邮件 MCP 只允许读取收件人、标题、正文摘要和附件 ID。" />
              <SecurityNotice title="频率限制" detail="高风险 MCP 当前限制 60 次 / 小时，超限需要审批。" />
            </div>
          </div>

          <div className="agent-card">
            <SectionHeader icon={Eye} title="审计可见性" description="根据角色展示证据链，不暴露敏感参数" />
            <div className="p-4">
              <p className="agent-muted rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] p-3 text-sm leading-6">
                审计人员可查看完整操作链路；业务执行人仅查看与自己相关的运行摘要；敏感变量和凭证参数始终脱敏。
              </p>
            </div>
          </div>
        </div>
      </section>

      <section className="agent-card">
        <SectionHeader icon={Database} title="资源保护清单" description="后端权限模块需要覆盖的资源和前端提示文案" />
        <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-4">
          {protectedResources.map((resource) => (
            <article key={resource.type} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
              <h3 className="text-sm font-semibold">{resource.type}</h3>
              <p className="agent-muted mt-2 text-sm leading-6">{resource.policy}</p>
              <p className="mt-3 text-xs text-[var(--color-text-tertiary)]">责任角色：{resource.owner}</p>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}

function SectionHeader({ icon: Icon, title, description }: { icon: typeof ShieldCheck; title: string; description: string }) {
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

function Tag({ children }: { children: string }) {
  return <span className="rounded bg-indigo-100 px-2 py-1 text-xs font-medium text-indigo-800">{children}</span>;
}

function SecurityNotice({ title, detail }: { title: string; detail: string }) {
  return (
    <article className="rounded-[var(--radius-md)] bg-[var(--color-bg-hover)] p-3">
      <h3 className="text-sm font-semibold">{title}</h3>
      <p className="agent-muted mt-2 text-sm leading-6">{detail}</p>
    </article>
  );
}
