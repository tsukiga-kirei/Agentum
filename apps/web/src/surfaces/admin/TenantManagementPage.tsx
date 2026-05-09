import { useEffect, useState } from "react";
import { Button, Form, Input, InputNumber, Modal, Segmented, Select } from "antd";
import { Building2, ClipboardList, Database, Eye, FileText, LockKeyhole, ShieldCheck, UserPlus, UserRoundCog, UsersRound } from "lucide-react";
import { AgentumApiError, organizationApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  CreateDepartmentRequest,
  CreateMemberRequest,
  OrganizationMembership,
  TenantOrganizationOverview,
} from "../../types/organization";

type TenantManagementTabKey = "organization" | "roles" | "resources" | "requirements" | "sensitive" | "audit";

type TenantManagementTab = {
  key: TenantManagementTabKey;
  label: string;
  description: string;
  icon: typeof ShieldCheck;
};

type BusinessAction = {
  label: string;
  code: string;
};

type RolePolicy = {
  role: string;
  scope: string;
  actions: BusinessAction[];
  sensitiveActions: BusinessAction[];
};

type ResourceGrant = {
  resource: string;
  owner: string;
  grants: string;
  rule: string;
};

type SensitivePolicy = {
  action: string;
  owner: string;
  approval: string;
  note: string;
};

const tenantManagementTabs: TenantManagementTab[] = [
  { key: "organization", label: "人员组织", description: "用户、部门和空间成员关系", icon: UsersRound },
  { key: "roles", label: "角色权限", description: "角色、部门、人员的小权限", icon: UserRoundCog },
  { key: "resources", label: "资源授权", description: "具体流程、资产和结果给谁看", icon: Database },
  { key: "requirements", label: "需求配置", description: "表单字段、审核规则和交付目标", icon: ClipboardList },
  { key: "sensitive", label: "敏感动作", description: "高风险调用、外部交付和凭证审批", icon: LockKeyhole },
  { key: "audit", label: "审计可见性", description: "谁能看证据链和脱敏字段", icon: Eye },
];

const organizationItems = [
  { title: "人员管理", detail: "成员状态、所属部门、租户角色、最近登录和禁用入口。", icon: UsersRound },
  { title: "部门管理", detail: "租户内部门树，用于待办分派、审核范围和资源过滤。", icon: Building2 },
  { title: "角色分配", detail: "一个用户可在不同空间拥有不同角色，角色只授予动作能力。", icon: UserRoundCog },
];

const rolePolicies: RolePolicy[] = [
  {
    role: "系统管理员",
    scope: "全局租户、模型、系统级能力和凭证策略",
    actions: [
      { label: "查看", code: "read" },
      { label: "新增", code: "create" },
      { label: "编辑", code: "update" },
      { label: "删除", code: "delete" },
    ],
    sensitiveActions: [
      { label: "管理凭证策略", code: "manage_credential" },
      { label: "查看审计日志", code: "view_audit_log" },
    ],
  },
  {
    role: "租户管理员",
    scope: "租户内成员、部门、空间、资产授权和发布策略",
    actions: [
      { label: "查看", code: "read" },
      { label: "新增", code: "create" },
      { label: "编辑", code: "update" },
      { label: "发布", code: "publish" },
    ],
    sensitiveActions: [
      { label: "分配权限", code: "manage_permission" },
      { label: "审批高风险 MCP", code: "invoke_sensitive_mcp" },
    ],
  },
  {
    role: "流程设计者",
    scope: "工作流定义、节点配置、测试运行和发布申请",
    actions: [
      { label: "查看", code: "read" },
      { label: "新建草稿", code: "create" },
      { label: "编辑配置", code: "update" },
      { label: "测试运行", code: "execute" },
    ],
    sensitiveActions: [{ label: "提交发布", code: "publish" }],
  },
  {
    role: "审核人",
    scope: "人工审核节点、高风险审批和待办处理",
    actions: [
      { label: "查看", code: "read" },
      { label: "审核", code: "approve" },
    ],
    sensitiveActions: [{ label: "下载敏感文件", code: "download_sensitive_file" }],
  },
  {
    role: "执行人",
    scope: "发起流程、补充输入、追问确认和查看授权结果",
    actions: [
      { label: "查看", code: "read" },
      { label: "发起流程", code: "execute" },
    ],
    sensitiveActions: [],
  },
];

const resourceGrants: ResourceGrant[] = [
  { resource: "流程模板", owner: "流程设计者", grants: "按角色、部门或空间授权发起和查看", rule: "只展示已发布且当前用户可发起的模板。" },
  { resource: "智能体模板", owner: "智能体管理员", grants: "授权给流程、设计者或业务空间", rule: "节点引用模板版本，运行时再次校验可用性。" },
  { resource: "运行记录", owner: "流程负责人", grants: "发起人、处理人、负责人、审计角色可见", rule: "业务用户看摘要，审计角色看只读证据链。" },
  { resource: "交付物", owner: "业务负责人", grants: "按用户、部门、角色或外部交付目标授权", rule: "敏感文件下载需要单独动作权限和审计。" },
];

const requirementConfigs = [
  { title: "提单字段", detail: "控制业务用户发起流程时需要填写哪些字段、附件和必填校验。", icon: ClipboardList },
  { title: "审核规则", detail: "按流程类型、部门、金额或风险等级配置租户内审核路径。", icon: ShieldCheck },
  { title: "交付目标", detail: "限制文档、邮件、OA、IM、Webhook 等交付能力在本租户内的使用范围。", icon: FileText },
];

const sensitivePolicies: SensitivePolicy[] = [
  { action: "高风险 MCP 调用", owner: "租户管理员", approval: "需要审批", note: "例如邮件发送、OA 创建、数据库写入；调用前由后端网关复核。" },
  { action: "外部系统交付", owner: "流程负责人", approval: "需要二次确认", note: "交付到邮件、OA、IM、Webhook 前需要记录目标和交付变量。" },
  { action: "凭证绑定与更新", owner: "系统管理员", approval: "系统管理处理", note: "租户管理只控制谁能申请或审批，凭证明文永远不进前端。" },
  { action: "敏感文件下载", owner: "审计 / 租户管理员", approval: "按策略控制", note: "下载动作单独授权，和普通查看运行摘要分开。" },
];

export function TenantManagementPage() {
  // 租户管理页按租户管理员的日常任务组织展示，动作码只作为后端策略标识露出在次级文本中。
  const [activeTab, setActiveTab] = useState<TenantManagementTabKey>("organization");
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const themeMode = useAuthStore((s) => s.themeMode);
  const darkModalClassName = themeMode === "dark" ? "agent-dark-modal" : undefined;
  const [organizationOverview, setOrganizationOverview] = useState<TenantOrganizationOverview | null>(null);
  const [organizationLoading, setOrganizationLoading] = useState(false);
  const [organizationError, setOrganizationError] = useState("");
  const [createMemberOpen, setCreateMemberOpen] = useState(false);
  const [createMemberSubmitting, setCreateMemberSubmitting] = useState(false);
  const [createMemberForm] = Form.useForm<CreateMemberRequest>();
  const [createDepartmentOpen, setCreateDepartmentOpen] = useState(false);
  const [createDepartmentSubmitting, setCreateDepartmentSubmitting] = useState(false);
  const [createDepartmentForm] = Form.useForm<CreateDepartmentRequest>();
  const [membershipUpdatingId, setMembershipUpdatingId] = useState<string | null>(null);
  const activeTabMeta = tenantManagementTabs.find((tab) => tab.key === activeTab) ?? tenantManagementTabs[0];
  const tabSegmentedOptions = tenantManagementTabs.map((tab) => {
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

  useEffect(() => {
    if (activeTab !== "organization" || !token || !user?.tenantId) {
      return;
    }

    let active = true;
    const tenantId = user.tenantId;
    setOrganizationLoading(true);
    setOrganizationError("");

    // 租户管理页只展示租户治理视图；资源归属、角色和敏感动作仍由后端按 token + tenantId 重新判断。
    organizationApi.overview(tenantId, token)
      .then((overview) => {
        if (active) {
          setOrganizationOverview(overview);
        }
      })
      .catch((error) => {
        if (active) {
          console.warn("[tenant-management] 组织概览加载失败", getTenantManagementErrorContext(error, tenantId));
          setOrganizationError(error instanceof AgentumApiError ? error.message : "无法加载人员组织数据");
          setOrganizationOverview(null);
        }
      })
      .finally(() => {
        if (active) {
          setOrganizationLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [activeTab, token, user?.tenantId]);

  async function handleCreateMember(values: CreateMemberRequest) {
    if (!token || !user?.tenantId) {
      console.warn("[tenant-management] 新增成员失败：缺少租户上下文", { hasToken: Boolean(token), userId: user?.id });
      setOrganizationError("当前账号缺少租户上下文，无法新增成员");
      return;
    }

    setCreateMemberSubmitting(true);
    setOrganizationError("");

    try {
      // 初始密码只随创建请求提交，禁止进入日志、localStorage、URL 或错误详情；诊断日志只记录脱敏字段。
      const overview = await organizationApi.createMember(user.tenantId, token, values);
      setOrganizationOverview(overview);
      setCreateMemberOpen(false);
      createMemberForm.resetFields();
    } catch (error) {
      console.warn("[tenant-management] 新增成员失败", getTenantManagementErrorContext(error, user.tenantId, { username: values.username, roleId: values.roleId, departmentId: values.departmentId }));
      setOrganizationError(error instanceof AgentumApiError ? error.message : "新增成员失败，请稍后重试");
    } finally {
      setCreateMemberSubmitting(false);
    }
  }

  async function handleCreateDepartment(values: CreateDepartmentRequest) {
    if (!token || !user?.tenantId) {
      console.warn("[tenant-management] 新增部门失败：缺少租户上下文", { hasToken: Boolean(token), userId: user?.id });
      setOrganizationError("当前账号缺少租户上下文，无法新增部门");
      return;
    }

    setCreateDepartmentSubmitting(true);
    setOrganizationError("");

    try {
      // 前端只提交部门治理动作；上级部门是否属于当前租户必须由后端再次校验。
      const overview = await organizationApi.createDepartment(user.tenantId, token, values);
      setOrganizationOverview(overview);
      setCreateDepartmentOpen(false);
      createDepartmentForm.resetFields();
    } catch (error) {
      console.warn("[tenant-management] 新增部门失败", getTenantManagementErrorContext(error, user.tenantId, { departmentCode: values.code, parentId: values.parentId }));
      setOrganizationError(error instanceof AgentumApiError ? error.message : "新增部门失败，请稍后重试");
    } finally {
      setCreateDepartmentSubmitting(false);
    }
  }

  async function handleUpdateMembershipRole(membership: OrganizationMembership, roleId: string) {
    if (!token || !user?.tenantId) {
      setOrganizationError("当前账号缺少租户上下文，无法调整成员角色");
      return;
    }

    setMembershipUpdatingId(membership.id);
    setOrganizationError("");
    try {
      const overview = await organizationApi.updateMembershipRole(user.tenantId, membership.id, token, { roleId });
      setOrganizationOverview(overview);
    } catch (error) {
      console.warn(
        "[tenant-management] 成员角色调整失败",
        getTenantManagementErrorContext(error, user.tenantId, { membershipId: membership.id, roleId })
      );
      setOrganizationError(error instanceof AgentumApiError ? error.message : "成员角色调整失败，请稍后重试");
    } finally {
      setMembershipUpdatingId(null);
    }
  }

  async function handleUpdateMembershipDepartment(membership: OrganizationMembership, departmentId?: string) {
    if (!token || !user?.tenantId) {
      setOrganizationError("当前账号缺少租户上下文，无法调整成员部门");
      return;
    }

    setMembershipUpdatingId(membership.id);
    setOrganizationError("");
    try {
      const overview = await organizationApi.updateMembershipDepartment(user.tenantId, membership.id, token, { departmentId });
      setOrganizationOverview(overview);
    } catch (error) {
      console.warn(
        "[tenant-management] 成员部门调整失败",
        getTenantManagementErrorContext(error, user.tenantId, { membershipId: membership.id, departmentId })
      );
      setOrganizationError(error instanceof AgentumApiError ? error.message : "成员部门调整失败，请稍后重试");
    } finally {
      setMembershipUpdatingId(null);
    }
  }

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex min-w-0 gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)] bg-[var(--color-primary-bg)] text-[var(--color-primary)] shadow-[var(--shadow-xs)]">
              <ShieldCheck className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg font-semibold tracking-tight text-[var(--color-text-primary)] sm:text-xl">租户管理</h1>
                <span className="rounded-full bg-[var(--color-bg-hover)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
                  租户内治理
                </span>
              </div>
              <p className="agent-muted mt-1.5 max-w-2xl text-sm leading-relaxed">
                左侧菜单决定能否进入租户管理；模块内页签进一步区分组织、权限、资源与审计能力，最终权限仍由后端复核。
              </p>
            </div>
          </div>
        </header>

        <div className="system-mgmt-module-switch mb-5">
          <div className="system-mgmt-segmented-scroll">
            <Segmented<TenantManagementTabKey>
              aria-label="租户管理模块"
              value={activeTab}
              onChange={(key) => setActiveTab(key as TenantManagementTabKey)}
              options={tabSegmentedOptions}
              className="login-portal-segmented login-portal-segmented--tenant_admin system-mgmt-segmented"
            />
          </div>
          <div className="login-portal-description login-portal-description--tenant_admin">
            <span className="login-portal-description-dot" />
            {activeTabMeta.description}
          </div>
        </div>

        <div className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]">

          <div className="p-5">
            <div className="mb-4 rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">
              <div className="flex items-center gap-2 font-semibold">
                <LockKeyhole className="h-4 w-4" aria-hidden="true" />
                页面入口不是安全边界
              </div>
              <p className="mt-2">发布、凭证、外部交付、敏感 MCP 和审计查看都必须由后端再次校验。</p>
            </div>

            {activeTab === "organization" ? (
              <OrganizationPanel
                overview={organizationOverview}
                loading={organizationLoading}
                error={organizationError}
                hasTenantContext={Boolean(user?.tenantId)}
                onCreateMember={() => setCreateMemberOpen(true)}
                onCreateDepartment={() => setCreateDepartmentOpen(true)}
                membershipUpdatingId={membershipUpdatingId}
                onUpdateMembershipRole={handleUpdateMembershipRole}
                onUpdateMembershipDepartment={handleUpdateMembershipDepartment}
              />
            ) : null}
            {activeTab === "roles" ? <RolePolicyPanel /> : null}
            {activeTab === "resources" ? <ResourceGrantPanel /> : null}
            {activeTab === "requirements" ? <RequirementConfigPanel /> : null}
            {activeTab === "sensitive" ? <SensitivePolicyPanel /> : null}
            {activeTab === "audit" ? <AuditVisibilityPanel /> : null}
          </div>
        </div>
      </div>

      <Modal
        title="新增成员"
        rootClassName={darkModalClassName}
        open={createMemberOpen}
        okText="创建成员"
        cancelText="取消"
        confirmLoading={createMemberSubmitting}
        onOk={() => void createMemberForm.submit()}
        onCancel={() => setCreateMemberOpen(false)}
        destroyOnClose
      >
        <Form form={createMemberForm} layout="vertical" onFinish={handleCreateMember} preserve={false}>
          <Form.Item name="displayName" label="成员姓名" rules={[{ required: true, message: "请输入成员姓名" }]}>
            <Input placeholder="例如：张三" />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: "请输入用户名" }]}>
            <Input placeholder="例如：zhangsan" autoComplete="off" />
          </Form.Item>
          <Form.Item name="password" label="初始密码" rules={[{ required: true, message: "请输入初始密码" }, { min: 8, message: "初始密码至少 8 位" }]}>
            <Input.Password placeholder="至少 8 位" autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="email" label="邮箱">
            <Input placeholder="name@example.com" />
          </Form.Item>
          <Form.Item name="departmentId" label="部门">
            <Select
              allowClear
              placeholder="可选"
              options={(organizationOverview?.departments ?? []).map((department) => ({ value: department.id, label: department.name }))}
            />
          </Form.Item>
          <Form.Item name="roleId" label="角色" rules={[{ required: true, message: "请选择角色" }]}>
            <Select
              placeholder="请选择角色"
              options={(organizationOverview?.roles ?? []).map((role) => ({ value: role.id, label: `${role.name} (${role.code})` }))}
            />
          </Form.Item>
          <Form.Item name="spaceCode" label="空间">
            <Input placeholder="默认空间" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新增部门"
        rootClassName={darkModalClassName}
        open={createDepartmentOpen}
        okText="创建部门"
        cancelText="取消"
        confirmLoading={createDepartmentSubmitting}
        onOk={() => void createDepartmentForm.submit()}
        onCancel={() => setCreateDepartmentOpen(false)}
        destroyOnClose
      >
        <Form form={createDepartmentForm} layout="vertical" onFinish={handleCreateDepartment} preserve={false}>
          <Form.Item name="name" label="部门名称" rules={[{ required: true, message: "请输入部门名称" }]}>
            <Input placeholder="例如：风控部" />
          </Form.Item>
          <Form.Item name="code" label="部门编码">
            <Input placeholder="例如：risk" />
          </Form.Item>
          <Form.Item name="parentId" label="上级部门">
            <Select
              allowClear
              placeholder="不选择则为一级部门"
              options={(organizationOverview?.departments ?? []).map((department) => ({ value: department.id, label: department.name }))}
            />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber className="w-full" min={0} placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function OrganizationPanel({
  overview,
  loading,
  error,
  hasTenantContext,
  onCreateMember,
  onCreateDepartment,
  membershipUpdatingId,
  onUpdateMembershipRole,
  onUpdateMembershipDepartment,
}: {
  overview: TenantOrganizationOverview | null;
  loading: boolean;
  error: string;
  hasTenantContext: boolean;
  onCreateMember: () => void;
  onCreateDepartment: () => void;
  membershipUpdatingId: string | null;
  onUpdateMembershipRole: (membership: OrganizationMembership, roleId: string) => void;
  onUpdateMembershipDepartment: (membership: OrganizationMembership, departmentId?: string) => void;
}) {
  if (!hasTenantContext) {
    return (
      <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 text-sm text-[var(--color-text-secondary)]">
        系统管理员需要先在系统管理中选择目标租户，才能查看租户内人员组织。
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 md:grid-cols-3">
        {organizationItems.map((item) => {
          const Icon = item.icon;

          return (
            <article key={item.title} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
              <div className="flex items-center gap-2">
                <Icon className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
                <h3 className="text-sm font-semibold">{item.title}</h3>
              </div>
              <p className="agent-muted mt-2 text-sm leading-6">{item.detail}</p>
            </article>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold">人员组织维护</h3>
          <p className="agent-muted mt-1 text-xs">新增成员和部门都会由后端再次校验租户归属，前端只负责提交治理动作。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="default" icon={<Building2 className="h-4 w-4" aria-hidden="true" />} onClick={onCreateDepartment} disabled={!overview}>
            新增部门
          </Button>
          <Button type="primary" icon={<UserPlus className="h-4 w-4" aria-hidden="true" />} onClick={onCreateMember} disabled={!overview}>
            新增成员
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] p-4 text-sm text-[var(--color-text-secondary)]">正在加载人员组织数据…</div>
      ) : null}

      {error ? (
        <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">{error}</div>
      ) : null}

      {overview ? (
        <div className="grid gap-3 xl:grid-cols-[1fr_1fr]">
          <section className="rounded-[var(--radius-md)] border border-[var(--color-border-light)]">
            <div className="border-b border-[var(--color-border-light)] px-4 py-3">
              <h3 className="text-sm font-semibold">{overview.tenantName} 成员</h3>
              <p className="agent-muted mt-1 text-xs">{overview.members.length} 人，{overview.departments.length} 个部门，{overview.roles.length} 个角色</p>
            </div>
            <div className="divide-y divide-[var(--color-border-light)]">
              {overview.members.map((member) => (
                <div key={member.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div>
                    <p className="text-sm font-medium">{member.displayName}</p>
                    <p className="agent-muted mt-1 text-xs">{member.username} · {member.email || "未填写邮箱"}</p>
                  </div>
                  <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">{member.status}</span>
                </div>
              ))}
            </div>
          </section>

          <section className="rounded-[var(--radius-md)] border border-[var(--color-border-light)]">
            <div className="border-b border-[var(--color-border-light)] px-4 py-3">
              <h3 className="text-sm font-semibold">成员关系</h3>
              <p className="agent-muted mt-1 text-xs">后端按租户、部门、空间和角色返回真实关系</p>
            </div>
            <div className="divide-y divide-[var(--color-border-light)]">
              {overview.memberships.map((membership) => (
                <div key={membership.id} className="px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-medium">{membership.userDisplayName}</p>
                    <span className="rounded bg-indigo-50 px-2 py-1 text-xs text-indigo-700 ring-1 ring-indigo-100 dark:bg-indigo-950/40 dark:text-indigo-200 dark:ring-indigo-900/70">
                      {membership.roleName || membership.roleCode}
                    </span>
                  </div>
                  <p className="agent-muted mt-1 text-xs">{membership.departmentName || "未分配部门"} · {membership.spaceCode}</p>
                  <div className="mt-2 grid gap-2 sm:grid-cols-2">
                    <Select
                      size="small"
                      value={membership.roleId}
                      disabled={membershipUpdatingId === membership.id}
                      options={overview.roles.map((role) => ({ value: role.id, label: `${role.name} (${role.code})` }))}
                      onChange={(roleId) => onUpdateMembershipRole(membership, roleId)}
                    />
                    <Select
                      size="small"
                      value={membership.departmentId ?? undefined}
                      allowClear
                      disabled={membershipUpdatingId === membership.id}
                      placeholder="未分配部门"
                      options={overview.departments.map((department) => ({ value: department.id, label: department.name }))}
                      onChange={(departmentId) => onUpdateMembershipDepartment(membership, departmentId)}
                    />
                  </div>
                </div>
              ))}
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function getTenantManagementErrorContext(error: unknown, tenantId: string, extra?: Record<string, string | undefined>) {
  if (error instanceof AgentumApiError) {
    return { tenantId, code: error.code, requestId: error.requestId, ...extra };
  }

  return { tenantId, message: error instanceof Error ? error.message : "unknown", ...extra };
}

function RolePolicyPanel() {
  return (
    <div className="divide-y divide-[var(--color-border-light)] rounded-[var(--radius-md)] border border-[var(--color-border-light)]">
      {rolePolicies.map((policy) => (
        <article key={policy.role} className="px-4 py-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold">{policy.role}</h3>
              <p className="agent-muted mt-1 text-xs">{policy.scope}</p>
            </div>
            <span className="rounded bg-[var(--color-bg-hover)] px-2 py-1 text-xs text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">
              {policy.actions.length + policy.sensitiveActions.length} 项权限
            </span>
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            {policy.actions.map((action) => (
              <ActionTag key={action.code} action={action} />
            ))}
            {policy.sensitiveActions.map((action) => (
              <ActionTag key={action.code} action={action} sensitive />
            ))}
          </div>
        </article>
      ))}
    </div>
  );
}

function ResourceGrantPanel() {
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {resourceGrants.map((resource) => (
        <article key={resource.resource} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
          <div className="flex items-center gap-2">
            <Database className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
            <h3 className="text-sm font-semibold">{resource.resource}</h3>
          </div>
          <p className="agent-muted mt-2 text-sm leading-6">{resource.grants}</p>
          <p className="mt-2 text-xs text-[var(--color-text-tertiary)]">{resource.rule}</p>
          <p className="mt-3 text-xs text-[var(--color-text-tertiary)]">责任角色：{resource.owner}</p>
        </article>
      ))}
    </div>
  );
}

function RequirementConfigPanel() {
  return (
    <div className="grid gap-3 md:grid-cols-3">
      {requirementConfigs.map((config) => {
        const Icon = config.icon;

        return (
          <article key={config.title} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
            <div className="flex items-center gap-2">
              <Icon className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
              <h3 className="text-sm font-semibold">{config.title}</h3>
            </div>
            <p className="agent-muted mt-2 text-sm leading-6">{config.detail}</p>
          </article>
        );
      })}
    </div>
  );
}

function SensitivePolicyPanel() {
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {sensitivePolicies.map((policy) => (
        <article key={policy.action} className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
          <div className="flex items-center gap-2">
            <LockKeyhole className="h-4 w-4 text-amber-600" aria-hidden="true" />
            <h3 className="text-sm font-semibold">{policy.action}</h3>
          </div>
          <p className="agent-muted mt-2 text-sm leading-6">{policy.note}</p>
          <div className="mt-3 flex flex-wrap gap-2 text-xs">
            <span className="rounded bg-[var(--color-bg-card)] px-2 py-1 text-[var(--color-text-secondary)] ring-1 ring-[var(--color-border-light)]">责任：{policy.owner}</span>
            <span className="rounded bg-amber-100 px-2 py-1 font-medium text-amber-800">{policy.approval}</span>
          </div>
        </article>
      ))}
    </div>
  );
}

function AuditVisibilityPanel() {
  return (
    <div className="grid gap-3 md:grid-cols-2">
      <article className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
        <div className="flex items-center gap-2">
          <Eye className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
          <h3 className="text-sm font-semibold">业务用户</h3>
        </div>
        <p className="agent-muted mt-2 text-sm leading-6">只查看与自己相关的运行摘要、待办状态和被授权交付物。</p>
      </article>
      <article className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-3">
        <div className="flex items-center gap-2">
          <FileText className="h-4 w-4 text-[var(--color-primary)]" aria-hidden="true" />
          <h3 className="text-sm font-semibold">审计人员</h3>
        </div>
        <p className="agent-muted mt-2 text-sm leading-6">查看只读证据链、节点快照、MCP 调用摘要和交付记录，敏感字段默认脱敏。</p>
      </article>
    </div>
  );
}

function ActionTag({ action, sensitive = false }: { action: BusinessAction; sensitive?: boolean }) {
  const className = sensitive ? "bg-amber-100 text-amber-800" : "bg-indigo-100 text-indigo-800";

  return (
    <span className={`rounded px-2 py-1 text-xs font-medium ${className}`} title={action.code}>
      {action.label}
    </span>
  );
}
