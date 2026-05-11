import { useCallback, useEffect, useMemo, useState } from "react";
import { Empty, Segmented, Select, message } from "antd";
import {
  Building2,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  Code2,
  Database,
  Edit,
  Eye,
  Info,
  LockKeyhole,
  PlusCircle,
  Save,
  Search,
  ShieldCheck,
  Tag,
  Type,
  UserPlus,
  UserRoundCog,
  UsersRound,
  X,
} from "lucide-react";
import { AgentumApiError, organizationApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import type {
  CreateDepartmentRequest,
  CreateMemberRequest,
  CreateTenantOrgRoleRequest,
  OrganizationMembership,
  TenantOrgRole,
  TenantOrganizationOverview,
  TenantResourceOption,
  UpdateTenantOrgRoleRequest,
} from "../../types/organization";

type TenantManagementTabKey = "organization" | "resources";

type TenantManagementTab = {
  key: TenantManagementTabKey;
  label: string;
  description: string;
  icon: typeof ShieldCheck;
};

const tenantManagementTabs: TenantManagementTab[] = [
  { key: "organization", label: "人员组织", description: "用户、部门和空间成员关系", icon: UsersRound },
  { key: "resources", label: "资源授权", description: "按角色、人员、部门授权可用功能", icon: UserRoundCog },
];

const pagePermissionOptions = [
  { value: "workbench", label: "业务工作台", description: "待办、发起流程和运行摘要", icon: ClipboardList },
  { value: "designer", label: "流程设计", description: "草稿、画布和节点配置", icon: Code2 },
  { value: "assets", label: "能力资产", description: "智能体、Skill、MCP 和交付能力", icon: Database },
  { value: "audit", label: "运行审计", description: "只读证据链、变量快照和交付记录", icon: Eye },
];

const emptyMemberForm: CreateMemberRequest = {
  displayName: "",
  username: "",
  password: "",
  email: "",
  roleId: "",
  departmentId: undefined,
  spaceCode: "默认空间",
};

const emptyDepartmentForm: CreateDepartmentRequest = {
  name: "",
  code: "",
  parentId: undefined,
  sortOrder: 0,
};

const emptyOrgRoleForm: CreateTenantOrgRoleRequest & { status: "active" | "disabled" } = {
  name: "",
  description: "",
  pagePermissions: ["workbench"],
  resourcePermissions: [],
  status: "active",
};

type MemberEditDraft = {
  departmentId?: string;
  roleId: string;
  status: "active" | "disabled";
};

export function TenantManagementPage() {
  // 租户管理页按租户管理员的日常任务组织展示，动作码只作为后端策略标识露出在次级文本中。
  const [activeTab, setActiveTab] = useState<TenantManagementTabKey>("organization");
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const [messageApi, messageContextHolder] = message.useMessage();
  const [organizationOverview, setOrganizationOverview] = useState<TenantOrganizationOverview | null>(null);
  const [organizationLoading, setOrganizationLoading] = useState(false);
  const [organizationError, setOrganizationError] = useState("");
  const [createMemberOpen, setCreateMemberOpen] = useState(false);
  const [createMemberSubmitting, setCreateMemberSubmitting] = useState(false);
  const [memberDraft, setMemberDraft] = useState<CreateMemberRequest>(emptyMemberForm);
  const [createDepartmentOpen, setCreateDepartmentOpen] = useState(false);
  const [createDepartmentSubmitting, setCreateDepartmentSubmitting] = useState(false);
  const [departmentDraft, setDepartmentDraft] = useState<CreateDepartmentRequest>(emptyDepartmentForm);
  const [membershipUpdatingId, setMembershipUpdatingId] = useState<string | null>(null);
  const [editMemberOpen, setEditMemberOpen] = useState(false);
  const [editingMembership, setEditingMembership] = useState<OrganizationMembership | null>(null);
  const [memberEditDraft, setMemberEditDraft] = useState<MemberEditDraft>({ departmentId: undefined, roleId: "", status: "active" });
  const [memberEditSubmitting, setMemberEditSubmitting] = useState(false);
  const [orgRoles, setOrgRoles] = useState<TenantOrgRole[]>([]);
  const [resourceOptions, setResourceOptions] = useState<TenantResourceOption[]>([]);
  const [orgRolePage, setOrgRolePage] = useState(1);
  const [orgRoleTotalPages, setOrgRoleTotalPages] = useState(1);
  const [orgRoleTotal, setOrgRoleTotal] = useState(0);
  const [orgRoleLoading, setOrgRoleLoading] = useState(false);
  const [orgRoleError, setOrgRoleError] = useState("");
  const [orgRoleModalOpen, setOrgRoleModalOpen] = useState(false);
  const [editingOrgRole, setEditingOrgRole] = useState<TenantOrgRole | null>(null);
  const [orgRoleSubmitting, setOrgRoleSubmitting] = useState(false);
  const [orgRoleDraft, setOrgRoleDraft] = useState<CreateTenantOrgRoleRequest & { status: "active" | "disabled" }>(emptyOrgRoleForm);
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

  const loadOrgRoles = useCallback(async (page = 1) => {
    if (!token || !user?.tenantId) return;

    setOrgRoleLoading(true);
    setOrgRoleError("");

    try {
      const result = await organizationApi.listOrgRoles(user.tenantId, token, page, 6);
      setOrgRoles(result.items);
      setOrgRolePage(result.page);
      setOrgRoleTotal(result.total);
      setOrgRoleTotalPages(Math.max(result.totalPages, 1));
    } catch (error) {
      console.warn("[tenant-management] 租户角色列表加载失败", getTenantManagementErrorContext(error, user.tenantId));
      setOrgRoleError(error instanceof AgentumApiError ? error.message : "无法加载资源授权数据");
      setOrgRoles([]);
    } finally {
      setOrgRoleLoading(false);
    }
  }, [token, user?.tenantId]);

  const loadResourceOptions = useCallback(async () => {
    if (!token || !user?.tenantId) return;

    try {
      setResourceOptions(await organizationApi.listResourceOptions(user.tenantId, token));
    } catch (error) {
      console.warn("[tenant-management] 租户可授权资源加载失败", getTenantManagementErrorContext(error, user.tenantId));
      setResourceOptions([]);
    }
  }, [token, user?.tenantId]);

  useEffect(() => {
    if (activeTab !== "organization" || !token || !user?.tenantId) {
      return;
    }

    let active = true;
    const tenantId = user.tenantId;
    setOrganizationLoading(true);
    setOrganizationError("");

    // 租户管理页只展示当前阶段要做的治理视图；人员、部门和资源授权仍由后端按 token + tenantId 重新判断。
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

  useEffect(() => {
    if (activeTab !== "resources") return;
    void loadOrgRoles(1);
    void loadResourceOptions();
  }, [activeTab, loadOrgRoles, loadResourceOptions]);

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
      setMemberDraft(emptyMemberForm);
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
      setDepartmentDraft(emptyDepartmentForm);
    } catch (error) {
      console.warn("[tenant-management] 新增部门失败", getTenantManagementErrorContext(error, user.tenantId, { departmentCode: values.code, parentId: values.parentId }));
      setOrganizationError(error instanceof AgentumApiError ? error.message : "新增部门失败，请稍后重试");
    } finally {
      setCreateDepartmentSubmitting(false);
    }
  }

  function openEditMemberModal(membership: OrganizationMembership) {
    setEditingMembership(membership);
    setMemberEditDraft({
      departmentId: membership.departmentId ?? undefined,
      roleId: membership.roleId,
      status: membership.status === "disabled" ? "disabled" : "active",
    });
    setEditMemberOpen(true);
  }

  async function handleSubmitMemberEdit() {
    if (!editingMembership) {
      return;
    }

    if (!memberEditDraft.roleId) {
      messageApi.warning("请选择成员角色");
      return;
    }

    if (!token || !user?.tenantId) {
      setOrganizationError("当前账号缺少租户上下文，无法编辑成员");
      return;
    }

    const originalDepartmentId = editingMembership.departmentId ?? undefined;
    const departmentChanged = originalDepartmentId !== memberEditDraft.departmentId;
    const roleChanged = editingMembership.roleId !== memberEditDraft.roleId;
    const statusChanged = editingMembership.status !== memberEditDraft.status;

    if (!departmentChanged && !roleChanged && !statusChanged) {
      setEditMemberOpen(false);
      setEditingMembership(null);
      return;
    }

    setMemberEditSubmitting(true);
    setMembershipUpdatingId(editingMembership.id);
    setOrganizationError("");

    try {
      let nextOverview = organizationOverview;

      // 成员编辑是租户内权限动作；前端拆成部门和角色两个已有接口提交，后端仍按租户重新校验归属。
      if (departmentChanged) {
        nextOverview = await organizationApi.updateMembershipDepartment(
          user.tenantId,
          editingMembership.id,
          token,
          { departmentId: memberEditDraft.departmentId }
        );
        setOrganizationOverview(nextOverview);
      }

      if (roleChanged) {
        nextOverview = await organizationApi.updateMembershipRole(
          user.tenantId,
          editingMembership.id,
          token,
          { roleId: memberEditDraft.roleId }
        );
        setOrganizationOverview(nextOverview);
      }

      if (statusChanged) {
        nextOverview = await organizationApi.updateMembershipStatus(
          user.tenantId,
          editingMembership.id,
          token,
          { status: memberEditDraft.status }
        );
        setOrganizationOverview(nextOverview);
      }

      messageApi.success("成员信息已更新");
      setEditMemberOpen(false);
      setEditingMembership(null);
    } catch (error) {
      console.warn(
        "[tenant-management] 成员编辑失败",
        getTenantManagementErrorContext(error, user.tenantId, {
          membershipId: editingMembership.id,
          roleId: memberEditDraft.roleId,
          departmentId: memberEditDraft.departmentId,
          status: memberEditDraft.status,
        })
      );
      setOrganizationError(error instanceof AgentumApiError ? error.message : "成员编辑失败，请稍后重试");
    } finally {
      setMemberEditSubmitting(false);
      setMembershipUpdatingId(null);
    }
  }

  function openCreateOrgRoleModal() {
    setEditingOrgRole(null);
    setOrgRoleDraft(emptyOrgRoleForm);
    setOrgRoleModalOpen(true);
  }

  function openEditOrgRoleModal(role: TenantOrgRole) {
    setEditingOrgRole(role);
    setOrgRoleDraft({
      name: role.name,
      description: role.description,
      pagePermissions: role.pagePermissions,
      resourcePermissions: (role.resourcePermissions ?? []).map((permission) => ({
        resourceType: permission.resourceType,
        resourceId: permission.resourceId,
        actions: permission.actions.length > 0 ? permission.actions : ["use"],
      })),
      status: role.status === "disabled" ? "disabled" : "active",
    });
    setOrgRoleModalOpen(true);
  }

  async function handleSubmitOrgRole() {
    if (!token || !user?.tenantId) {
      setOrgRoleError("当前账号缺少租户上下文，无法保存租户角色");
      return;
    }

    if (!orgRoleDraft.name.trim()) {
      messageApi.warning("请输入角色名称");
      return;
    }

    if (orgRoleDraft.pagePermissions.length === 0) {
      messageApi.warning("请至少选择一个页面权限");
      return;
    }

    setOrgRoleSubmitting(true);
    setOrgRoleError("");

    try {
      if (editingOrgRole) {
        await organizationApi.updateOrgRole(user.tenantId, editingOrgRole.id, token, orgRoleDraft as UpdateTenantOrgRoleRequest);
        messageApi.success("资源授权已更新");
      } else {
        await organizationApi.createOrgRole(user.tenantId, token, {
          name: orgRoleDraft.name,
          description: orgRoleDraft.description,
          pagePermissions: orgRoleDraft.pagePermissions,
          resourcePermissions: orgRoleDraft.resourcePermissions,
        });
        messageApi.success("已新增资源授权角色");
      }
      setOrgRoleModalOpen(false);
      setEditingOrgRole(null);
      await loadOrgRoles(editingOrgRole ? orgRolePage : 1);
    } catch (error) {
      console.warn("[tenant-management] 租户角色保存失败", getTenantManagementErrorContext(error, user.tenantId, { roleId: editingOrgRole?.id }));
      setOrgRoleError(error instanceof AgentumApiError ? error.message : "租户角色保存失败，请稍后重试");
    } finally {
      setOrgRoleSubmitting(false);
    }
  }

  function toggleOrgRolePermission(permission: string) {
    setOrgRoleDraft((draft) => {
      const exists = draft.pagePermissions.includes(permission);
      return {
        ...draft,
        pagePermissions: exists
          ? draft.pagePermissions.filter((item) => item !== permission)
          : [...draft.pagePermissions, permission],
      };
    });
  }

  function toggleResourcePermission(option: TenantResourceOption) {
    setOrgRoleDraft((draft) => {
      const exists = draft.resourcePermissions.some(
        (permission) => permission.resourceType === option.resourceType && permission.resourceId === option.resourceId
      );
      return {
        ...draft,
        resourcePermissions: exists
          ? draft.resourcePermissions.filter((permission) => permission.resourceType !== option.resourceType || permission.resourceId !== option.resourceId)
          : [...draft.resourcePermissions, { resourceType: option.resourceType, resourceId: option.resourceId, actions: ["use"] }],
      };
    });
  }

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-[var(--color-bg-page)] pb-10 pt-1">
      {messageContextHolder}
      <div className="mx-auto max-w-[1400px] px-5 lg:px-6">
        <header className="mb-5 flex flex-col gap-4 border-b border-[var(--color-border-light)] pb-5 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex min-w-0 gap-4">
            <div className="tenant-mgmt-page-mark flex h-12 w-12 shrink-0 items-center justify-center rounded-[var(--radius-lg)]">
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
                左侧菜单决定能否进入租户管理；当前先聚焦人员组织和资源授权，业务运行日志后续会在运行审计中独立呈现。
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
            <div className="sys-hint mb-4">
              <LockKeyhole size={14} />
              页面入口不是安全边界。发布、凭证、外部交付、敏感 MCP 和审计查看都必须由后端再次校验。
            </div>

            {activeTab === "organization" ? (
              <OrganizationPanel
                overview={organizationOverview}
                loading={organizationLoading}
                error={organizationError}
                hasTenantContext={Boolean(user?.tenantId)}
                onCreateMember={() => { setMemberDraft(emptyMemberForm); setCreateMemberOpen(true); }}
                onCreateDepartment={() => { setDepartmentDraft(emptyDepartmentForm); setCreateDepartmentOpen(true); }}
                membershipUpdatingId={membershipUpdatingId}
                onEditMembership={openEditMemberModal}
              />
            ) : null}
            {activeTab === "resources" ? (
              <ResourceAuthorizationPanel
                roles={orgRoles}
                loading={orgRoleLoading}
                error={orgRoleError}
                total={orgRoleTotal}
                page={orgRolePage}
                totalPages={orgRoleTotalPages}
                onCreate={openCreateOrgRoleModal}
                onEdit={openEditOrgRoleModal}
                onPageChange={(page) => void loadOrgRoles(page)}
              />
            ) : null}
          </div>
        </div>
      </div>

      {createMemberOpen && (
        <div className="sys-modal-mask" onClick={() => setCreateMemberOpen(false)}>
          <div className="sys-modal" style={{ maxWidth: 640 }} onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">新增成员</span>
              <button className="sys-modal-close" onClick={() => setCreateMemberOpen(false)}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-hint"><Info size={14} /> 初始密码只随本次请求提交，前端不会写入日志、URL 或本地缓存。</div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">成员姓名</label>
                  <div className="sys-field-input-wrap"><Type size={16} className="sys-field-prefix" /><input className="sys-field-input" value={memberDraft.displayName} placeholder="例如：张三" onChange={(event) => setMemberDraft((draft) => ({ ...draft, displayName: event.target.value }))} /></div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">用户名</label>
                  <div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix" /><input className="sys-field-input" value={memberDraft.username} placeholder="例如：zhangsan" autoComplete="off" onChange={(event) => setMemberDraft((draft) => ({ ...draft, username: event.target.value }))} /></div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">初始密码</label>
                  <div className="sys-field-input-wrap"><LockKeyhole size={16} className="sys-field-prefix" /><input className="sys-field-input" type="password" value={memberDraft.password} placeholder="至少 8 位" autoComplete="new-password" onChange={(event) => setMemberDraft((draft) => ({ ...draft, password: event.target.value }))} /></div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">邮箱</label>
                  <div className="sys-field-input-wrap"><UserPlus size={16} className="sys-field-prefix" /><input className="sys-field-input" value={memberDraft.email ?? ""} placeholder="name@example.com" onChange={(event) => setMemberDraft((draft) => ({ ...draft, email: event.target.value }))} /></div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">部门</label>
                  <Select
                    allowClear
                    className="w-full"
                    placeholder="可选"
                    value={memberDraft.departmentId}
                    options={(organizationOverview?.departments ?? []).map((department) => ({ value: department.id, label: department.name }))}
                    onChange={(departmentId) => setMemberDraft((draft) => ({ ...draft, departmentId }))}
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">角色</label>
                  <Select
                    className="w-full"
                    placeholder="请选择角色"
                    value={memberDraft.roleId || undefined}
                    options={(organizationOverview?.roles ?? []).map((role) => ({ value: role.id, label: `${role.name} (${role.code})` }))}
                    onChange={(roleId) => setMemberDraft((draft) => ({ ...draft, roleId }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">空间</label>
                <div className="sys-field-input-wrap"><Building2 size={16} className="sys-field-prefix" /><input className="sys-field-input" value={memberDraft.spaceCode ?? ""} placeholder="默认空间" onChange={(event) => setMemberDraft((draft) => ({ ...draft, spaceCode: event.target.value }))} /></div>
              </div>
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={() => setCreateMemberOpen(false)}><X size={14} /> 取消</button>
              <button className="sys-btn sys-btn--primary" disabled={createMemberSubmitting} onClick={() => void handleCreateMember(memberDraft)}><PlusCircle size={14} /> 创建成员</button>
            </div>
          </div>
        </div>
      )}

      {editMemberOpen && editingMembership && (
        <div className="sys-modal-mask" onClick={() => setEditMemberOpen(false)}>
          <div className="sys-modal" style={{ maxWidth: 560 }} onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">编辑成员</span>
              <button className="sys-modal-close" onClick={() => setEditMemberOpen(false)}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className="tenant-member-edit-summary">
                <span className="tenant-member-avatar">{editingMembership.userDisplayName.slice(0, 1)}</span>
                <div>
                  <strong>{editingMembership.userDisplayName}</strong>
                  <span>{organizationOverview?.members.find((member) => member.id === editingMembership.userId)?.username ?? "未找到账号"}</span>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">部门</label>
                  <Select
                    allowClear
                    className="w-full"
                    placeholder="未分配部门"
                    value={memberEditDraft.departmentId}
                    options={(organizationOverview?.departments ?? []).map((department) => ({ value: department.id, label: department.name }))}
                    onChange={(departmentId) => setMemberEditDraft((draft) => ({ ...draft, departmentId }))}
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">角色</label>
                  <Select
                    className="w-full"
                    placeholder="请选择角色"
                    value={memberEditDraft.roleId || undefined}
                    options={(organizationOverview?.roles ?? []).map((role) => ({ value: role.id, label: `${role.name} (${role.code})` }))}
                    onChange={(roleId) => setMemberEditDraft((draft) => ({ ...draft, roleId }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">空间</label>
                <div className="sys-readonly-field">{editingMembership.spaceCode}</div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">状态</label>
                <Select
                  className="w-full"
                  value={memberEditDraft.status}
                  options={[{ value: "active", label: "启用" }, { value: "disabled", label: "禁用" }]}
                  onChange={(status) => setMemberEditDraft((draft) => ({ ...draft, status }))}
                />
              </div>
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={() => setEditMemberOpen(false)}><X size={14} /> 取消</button>
              <button className="sys-btn sys-btn--primary" disabled={memberEditSubmitting} onClick={() => void handleSubmitMemberEdit()}><Save size={14} /> 保存成员</button>
            </div>
          </div>
        </div>
      )}

      {createDepartmentOpen && (
        <div className="sys-modal-mask" onClick={() => setCreateDepartmentOpen(false)}>
          <div className="sys-modal" style={{ maxWidth: 560 }} onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">新增部门</span>
              <button className="sys-modal-close" onClick={() => setCreateDepartmentOpen(false)}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">部门名称</label>
                  <div className="sys-field-input-wrap"><Building2 size={16} className="sys-field-prefix" /><input className="sys-field-input" value={departmentDraft.name} placeholder="例如：风控部" onChange={(event) => setDepartmentDraft((draft) => ({ ...draft, name: event.target.value }))} /></div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">部门编码</label>
                  <div className="sys-field-input-wrap"><Code2 size={16} className="sys-field-prefix" /><input className="sys-field-input" value={departmentDraft.code ?? ""} placeholder="例如：risk" onChange={(event) => setDepartmentDraft((draft) => ({ ...draft, code: event.target.value }))} /></div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">上级部门</label>
                  <Select
                    allowClear
                    className="w-full"
                    placeholder="不选择则为一级部门"
                    value={departmentDraft.parentId}
                    options={(organizationOverview?.departments ?? []).map((department) => ({ value: department.id, label: department.name }))}
                    onChange={(parentId) => setDepartmentDraft((draft) => ({ ...draft, parentId }))}
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">排序</label>
                  <div className="sys-field-input-wrap"><Tag size={16} className="sys-field-prefix" /><input className="sys-field-input" type="number" min={0} value={departmentDraft.sortOrder ?? 0} onChange={(event) => setDepartmentDraft((draft) => ({ ...draft, sortOrder: Number(event.target.value) }))} /></div>
                </div>
              </div>
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={() => setCreateDepartmentOpen(false)}><X size={14} /> 取消</button>
              <button className="sys-btn sys-btn--primary" disabled={createDepartmentSubmitting} onClick={() => void handleCreateDepartment(departmentDraft)}><PlusCircle size={14} /> 创建部门</button>
            </div>
          </div>
        </div>
      )}

      {orgRoleModalOpen && (
        <div className="sys-modal-mask" onClick={() => setOrgRoleModalOpen(false)}>
          <div className="sys-modal" style={{ maxWidth: 680 }} onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">{editingOrgRole ? "编辑资源授权" : "新增授权角色"}</span>
              <button className="sys-modal-close" onClick={() => setOrgRoleModalOpen(false)}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">角色名称</label>
                  <div className="sys-field-input-wrap"><UserRoundCog size={16} className="sys-field-prefix" /><input className="sys-field-input" value={orgRoleDraft.name} placeholder="例如：流程设计者" onChange={(event) => setOrgRoleDraft((draft) => ({ ...draft, name: event.target.value }))} /></div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">状态</label>
                  <Select
                    className="w-full"
                    value={orgRoleDraft.status}
                    options={[{ value: "active", label: "启用" }, { value: "disabled", label: "停用" }]}
                    onChange={(status) => setOrgRoleDraft((draft) => ({ ...draft, status }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">角色说明</label>
                <textarea className="sys-field-textarea" value={orgRoleDraft.description ?? ""} placeholder="说明这个角色适用的人群和业务边界" onChange={(event) => setOrgRoleDraft((draft) => ({ ...draft, description: event.target.value }))} />
              </div>
              <div className="sys-config-group">
                <div className="sys-config-group-title">页面权限</div>
                <div className="tenant-permission-grid">
                  {pagePermissionOptions.map((option) => {
                    const Icon = option.icon;
                    const checked = orgRoleDraft.pagePermissions.includes(option.value);
                    return (
                      <button
                        key={option.value}
                        type="button"
                        className={`tenant-permission-option ${checked ? "tenant-permission-option--checked" : ""}`}
                        onClick={() => toggleOrgRolePermission(option.value)}
                      >
                        <span className="tenant-permission-option-icon"><Icon size={16} /></span>
                        <span className="tenant-permission-option-text">
                          <span>{option.label}</span>
                          <small>{option.description}</small>
                        </span>
                        <CheckCircle2 size={16} className="tenant-permission-option-check" />
                      </button>
                    );
                  })}
                </div>
              </div>
              <div className="sys-config-group">
                <div className="sys-config-group-title">能力资源</div>
                {resourceOptions.length === 0 ? (
                  <div className="sys-hint">
                    <Info size={14} />
                    当前租户暂无可分配能力。请先在系统管理的租户配置中启用 MCP、Skill、提示词模板或交付能力。
                  </div>
                ) : (
                  <div className="tenant-permission-grid">
                    {resourceOptions.map((option) => {
                      const checked = orgRoleDraft.resourcePermissions.some(
                        (permission) => permission.resourceType === option.resourceType && permission.resourceId === option.resourceId
                      );
                      return (
                        <button
                          key={`${option.resourceType}:${option.resourceId}`}
                          type="button"
                          className={`tenant-permission-option ${checked ? "tenant-permission-option--checked" : ""}`}
                          onClick={() => toggleResourcePermission(option)}
                        >
                          <span className="tenant-permission-option-icon">{getResourceTypeIcon(option.resourceType)}</span>
                          <span className="tenant-permission-option-text">
                            <span>{option.resourceName}</span>
                            <small>{formatResourceType(option.resourceType)} · {option.version} · {formatRiskLevel(option.riskLevel)}</small>
                          </span>
                          <CheckCircle2 size={16} className="tenant-permission-option-check" />
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
            <div className="sys-modal-footer">
              <button className="sys-btn sys-btn--default" onClick={() => setOrgRoleModalOpen(false)}><X size={14} /> 取消</button>
              <button className="sys-btn sys-btn--primary" disabled={orgRoleSubmitting} onClick={() => void handleSubmitOrgRole()}><Save size={14} /> 保存角色</button>
            </div>
          </div>
        </div>
      )}
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
  onEditMembership,
}: {
  overview: TenantOrganizationOverview | null;
  loading: boolean;
  error: string;
  hasTenantContext: boolean;
  onCreateMember: () => void;
  onCreateDepartment: () => void;
  membershipUpdatingId: string | null;
  onEditMembership: (membership: OrganizationMembership) => void;
}) {
  const [selectedDepartmentId, setSelectedDepartmentId] = useState<string>("all");
  const [memberKeyword, setMemberKeyword] = useState("");

  const departmentTree = useMemo(() => {
    if (!overview) return [];
    const memberships = overview.memberships;
    return [
      {
        id: "all",
        name: "全部部门",
        code: overview.tenantCode,
        memberCount: memberships.length,
        level: 0,
      },
      ...overview.departments.map((department) => ({
        id: department.id,
        name: department.name,
        code: department.code,
        memberCount: memberships.filter((membership) => membership.departmentId === department.id).length,
        level: department.parentId ? 1 : 0,
      })),
      {
        id: "unassigned",
        name: "未分配部门",
        code: "",
        memberCount: memberships.filter((membership) => !membership.departmentId).length,
        level: 0,
      },
    ];
  }, [overview]);

  const visibleMemberships = useMemo(() => {
    if (!overview) return [];
    const keyword = memberKeyword.trim().toLowerCase();

    return overview.memberships.filter((membership) => {
      const matchDepartment =
        selectedDepartmentId === "all"
        || (selectedDepartmentId === "unassigned" && !membership.departmentId)
        || membership.departmentId === selectedDepartmentId;
      const matchKeyword =
        !keyword
        || membership.userDisplayName.toLowerCase().includes(keyword)
        || membership.roleName.toLowerCase().includes(keyword)
        || membership.roleCode.toLowerCase().includes(keyword)
        || membership.departmentName.toLowerCase().includes(keyword);
      return matchDepartment && matchKeyword;
    });
  }, [memberKeyword, overview, selectedDepartmentId]);

  if (!hasTenantContext) {
    return (
      <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 text-sm text-[var(--color-text-secondary)]">
        系统管理员需要先在系统管理中选择目标租户，才能查看租户内人员组织。
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold">人员组织维护</h3>
          <p className="agent-muted mt-1 text-xs">左侧按部门筛选，右侧查看成员信息；需要调整时进入单人成员编辑。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button className="sys-btn sys-btn--default" onClick={onCreateDepartment} disabled={!overview}>
            <Building2 size={14} />
            新增部门
          </button>
          <button className="sys-btn sys-btn--primary" onClick={onCreateMember} disabled={!overview}>
            <UserPlus size={14} />
            新增成员
          </button>
        </div>
      </div>

      {loading ? (
        <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] p-4 text-sm text-[var(--color-text-secondary)]">正在加载人员组织数据…</div>
      ) : null}

      {error ? (
        <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">{error}</div>
      ) : null}

      {overview ? (
        <div className="tenant-org-layout">
          <aside className="tenant-dept-tree">
            <div className="tenant-dept-tree-header">
              <div>
                <h3>{overview.tenantName}</h3>
                <p>{overview.departments.length} 个部门 · {overview.memberships.length} 名成员</p>
              </div>
            </div>
            <div className="tenant-dept-tree-list">
              {departmentTree.map((department) => (
                <button
                  key={department.id}
                  type="button"
                  className={`tenant-dept-tree-item ${selectedDepartmentId === department.id ? "tenant-dept-tree-item--active" : ""}`}
                  style={{ paddingLeft: 12 + department.level * 18 }}
                  onClick={() => setSelectedDepartmentId(department.id)}
                >
                  <Building2 size={15} />
                  <span className="tenant-dept-tree-name">{department.name}</span>
                  <span className="tenant-dept-tree-count">{department.memberCount}</span>
                </button>
              ))}
            </div>
          </aside>

          <section className="tenant-member-table-card">
            <div className="tenant-member-toolbar">
              <div>
                <h3>成员列表</h3>
                <p>当前筛选 {visibleMemberships.length} 人</p>
              </div>
              <div className="tenant-member-search">
                <Search size={15} />
                <input
                  value={memberKeyword}
                  placeholder="搜索成员、部门或角色"
                  onChange={(event) => setMemberKeyword(event.target.value)}
                />
              </div>
            </div>
            <div className="tenant-member-table-wrap">
              <table className="tenant-member-table">
                <thead>
                  <tr>
                    <th>成员</th>
                    <th>部门</th>
                    <th>角色</th>
                    <th>空间</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleMemberships.map((membership) => (
                    <tr key={membership.id}>
                      <td>
                        <div className="tenant-member-cell">
                          <span className="tenant-member-avatar">{membership.userDisplayName.slice(0, 1)}</span>
                          <div>
                            <strong>{membership.userDisplayName}</strong>
                            <span>{overview.members.find((member) => member.id === membership.userId)?.username ?? "未找到账号"}</span>
                          </div>
                        </div>
                      </td>
                      <td>{membership.departmentName || "未分配部门"}</td>
                      <td>{membership.roleName}（{membership.roleCode}）</td>
                      <td>{membership.spaceCode}</td>
                      <td>
                        <span className={`sys-status sys-status--${membership.status === "active" ? "active" : "inactive"}`}>
                          <span className="sys-status-dot" />{membership.status === "active" ? "启用" : "停用"}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          className="sys-btn sys-btn--text sys-btn--sm"
                          disabled={membershipUpdatingId === membership.id}
                          onClick={() => onEditMembership(membership)}
                        >
                          <Edit size={14} />
                          编辑
                        </button>
                      </td>
                    </tr>
                  ))}
                  {visibleMemberships.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="tenant-member-empty">暂无符合条件的成员</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
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

function ResourceAuthorizationPanel({
  roles,
  loading,
  error,
  total,
  page,
  totalPages,
  onCreate,
  onEdit,
  onPageChange,
}: {
  roles: TenantOrgRole[];
  loading: boolean;
  error: string;
  total: number;
  page: number;
  totalPages: number;
  onCreate: () => void;
  onEdit: (role: TenantOrgRole) => void;
  onPageChange: (page: number) => void;
}) {
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold">资源授权</h3>
          <p className="agent-muted mt-1 text-xs">
            这里决定业务功能、流程模板、MCP、Skill、提示词模板和交付能力能授权给哪些角色、部门或人员；业务页面只消费授权结果。
          </p>
        </div>
        <button className="sys-btn sys-btn--primary" onClick={onCreate}><PlusCircle size={14} /> 新增授权</button>
      </div>

      {error ? (
        <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">{error}</div>
      ) : null}

      {loading ? (
        <div className="sys-preview-card"><div className="sys-preview-card-title"><UserRoundCog size={16} /> 正在加载资源授权</div></div>
      ) : null}

      {!loading && roles.length === 0 ? (
        <div className="sys-preview-card">
          <Empty description="暂无资源授权规则" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          <div className="mt-3 flex justify-center">
            <button className="sys-btn sys-btn--default" onClick={onCreate}><PlusCircle size={14} /> 创建第一条授权</button>
          </div>
        </div>
      ) : null}

      {roles.length > 0 ? (
        <div className="sys-card-grid">
          {roles.map((role) => (
            <article key={role.id} className="sys-card sys-card--static">
              <div className="sys-card-header">
                <div className="sys-card-avatar sys-card-avatar--tenant"><UserRoundCog size={22} /></div>
                <div className="sys-card-info">
                  <div className="sys-card-name">{role.name}</div>
                  <div className="sys-card-code">{role.description || "未填写说明"}</div>
                </div>
                <span className={`sys-status sys-status--${role.status === "active" ? "active" : "inactive"}`}>
                  <span className="sys-status-dot" />{role.status === "active" ? "启用" : "停用"}
                </span>
              </div>
              <div className="sys-info-tags">
                {role.pagePermissions.map((permission) => (
                  <span key={permission} className="sys-info-tag sys-info-tag--primary">{formatPagePermission(permission)}</span>
                ))}
                {role.pagePermissions.length === 0 ? <span className="sys-info-tag sys-info-tag--warn">未配置页面</span> : null}
              </div>
              <div className="sys-info-tags">
                {(role.resourcePermissions ?? []).map((permission) => (
                  <span key={`${permission.resourceType}:${permission.resourceId}`} className="sys-info-tag">
                    {formatResourceType(permission.resourceType)} · {permission.resourceName}
                  </span>
                ))}
                {(role.resourcePermissions ?? []).length === 0 ? <span className="sys-info-tag sys-info-tag--warn">未配置能力资源</span> : null}
              </div>
              <div className="sys-card-footer">
                <span className="sys-card-footer-time">角色维度 · 更新于 {formatDateTime(role.updatedAt)}</span>
                <div className="sys-card-footer-actions">
                  <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onEdit(role)}><Edit size={14} /> 编辑</button>
                </div>
              </div>
            </article>
          ))}
        </div>
      ) : null}

      <div className="tenant-pagination">
        <span>共 {total} 条授权，第 {page} / {totalPages} 页</span>
        <div className="tenant-pagination-actions">
          <button className="sys-btn sys-btn--default sys-btn--sm" disabled={page <= 1} onClick={() => onPageChange(page - 1)}><ChevronLeft size={14} /> 上一页</button>
          <button className="sys-btn sys-btn--default sys-btn--sm" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>下一页 <ChevronRight size={14} /></button>
        </div>
      </div>
    </div>
  );
}

function formatPagePermission(permission: string): string {
  return pagePermissionOptions.find((option) => option.value === permission)?.label ?? permission;
}

function formatResourceType(type: string): string {
  switch (type) {
    case "mcp":
      return "MCP";
    case "skill":
      return "Skill";
    case "prompt_template":
      return "提示词模板";
    case "delivery":
      return "交付能力";
    default:
      return type;
  }
}

function formatRiskLevel(riskLevel: string): string {
  switch (riskLevel) {
    case "high":
      return "高风险";
    case "medium":
      return "中风险";
    case "low":
      return "低风险";
    default:
      return riskLevel || "未标记风险";
  }
}

function getResourceTypeIcon(type: string) {
  if (type === "mcp") return <Database size={16} />;
  if (type === "skill") return <ShieldCheck size={16} />;
  if (type === "prompt_template") return <ClipboardList size={16} />;
  return <UserRoundCog size={16} />;
}

function formatDateTime(value: string): string {
  if (!value) return "暂无记录";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
