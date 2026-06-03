import { useCallback, useEffect, useMemo, useState } from "react";
import { Empty, Segmented, Select, message, Pagination, Drawer } from "antd";
import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ClipboardList,
  Code2,
  Database,
  Edit,
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
  CreatePageGrantRequest,
  CreateResourceGrantRequest,
  CreateTenantRoleRequest,
  GrantPrincipal,
  OrganizationDepartment,
  OrganizationMembership,
  OrganizationRole,
  PageGrant,
  PrincipalType,
  ResourceGrant,
  TenantOrganizationOverview,
  TenantResourceOption,
  UpdateTenantRoleRequest,
} from "../../types/organization";

type TenantManagementTabKey = "organization" | "roles" | "resources";

type TenantManagementTab = {
  key: TenantManagementTabKey;
  label: string;
  description: string;
  icon: typeof ShieldCheck;
};

const tenantManagementTabs: TenantManagementTab[] = [
  { key: "organization", label: "人员组织", description: "用户、部门和成员关系", icon: UsersRound },
  { key: "roles", label: "角色维护", description: "租户内角色新增、编辑和停用", icon: ShieldCheck },
  { key: "resources", label: "资源分配", description: "分配模块入口和可用能力池", icon: UserRoundCog },
];

const pagePermissionOptions = [
  { value: "workbench", label: "业务工作台", description: "待办、发起流程和运行摘要", icon: ClipboardList },
  { value: "designer", label: "流程设计", description: "草稿、阶段积木和能力配置", icon: Code2 },
  { value: "assets", label: "能力资产", description: "智能体、Skill、MCP 和交付能力", icon: Database },
];

const emptyMemberForm: CreateMemberRequest = {
  displayName: "",
  username: "",
  password: "agentum123",
  email: "",
  roleId: "",
  departmentId: undefined,
};

const emptyDepartmentForm: CreateDepartmentRequest = {
  name: "",
  parentId: undefined,
  sortOrder: 0,
};

type RoleDraft = CreateTenantRoleRequest & {
  status: "active" | "disabled";
  membershipIds: string[];
};

const emptyRoleForm: RoleDraft = {
  name: "",
  description: "",
  status: "active",
  membershipIds: [],
};

const adminSelectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const adminSelectSuffixIcon = <ChevronDown className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />;
// 多选空态占位与左侧图标需同一垂直中线；antd 6 默认把占位符放在仅 font-size 高的槽位里，需用 styles 拉回居中。
const adminPrincipalSelectStyles = {
  root: { alignItems: "center" as const, paddingBlock: 0 },
  content: { alignItems: "center" as const, lineHeight: 1.4 },
  placeholder: { position: "static" as const, transform: "none", lineHeight: 1.4 },
  input: { height: 22, lineHeight: "22px" },
};

type MemberEditDraft = {
  username: string;
  displayName: string;
  email: string;
  departmentId?: string;
  roleIds: string[];
  status: "active" | "disabled";
};

type PrincipalSelectionKey = `${PrincipalType}:${string}`;

type DepartmentTreeItem = {
  department: OrganizationDepartment;
  level: number;
  memberCount: number;
  hasChildren: boolean;
};

const adminPaginationLocale = {
  items_per_page: "条/页",
  jump_to: "跳至",
  jump_to_confirm: "确定",
  page: "页",
  prev_page: "上一页",
  next_page: "下一页",
  prev_5: "向前 5 页",
  next_5: "向后 5 页",
  prev_3: "向前 3 页",
  next_3: "向后 3 页",
  page_size: "每页条数",
};

function formatPaginationTotal(count: number, range: [number, number], pageSize: number): string {
  return count <= pageSize ? `共 ${count} 条` : `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`;
}

function useClientPagination<T>(items: T[], defaultPageSize = 10) {
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(defaultPageSize);
  const totalPages = Math.max(Math.ceil(items.length / pageSize), 1);

  useEffect(() => {
    if (current > totalPages) {
      setCurrent(totalPages);
    }
  }, [current, totalPages]);

  const pagedItems = useMemo(() => {
    const start = (current - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [current, items, pageSize]);

  const onChange = useCallback((page: number, size: number) => {
    setCurrent(page);
    setPageSize(size);
  }, []);

  return { current, pageSize, total: items.length, pagedItems, onChange };
}

function AdminPagination({
  current,
  pageSize,
  total,
  onChange,
}: {
  current: number;
  pageSize: number;
  total: number;
  onChange: (page: number, size: number) => void;
}) {
  if (total <= 0) return null;

  return (
    <div className="agent-admin-pagination-wrap">
      <Pagination
        className="agent-admin-pagination"
        current={current}
        pageSize={pageSize}
        total={total}
        locale={adminPaginationLocale}
        showSizeChanger={{ className: "agent-admin-select", popupClassName: "agent-select-dropdown agent-admin-select-dropdown" }}
        pageSizeOptions={["10", "20", "50"]}
        showTotal={(count, range) => formatPaginationTotal(count, range, pageSize)}
        onChange={onChange}
        onShowSizeChange={onChange}
      />
    </div>
  );
}

export function TenantManagementPage() {
  // 租户管理页按租户管理员的日常任务组织展示，动作码只作为后端策略标识露出在次级文本中。
  const [activeTab, setActiveTab] = useState<TenantManagementTabKey>("organization");
  const user = useAuthStore((s) => s.user);
  const token = useAuthStore((s) => s.token);
  const themeMode = useAuthStore((s) => s.themeMode);
  const drawerRootClassName = themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer";
  const [messageApi, messageContextHolder] = message.useMessage();
  const [organizationOverview, setOrganizationOverview] = useState<TenantOrganizationOverview | null>(null);
  const [organizationLoading, setOrganizationLoading] = useState(false);
  const [organizationError, setOrganizationError] = useState("");
  const [createMemberOpen, setCreateMemberOpen] = useState(false);
  const [createMemberSubmitting, setCreateMemberSubmitting] = useState(false);
  const [memberDraft, setMemberDraft] = useState<CreateMemberRequest>(emptyMemberForm);
  const [createDepartmentOpen, setCreateDepartmentOpen] = useState(false);
  const [departmentDraft, setDepartmentDraft] = useState<CreateDepartmentRequest>(emptyDepartmentForm);
  const [editingDepartment, setEditingDepartment] = useState<OrganizationDepartment | null>(null);
  const [departmentSubmitting, setDepartmentSubmitting] = useState(false);
  const [roleModalOpen, setRoleModalOpen] = useState(false);
  const [editingRole, setEditingRole] = useState<OrganizationRole | null>(null);
  const [roleDraft, setRoleDraft] = useState<RoleDraft>(emptyRoleForm);
  const [roleSubmitting, setRoleSubmitting] = useState(false);
  const [roleDeleteTarget, setRoleDeleteTarget] = useState<OrganizationRole | null>(null);
  const [membershipUpdatingId, setMembershipUpdatingId] = useState<string | null>(null);
  const [editMemberOpen, setEditMemberOpen] = useState(false);
  const [editingMembership, setEditingMembership] = useState<OrganizationMembership | null>(null);
  const [memberEditDraft, setMemberEditDraft] = useState<MemberEditDraft>({ username: "", displayName: "", email: "", departmentId: undefined, roleIds: [], status: "active" });
  const [memberEditSubmitting, setMemberEditSubmitting] = useState(false);
  const [resourceOptions, setResourceOptions] = useState<TenantResourceOption[]>([]);
  const [authorizationLoading, setAuthorizationLoading] = useState(false);
  const [authorizationError, setAuthorizationError] = useState("");
  const [pageGrants, setPageGrants] = useState<PageGrant[]>([]);
  const [pageGrantModalOpen, setPageGrantModalOpen] = useState(false);
  const [editingPageGrantGroup, setEditingPageGrantGroup] = useState<PageGrant | null>(null);
  const [pageGrantGroupName, setPageGrantGroupName] = useState("");
  const [pageGrantPrincipalKeys, setPageGrantPrincipalKeys] = useState<PrincipalSelectionKey[]>([]);
  const [selectedPageKeys, setSelectedPageKeys] = useState<string[]>([]);
  const [pageGrantSubmitting, setPageGrantSubmitting] = useState(false);
  const [resourceGrants, setResourceGrants] = useState<ResourceGrant[]>([]);
  const [grantModalOpen, setGrantModalOpen] = useState(false);
  const [editingGrantGroup, setEditingGrantGroup] = useState<ResourceGrant | null>(null);
  const [grantGroupName, setGrantGroupName] = useState("");
  const [grantPrincipalKeys, setGrantPrincipalKeys] = useState<PrincipalSelectionKey[]>([]);
  const [grantResourceIds, setGrantResourceIds] = useState<string[]>([]);
  const [grantSubmitting, setGrantSubmitting] = useState(false);
  const activeTabMeta = tenantManagementTabs.find((tab) => tab.key === activeTab) ?? tenantManagementTabs[0];
  const activeDepartmentOptions = useMemo(
    () => (organizationOverview?.departments ?? [])
      .filter((department) => department.status === "active")
      .map((department) => ({ value: department.id, label: department.name })),
    [organizationOverview?.departments]
  );
  const roleMemberOptions = useMemo(
    () => (organizationOverview?.memberships ?? [])
      .filter((membership) => membership.status === "active")
      .map((membership) => ({
        value: membership.id,
        label: `${membership.userDisplayName || "未找到账号"} · ${membership.departmentName || "未分配部门"}`,
      })),
    [organizationOverview?.memberships]
  );
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

  const loadPageGrants = useCallback(async () => {
    if (!token || !user?.tenantId) return;

    setAuthorizationLoading(true);
    setAuthorizationError("");
    try {
      setPageGrants(await organizationApi.listPageGrants(user.tenantId, token));
    } catch (error) {
      console.warn("[tenant-management] 页签分配加载失败", getTenantManagementErrorContext(error, user.tenantId));
      setAuthorizationError(error instanceof AgentumApiError ? error.message : "无法加载页签分配数据");
      setPageGrants([]);
    } finally {
      setAuthorizationLoading(false);
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

  const loadResourceGrants = useCallback(async () => {
    if (!token || !user?.tenantId) return;

    try {
      setResourceGrants(await organizationApi.listResourceGrants(user.tenantId, token));
    } catch (error) {
      console.warn("[tenant-management] 能力分配加载失败", getTenantManagementErrorContext(error, user.tenantId));
      setResourceGrants([]);
    }
  }, [token, user?.tenantId]);

  useEffect(() => {
    if (!token || !user?.tenantId) {
      return;
    }

    let active = true;
    const tenantId = user.tenantId;
    setOrganizationLoading(true);
    setOrganizationError("");

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
  }, [token, user?.tenantId]);

  useEffect(() => {
    if (activeTab !== "resources") return;
    void loadPageGrants();
    void loadResourceOptions();
    void loadResourceGrants();
  }, [activeTab, loadPageGrants, loadResourceGrants, loadResourceOptions]);

  async function handleCreateMember(values: CreateMemberRequest) {
    if (!token || !user?.tenantId) {
      console.warn("[tenant-management] 新增成员失败：缺少租户上下文", { hasToken: Boolean(token), userId: user?.id });
      messageApi.error("当前账号缺少租户上下文，无法新增成员");
      return;
    }

    setCreateMemberSubmitting(true);
    setOrganizationError("");

    try {
      if (!values.departmentId) {
        messageApi.warning("请选择成员所属部门");
        return;
      }
      // 初始密码只随创建请求提交，禁止进入日志、localStorage、URL 或错误详情；诊断日志只记录脱敏字段。
      const overview = await organizationApi.createMember(user.tenantId, token, values);
      setOrganizationOverview(overview);
      setCreateMemberOpen(false);
      setMemberDraft(emptyMemberForm);
    } catch (error) {
      console.warn("[tenant-management] 新增成员失败", getTenantManagementErrorContext(error, user.tenantId, { username: values.username, roleId: values.roleId, departmentId: values.departmentId }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "新增成员失败，请稍后重试");
    } finally {
      setCreateMemberSubmitting(false);
    }
  }

  function openEditDepartmentModal(department: OrganizationDepartment) {
    setEditingDepartment(department);
    setDepartmentDraft({ name: department.name, parentId: department.parentId ?? undefined, sortOrder: department.sortOrder });
    setCreateDepartmentOpen(true);
  }

  async function handleSubmitDepartment() {
    if (!token || !user?.tenantId) {
      messageApi.error("当前账号缺少租户上下文，无法保存部门");
      return;
    }
    setDepartmentSubmitting(true);
    try {
      if (editingDepartment) {
        const overview = await organizationApi.updateDepartment(user.tenantId, editingDepartment.id, token, {
          name: departmentDraft.name,
          parentId: departmentDraft.parentId,
          sortOrder: departmentDraft.sortOrder,
        });
        setOrganizationOverview(overview);
        messageApi.success("部门已更新");
      } else {
        // 前端只提交部门治理动作；部门编码由后端生成，上级部门是否属于当前租户必须由后端再次校验。
        const overview = await organizationApi.createDepartment(user.tenantId, token, departmentDraft);
        setOrganizationOverview(overview);
        messageApi.success("部门已新增");
      }
      setEditingDepartment(null);
      setCreateDepartmentOpen(false);
      setDepartmentDraft(emptyDepartmentForm);
    } catch (error) {
      console.warn("[tenant-management] 部门保存失败", getTenantManagementErrorContext(error, user.tenantId, { departmentId: editingDepartment?.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "部门保存失败，请稍后重试");
    } finally {
      setDepartmentSubmitting(false);
    }
  }

  async function handleUpdateDepartmentStatus(department: OrganizationDepartment, status: "active" | "disabled") {
    if (!token || !user?.tenantId) return;
    setDepartmentSubmitting(true);
    try {
      const overview = await organizationApi.updateDepartmentStatus(user.tenantId, department.id, token, { status });
      setOrganizationOverview(overview);
      setCreateDepartmentOpen(false);
      setEditingDepartment(null);
      messageApi.success(status === "active" ? "部门已启用" : "部门已停用");
    } catch (error) {
      console.warn("[tenant-management] 部门状态更新失败", getTenantManagementErrorContext(error, user.tenantId, { departmentId: department.id, status }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "部门状态更新失败，请先确认部门下没有启用成员或启用下级部门");
    } finally {
      setDepartmentSubmitting(false);
    }
  }

  async function handleDeleteDepartment(department: OrganizationDepartment) {
    if (!token || !user?.tenantId) return;
    if (!window.confirm(`确认彻底删除部门“${department.name}”？删除后不会再出现在组织树中。`)) {
      return;
    }
    setDepartmentSubmitting(true);
    try {
      await organizationApi.deleteDepartment(user.tenantId, department.id, token);
      setOrganizationOverview(await organizationApi.overview(user.tenantId, token));
      setCreateDepartmentOpen(false);
      setEditingDepartment(null);
      messageApi.success("部门已删除");
    } catch (error) {
      console.warn("[tenant-management] 部门删除失败", getTenantManagementErrorContext(error, user.tenantId, { departmentId: department.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "部门删除失败，请先确认部门没有成员关系、下级部门和授权记录");
    } finally {
      setDepartmentSubmitting(false);
    }
  }

  function openCreateRoleModal() {
    setEditingRole(null);
    setRoleDraft(emptyRoleForm);
    setRoleModalOpen(true);
  }

  function openEditRoleModal(role: OrganizationRole) {
    setEditingRole(role);
    const membershipIds = (organizationOverview?.memberships ?? [])
      .filter((membership) => membership.roles.some((item) => item.id === role.id) && membership.status === "active")
      .map((membership) => membership.id);
    setRoleDraft({ name: role.name, description: "", status: role.status === "disabled" ? "disabled" : "active", membershipIds });
    setRoleModalOpen(true);
  }

  async function handleSubmitRole() {
    if (!token || !user?.tenantId) return;
    if (editingRole && roleDraft.status === "disabled" && roleDraft.membershipIds.length > 0) {
      messageApi.warning("停用角色前请先移出所有启用成员");
      return;
    }
    setRoleSubmitting(true);
    try {
      const overview = editingRole
        ? await organizationApi.updateRole(user.tenantId, editingRole.id, token, {
          name: roleDraft.name,
          description: roleDraft.description,
          status: roleDraft.status,
          membershipIds: roleDraft.membershipIds,
        } as UpdateTenantRoleRequest)
        : await organizationApi.createRole(user.tenantId, token, { name: roleDraft.name, description: roleDraft.description });
      setOrganizationOverview(overview);
      setRoleModalOpen(false);
      setEditingRole(null);
      messageApi.success(editingRole ? "角色已更新" : "角色已新增");
    } catch (error) {
      console.warn("[tenant-management] 角色保存失败", getTenantManagementErrorContext(error, user?.tenantId, { roleId: editingRole?.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "角色保存失败，请稍后重试");
    } finally {
      setRoleSubmitting(false);
    }
  }

  async function handleConfirmDeleteRole() {
    if (!roleDeleteTarget || !token || !user?.tenantId) return;
    const role = roleDeleteTarget;
    setRoleSubmitting(true);
    try {
      await organizationApi.deleteRole(user.tenantId, role.id, token);
      setOrganizationOverview(await organizationApi.overview(user.tenantId, token));
      setRoleModalOpen(false);
      setEditingRole(null);
      setRoleDeleteTarget(null);
      messageApi.success("角色已删除");
    } catch (error) {
      console.warn("[tenant-management] 角色删除失败", getTenantManagementErrorContext(error, user.tenantId, { roleId: role.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "角色删除失败，请先确认没有启用成员或分配引用");
    } finally {
      setRoleSubmitting(false);
    }
  }

  function openEditMemberModal(membership: OrganizationMembership) {
    const member = organizationOverview?.members.find((item) => item.id === membership.userId);
    setEditingMembership(membership);
    setMemberEditDraft({
      username: member?.username ?? "",
      displayName: member?.displayName ?? membership.userDisplayName,
      email: member?.email ?? "",
      departmentId: membership.departmentId ?? undefined,
      roleIds: membership.roles.map((role) => role.id),
      status: membership.status === "disabled" ? "disabled" : "active",
    });
    setEditMemberOpen(true);
  }

  async function handleSubmitMemberEdit() {
    if (!editingMembership) {
      return;
    }

    if (!memberEditDraft.displayName.trim() || !memberEditDraft.username.trim()) {
      messageApi.warning("请填写成员姓名和用户名");
      return;
    }

    if (memberEditDraft.roleIds.length === 0) {
      messageApi.warning("请选择成员角色");
      return;
    }

    if (!token || !user?.tenantId) {
      messageApi.error("当前账号缺少租户上下文，无法编辑成员");
      return;
    }

    const originalDepartmentId = editingMembership.departmentId ?? undefined;
    const originalMember = organizationOverview?.members.find((member) => member.id === editingMembership.userId);
    const profileChanged =
      (originalMember?.username ?? "") !== memberEditDraft.username.trim()
      || (originalMember?.displayName ?? editingMembership.userDisplayName) !== memberEditDraft.displayName.trim()
      || (originalMember?.email ?? "") !== memberEditDraft.email.trim();
    const departmentChanged = originalDepartmentId !== memberEditDraft.departmentId;
    const originalRoleIds = editingMembership.roles.map((role) => role.id).sort().join(",");
    const nextRoleIds = [...memberEditDraft.roleIds].sort().join(",");
    const roleChanged = originalRoleIds !== nextRoleIds;
    const statusChanged = editingMembership.status !== memberEditDraft.status;

    if (!profileChanged && !departmentChanged && !roleChanged && !statusChanged) {
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
      if (profileChanged) {
        nextOverview = await organizationApi.updateMemberProfile(
          user.tenantId,
          editingMembership.id,
          token,
          {
            username: memberEditDraft.username.trim(),
            displayName: memberEditDraft.displayName.trim(),
            email: memberEditDraft.email.trim(),
          }
        );
        setOrganizationOverview(nextOverview);
      }

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
          { roleIds: memberEditDraft.roleIds }
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
          roleIds: memberEditDraft.roleIds.join(","),
          departmentId: memberEditDraft.departmentId,
          status: memberEditDraft.status,
        })
      );
      messageApi.error(error instanceof AgentumApiError ? error.message : "成员编辑失败，请稍后重试");
    } finally {
      setMemberEditSubmitting(false);
      setMembershipUpdatingId(null);
    }
  }

  function openPageGrantModal() {
    setEditingPageGrantGroup(null);
    setPageGrantGroupName("");
    setPageGrantPrincipalKeys([]);
    setSelectedPageKeys([]);
    setPageGrantModalOpen(true);
  }

  function openEditPageGrantModal(group: PageGrant) {
    setEditingPageGrantGroup(group);
    setPageGrantGroupName(group.groupName);
    setPageGrantPrincipalKeys(group.principals.map((principal) => `${principal.principalType}:${principal.principalId}` as PrincipalSelectionKey));
    setSelectedPageKeys(group.pages.map((page) => page.pageKey));
    setPageGrantModalOpen(true);
  }

  async function handleSubmitPageGrant() {
    if (!token || !user?.tenantId) return;
    const groupName = pageGrantGroupName.trim();
    if (!groupName) {
      messageApi.warning("请输入分配名称");
      return;
    }
    if (pageGrantPrincipalKeys.length === 0 || selectedPageKeys.length === 0) {
      messageApi.warning("请选择分配对象和可访问页签");
      return;
    }
    const tenantId = user.tenantId;
    setPageGrantSubmitting(true);
    setAuthorizationError("");
    try {
      const request: CreatePageGrantRequest = {
        groupName,
        principals: pageGrantPrincipalKeys.map((principalKey) => {
          const [principalType, principalId] = principalKey.split(":") as [PrincipalType, string];
          return { principalType, principalId };
        }),
        pageKeys: selectedPageKeys,
      };
      if (editingPageGrantGroup) {
        await organizationApi.updatePageGrant(tenantId, editingPageGrantGroup.id, token, request);
      } else {
        await organizationApi.createPageGrant(tenantId, token, request);
      }
      await loadPageGrants();
      setPageGrantModalOpen(false);
      setEditingPageGrantGroup(null);
      setPageGrantGroupName("");
      setPageGrantPrincipalKeys([]);
      setSelectedPageKeys([]);
      messageApi.success(editingPageGrantGroup ? "页签分配已更新" : "页签分配已新增");
    } catch (error) {
      console.warn("[tenant-management] 页签分配保存失败", getTenantManagementErrorContext(error, user.tenantId, { principalId: pageGrantPrincipalKeys.join(","), pageKey: selectedPageKeys.join(",") }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "页签分配保存失败，请稍后重试");
    } finally {
      setPageGrantSubmitting(false);
    }
  }

  async function handleDeletePageGrantGroup(group: PageGrant) {
    if (!token || !user?.tenantId) return;
    try {
      await organizationApi.deletePageGrant(user.tenantId, group.id, token);
      await loadPageGrants();
      messageApi.success("页签分配已删除");
    } catch (error) {
      console.warn("[tenant-management] 页签分配删除失败", getTenantManagementErrorContext(error, user.tenantId, { grantId: group.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "页签分配删除失败，请稍后重试");
    }
  }

  function openGrantModal() {
    setEditingGrantGroup(null);
    setGrantGroupName("");
    setGrantPrincipalKeys([]);
    setGrantResourceIds([]);
    setGrantModalOpen(true);
  }

  function openEditGrantModal(group: ResourceGrant) {
    setEditingGrantGroup(group);
    setGrantGroupName(group.groupName);
    setGrantPrincipalKeys(group.principals.map((principal) => `${principal.principalType}:${principal.principalId}` as PrincipalSelectionKey));
    setGrantResourceIds(group.resources.map((resource) => resource.resourceId));
    setGrantModalOpen(true);
  }

  async function handleSubmitGrant() {
    if (!token || !user?.tenantId) return;
    const groupName = grantGroupName.trim();
    if (!groupName) {
      messageApi.warning("请输入分配名称");
      return;
    }
    if (grantPrincipalKeys.length === 0 || grantResourceIds.length === 0) {
      messageApi.warning("请选择分配对象和能力资源");
      return;
    }
    const tenantId = user.tenantId;
    setGrantSubmitting(true);
    try {
      const request: CreateResourceGrantRequest = {
        groupName,
        principals: grantPrincipalKeys.map((principalKey) => {
          const [principalType, principalId] = principalKey.split(":") as [PrincipalType, string];
          return { principalType, principalId };
        }),
        resources: grantResourceIds.map((resourceId) => {
          const resource = resourceOptions.find((option) => option.resourceId === resourceId);
          return { resourceId, resourceType: resource?.resourceType ?? "skill" };
        }),
      };
      if (editingGrantGroup) {
        await organizationApi.updateResourceGrant(tenantId, editingGrantGroup.id, token, request);
      } else {
        await organizationApi.createResourceGrant(tenantId, token, request);
      }
      await loadResourceGrants();
      setGrantModalOpen(false);
      setEditingGrantGroup(null);
      setGrantGroupName("");
      setGrantPrincipalKeys([]);
      setGrantResourceIds([]);
      messageApi.success(editingGrantGroup ? "能力分配已更新" : "能力分配已新增");
    } catch (error) {
      console.warn("[tenant-management] 能力分配保存失败", getTenantManagementErrorContext(error, user.tenantId, { principalId: grantPrincipalKeys.join(","), resourceId: grantResourceIds.join(",") }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "能力分配保存失败，请稍后重试");
    } finally {
      setGrantSubmitting(false);
    }
  }

  async function handleDeleteGrantGroup(group: ResourceGrant) {
    if (!token || !user?.tenantId) return;
    try {
      await organizationApi.deleteResourceGrant(user.tenantId, group.id, token);
      await loadResourceGrants();
      messageApi.success("能力分配已删除");
    } catch (error) {
      console.warn("[tenant-management] 能力分配删除失败", getTenantManagementErrorContext(error, user.tenantId, { grantId: group.id }));
      messageApi.error(error instanceof AgentumApiError ? error.message : "能力分配删除失败，请稍后重试");
    }
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
                维护当前租户的人员组织、角色职责、业务入口和可用能力池，保证成员能在合适的范围内发起流程、设计流程和查看结果。
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

        {activeTab === "organization" ? (
          <div className="overflow-hidden rounded-[var(--radius-lg)] border border-[var(--color-border-light)] bg-[var(--color-bg-card)] shadow-[var(--shadow-sm)]">
            <div className="p-5">
              <OrganizationPanel
                overview={organizationOverview}
                loading={organizationLoading}
                error={organizationError}
                hasTenantContext={Boolean(user?.tenantId)}
                onCreateMember={() => { setMemberDraft(emptyMemberForm); setCreateMemberOpen(true); }}
                onCreateDepartment={() => { setEditingDepartment(null); setDepartmentDraft(emptyDepartmentForm); setCreateDepartmentOpen(true); }}
                onEditDepartment={openEditDepartmentModal}
                membershipUpdatingId={membershipUpdatingId}
                onEditMembership={openEditMemberModal}
              />
            </div>
          </div>
        ) : null}
        {activeTab === "roles" ? (
          <RoleManagementPanel
            overview={organizationOverview}
            loading={organizationLoading}
            error={organizationError}
            hasTenantContext={Boolean(user?.tenantId)}
            onCreateRole={openCreateRoleModal}
            onEditRole={openEditRoleModal}
          />
        ) : null}
        {activeTab === "resources" ? (
          <ResourceAuthorizationPanel
            pageGrants={pageGrants}
            loading={authorizationLoading}
            error={authorizationError}
            grants={resourceGrants}
            onCreateGrant={openGrantModal}
            onCreatePageGrant={openPageGrantModal}
            onEditPageGrant={openEditPageGrantModal}
            onEditGrant={openEditGrantModal}
            onDeletePageGrant={(group) => void handleDeletePageGrantGroup(group)}
            onDeleteGrant={(group) => void handleDeleteGrantGroup(group)}
          />
        ) : null}
      </div>

      {createMemberOpen && (
        <div className="sys-modal-mask" onClick={() => setCreateMemberOpen(false)}>
          <div className="sys-modal" style={{ maxWidth: 640 }} onClick={(event) => event.stopPropagation()}>
            <div className="sys-modal-header">
              <span className="sys-modal-title">新增成员</span>
              <button className="sys-modal-close" onClick={() => setCreateMemberOpen(false)}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
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
                  <p className="sys-field-hint">默认密码为 agentum123，可按需要修改后再创建成员。</p>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">邮箱</label>
                  <div className="sys-field-input-wrap"><UserPlus size={16} className="sys-field-prefix" /><input className="sys-field-input" value={memberDraft.email ?? ""} placeholder="name@example.com" onChange={(event) => setMemberDraft((draft) => ({ ...draft, email: event.target.value }))} /></div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">部门</label>
                  <Select
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<Building2 className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    placeholder="请选择部门"
                    value={memberDraft.departmentId}
                    options={activeDepartmentOptions}
                    onChange={(departmentId) => setMemberDraft((draft) => ({ ...draft, departmentId }))}
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">角色</label>
                  <Select
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<ShieldCheck className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    placeholder="请选择角色"
                    value={memberDraft.roleId || undefined}
                    options={(organizationOverview?.roles ?? []).map((role) => ({ value: role.id, label: role.name }))}
                    onChange={(roleId) => setMemberDraft((draft) => ({ ...draft, roleId }))}
                  />
                </div>
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
                  <strong>{memberEditDraft.displayName || editingMembership.userDisplayName}</strong>
                  <span>{memberEditDraft.username || organizationOverview?.members.find((member) => member.id === editingMembership.userId)?.username || "未找到账号"}</span>
                </div>
              </div>
              <div className="sys-config-group tenant-member-profile-group">
                <div className="sys-config-group-title">人员基本信息</div>
                <div className="sys-field-row">
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">成员姓名</label>
                    <div className="sys-field-input-wrap">
                      <Type size={16} className="sys-field-prefix" />
                      <input
                        className="sys-field-input"
                        value={memberEditDraft.displayName}
                        maxLength={100}
                        onChange={(event) => setMemberEditDraft((draft) => ({ ...draft, displayName: event.target.value }))}
                      />
                    </div>
                  </div>
                  <div className="sys-field">
                    <label className="sys-field-label sys-field-label--required">用户名</label>
                    <div className="sys-field-input-wrap">
                      <Tag size={16} className="sys-field-prefix" />
                      <input
                        className="sys-field-input"
                        value={memberEditDraft.username}
                        maxLength={100}
                        autoComplete="off"
                        onChange={(event) => setMemberEditDraft((draft) => ({ ...draft, username: event.target.value }))}
                      />
                    </div>
                  </div>
                </div>
                <div className="sys-field">
                  <label className="sys-field-label">邮箱</label>
                  <div className="sys-field-input-wrap">
                    <UserPlus size={16} className="sys-field-prefix" />
                    <input
                      className="sys-field-input"
                      value={memberEditDraft.email}
                      maxLength={255}
                      placeholder="name@example.com"
                      onChange={(event) => setMemberEditDraft((draft) => ({ ...draft, email: event.target.value }))}
                    />
                  </div>
                </div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">部门</label>
                  <Select
                    allowClear
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<Building2 className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    placeholder="未分配部门"
                    value={memberEditDraft.departmentId}
                    options={activeDepartmentOptions}
                    onChange={(departmentId) => setMemberEditDraft((draft) => ({ ...draft, departmentId }))}
                  />
                </div>
                <div className="sys-field">
                  <label className="sys-field-label sys-field-label--required">角色</label>
                  <Select
                    mode="multiple"
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<ShieldCheck className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    placeholder="请选择一个或多个角色"
                    value={memberEditDraft.roleIds}
                    options={(organizationOverview?.roles ?? []).map((role) => ({ value: role.id, label: role.name }))}
                    onChange={(roleIds) => setMemberEditDraft((draft) => ({ ...draft, roleIds }))}
                  />
                </div>
              </div>
              <div className="sys-field">
                <label className="sys-field-label">状态</label>
                <Select
                  className="agent-admin-select w-full"
                  classNames={adminSelectClassNames}
                  prefix={<CheckCircle2 className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                  suffixIcon={adminSelectSuffixIcon}
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
              <span className="sys-modal-title">{editingDepartment ? "编辑部门" : "新增部门"}</span>
              <button className="sys-modal-close" onClick={() => { setCreateDepartmentOpen(false); setEditingDepartment(null); }}><X size={18} /></button>
            </div>
            <div className="sys-modal-body">
              <div className="sys-hint"><Info size={14} /> 部门编码由后端自动生成，页面只维护业务名称、层级和排序。</div>
              {editingDepartment ? (
                <div className="sys-field">
                  <label className="sys-field-label">当前状态</label>
                  <div className="sys-readonly-field">
                    <span className={`sys-status sys-status--${editingDepartment.status === "active" ? "active" : "inactive"}`}>
                      <span className="sys-status-dot" />{editingDepartment.status === "active" ? "启用" : "停用"}
                    </span>
                  </div>
                </div>
              ) : null}
              <div className="sys-field">
                <label className="sys-field-label sys-field-label--required">部门名称</label>
                <div className="sys-field-input-wrap"><Building2 size={16} className="sys-field-prefix" /><input className="sys-field-input" value={departmentDraft.name} placeholder="例如：风控部" onChange={(event) => setDepartmentDraft((draft) => ({ ...draft, name: event.target.value }))} /></div>
              </div>
              <div className="sys-field-row">
                <div className="sys-field">
                  <label className="sys-field-label">上级部门</label>
                  <Select
                    allowClear
                    className="agent-admin-select w-full"
                    classNames={adminSelectClassNames}
                    prefix={<Building2 className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                    suffixIcon={adminSelectSuffixIcon}
                    placeholder="不选择则为一级部门"
                    value={departmentDraft.parentId}
                    options={(organizationOverview?.departments ?? [])
                      .filter((department) => department.status === "active" && department.id !== editingDepartment?.id)
                      .map((department) => ({ value: department.id, label: department.name }))}
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
              {editingDepartment ? (
                <div className="tenant-department-danger-actions">
                  <button
                    className="sys-btn sys-btn--default"
                    disabled={departmentSubmitting}
                    onClick={() => void handleUpdateDepartmentStatus(editingDepartment, editingDepartment.status === "active" ? "disabled" : "active")}
                  >
                    <CheckCircle2 size={14} /> {editingDepartment.status === "active" ? "停用部门" : "启用部门"}
                  </button>
                  <button className="sys-btn sys-btn--danger" disabled={departmentSubmitting} onClick={() => void handleDeleteDepartment(editingDepartment)}><X size={14} /> 删除部门</button>
                </div>
              ) : null}
              <button className="sys-btn sys-btn--default" onClick={() => setCreateDepartmentOpen(false)}><X size={14} /> 取消</button>
              <button className="sys-btn sys-btn--primary" disabled={departmentSubmitting} onClick={() => void handleSubmitDepartment()}><PlusCircle size={14} /> {editingDepartment ? "保存部门" : "创建部门"}</button>
            </div>
          </div>
        </div>
      )}

      {/* 角色编辑抽屉 */}
      <Drawer
        title={editingRole ? "编辑角色" : "新增角色"}
        placement="right"
        width={560}
        onClose={() => setRoleModalOpen(false)}
        open={roleModalOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section">
          <div className="sys-hint"><Info size={14} /> 角色编码由后端自动生成；编辑角色时可同步维护该角色下的启用成员。</div>
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">角色名称</label>
            <div className="sys-field-input-wrap"><ShieldCheck size={16} className="sys-field-prefix" /><input className="sys-field-input" value={roleDraft.name} placeholder="例如：合同审核员" onChange={(event) => setRoleDraft((draft) => ({ ...draft, name: event.target.value }))} /></div>
          </div>
          <div className="sys-field">
            <label className="sys-field-label">说明</label>
            <textarea className="sys-field-textarea" value={roleDraft.description ?? ""} onChange={(event) => setRoleDraft((draft) => ({ ...draft, description: event.target.value }))} />
          </div>
          {editingRole ? (
            <div className="sys-field">
              <label className="sys-field-label">状态</label>
              <Select className="agent-admin-select w-full" classNames={adminSelectClassNames} prefix={<CheckCircle2 className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />} suffixIcon={adminSelectSuffixIcon} value={roleDraft.status} options={[{ value: "active", label: "启用" }, { value: "disabled", label: "停用" }]} onChange={(status) => setRoleDraft((draft) => ({ ...draft, status }))} />
            </div>
          ) : null}
          {editingRole ? (
            <div className="sys-config-group tenant-role-members-group">
              <div className="sys-config-group-title">角色成员</div>
              <div className="sys-field">
                <label className="sys-field-label">包含成员</label>
                <Select
                  mode="multiple"
                  allowClear
                  className="agent-admin-select w-full"
                  classNames={adminSelectClassNames}
                  showSearch
                  prefix={<UsersRound className="h-4 w-4 text-[var(--color-text-tertiary)]" aria-hidden="true" />}
                  suffixIcon={adminSelectSuffixIcon}
                  placeholder="选择该角色下的启用成员"
                  value={roleDraft.membershipIds}
                  options={roleMemberOptions}
                  optionFilterProp="label"
                  onChange={(membershipIds) => setRoleDraft((draft) => ({ ...draft, membershipIds }))}
                />
                <div className="sys-field-hint">勾选表示给人员增加当前角色；取消勾选只取消当前角色，不影响该人员已有的其他角色。</div>
              </div>
            </div>
          ) : null}
        </div>
        <div className="sys-drawer-footer">
          {editingRole ? (
            <button className="sys-btn sys-btn--danger-text" style={{ marginRight: "auto" }} disabled={roleSubmitting} onClick={() => setRoleDeleteTarget(editingRole)}><X size={14} /> 删除角色</button>
          ) : null}
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={() => setRoleModalOpen(false)}><X size={14} /> 取消</button>
            <button className="sys-btn sys-btn--primary" disabled={roleSubmitting} onClick={() => void handleSubmitRole()}><Save size={14} /> 保存角色</button>
          </div>
        </div>
      </Drawer>

      {roleDeleteTarget ? (
        <div className="sys-modal-mask agent-delete-confirm-mask" onClick={() => !roleSubmitting && setRoleDeleteTarget(null)}>
          <div className="sys-modal agent-delete-confirm-modal" role="dialog" aria-modal="true" aria-labelledby="tenant-role-delete-confirm-title" onClick={(event) => event.stopPropagation()}>
            <div className="agent-delete-confirm-body">
              <div className="agent-delete-confirm-icon">
                <AlertTriangle size={24} aria-hidden="true" />
              </div>
              <div className="agent-delete-confirm-content">
                <h2 id="tenant-role-delete-confirm-title">确认删除角色</h2>
                <p>确认删除“{roleDeleteTarget.name}”？删除前必须先移出启用成员，并移除该角色关联的页签或能力分配。</p>
              </div>
            </div>
            <div className="agent-delete-confirm-footer">
              <button type="button" className="sys-btn sys-btn--default" disabled={roleSubmitting} onClick={() => setRoleDeleteTarget(null)}>取消</button>
              <button type="button" className="sys-btn sys-btn--danger" disabled={roleSubmitting} onClick={() => void handleConfirmDeleteRole()}>确认删除</button>
            </div>
          </div>
        </div>
      ) : null}

      {/* 页签分配抽屉 */}
      <Drawer
        title={editingPageGrantGroup ? "编辑页签分配" : "新增页签分配"}
        placement="right"
        width={560}
        onClose={() => setPageGrantModalOpen(false)}
        open={pageGrantModalOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section">
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">分配名称</label>
            <div className="sys-field-input-wrap">
              <Tag size={16} className="sys-field-prefix" />
              <input
                className="sys-field-input"
                value={pageGrantGroupName}
                maxLength={120}
                placeholder="例如：设计与审计入口"
                onChange={(event) => setPageGrantGroupName(event.target.value)}
              />
            </div>
          </div>
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">分配对象</label>
            <div className="sys-field-input-wrap sys-field-input-wrap--select">
              <UserRoundCog size={16} className="sys-field-prefix" aria-hidden="true" />
              <Select
                mode="multiple"
                className="agent-admin-select agent-principal-select w-full"
                classNames={adminSelectClassNames}
                styles={adminPrincipalSelectStyles}
                popupMatchSelectWidth
                showSearch
                suffixIcon={adminSelectSuffixIcon}
                maxTagCount="responsive"
                maxTagTextLength={16}
                placeholder="选择角色、部门或人员"
                value={pageGrantPrincipalKeys}
                options={getPrincipalOptions(organizationOverview)}
                onChange={(principalKeys) => setPageGrantPrincipalKeys(principalKeys as PrincipalSelectionKey[])}
              />
            </div>
          </div>
          <div className="sys-config-group tenant-drawer-option-group">
            <div className="sys-config-group-title">可访问页签</div>
            <div className="tenant-permission-grid">
              {pagePermissionOptions.map((option) => {
                const Icon = option.icon;
                const checked = selectedPageKeys.includes(option.value);
                return (
                  <button
                    key={option.value}
                    type="button"
                    className={`tenant-permission-option ${checked ? "tenant-permission-option--checked" : ""}`}
                    onClick={() => setSelectedPageKeys((keys) => keys.includes(option.value) ? keys.filter((key) => key !== option.value) : [...keys, option.value])}
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
        </div>
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={() => setPageGrantModalOpen(false)}><X size={14} /> 取消</button>
            <button className="sys-btn sys-btn--primary" disabled={pageGrantSubmitting} onClick={() => void handleSubmitPageGrant()}><Save size={14} /> 保存分配</button>
          </div>
        </div>
      </Drawer>

      {/* 能力分配抽屉 */}
      <Drawer
        title={editingGrantGroup ? "编辑能力分配" : "新增能力分配"}
        placement="right"
        width={560}
        onClose={() => setGrantModalOpen(false)}
        open={grantModalOpen}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section">
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">分配名称</label>
            <div className="sys-field-input-wrap">
              <Tag size={16} className="sys-field-prefix" />
              <input
                className="sys-field-input"
                value={grantGroupName}
                maxLength={120}
                placeholder="例如：合同处理能力"
                onChange={(event) => setGrantGroupName(event.target.value)}
              />
            </div>
          </div>
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">分配对象</label>
            <div className="sys-field-input-wrap sys-field-input-wrap--select">
              <UserRoundCog size={16} className="sys-field-prefix" aria-hidden="true" />
              <Select
                mode="multiple"
                className="agent-admin-select agent-principal-select w-full"
                classNames={adminSelectClassNames}
                styles={adminPrincipalSelectStyles}
                popupMatchSelectWidth
                showSearch
                suffixIcon={adminSelectSuffixIcon}
                maxTagCount="responsive"
                maxTagTextLength={16}
                placeholder="选择角色、部门或人员"
                value={grantPrincipalKeys}
                options={getPrincipalOptions(organizationOverview)}
                onChange={(principalKeys) => setGrantPrincipalKeys(principalKeys as PrincipalSelectionKey[])}
              />
            </div>
          </div>
          <div className="sys-config-group tenant-drawer-option-group">
            <div className="sys-config-group-title">能力资源</div>
            {resourceOptions.length === 0 ? (
              <div className="sys-hint">
                <Info size={14} />
                当前租户暂无可分配能力。请先在系统管理的租户配置中启用 MCP、Skill、提示词模板或交付能力。
              </div>
            ) : (
              <div className="tenant-permission-grid">
                {resourceOptions.map((option) => {
                  const checked = grantResourceIds.includes(option.resourceId);
                  return (
                    <button
                      key={`${option.resourceType}:${option.resourceId}`}
                      type="button"
                      className={`tenant-permission-option ${checked ? "tenant-permission-option--checked" : ""}`}
                      onClick={() => setGrantResourceIds((ids) => ids.includes(option.resourceId) ? ids.filter((id) => id !== option.resourceId) : [...ids, option.resourceId])}
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
        <div className="sys-drawer-footer">
          <div className="sys-drawer-footer-right">
            <button className="sys-btn sys-btn--default" onClick={() => setGrantModalOpen(false)}><X size={14} /> 取消</button>
            <button className="sys-btn sys-btn--primary" disabled={grantSubmitting} onClick={() => void handleSubmitGrant()}><Save size={14} /> 保存分配</button>
          </div>
        </div>
      </Drawer>
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
  onEditDepartment,
  membershipUpdatingId,
  onEditMembership,
}: {
  overview: TenantOrganizationOverview | null;
  loading: boolean;
  error: string;
  hasTenantContext: boolean;
  onCreateMember: () => void;
  onCreateDepartment: () => void;
  onEditDepartment: (department: OrganizationDepartment) => void;
  membershipUpdatingId: string | null;
  onEditMembership: (membership: OrganizationMembership) => void;
}) {
  const [selectedDepartmentId, setSelectedDepartmentId] = useState<string>("all");
  const [memberKeyword, setMemberKeyword] = useState("");
  const [expandedDepartmentIds, setExpandedDepartmentIds] = useState<Set<string>>(() => new Set());

  const departmentTreeState = useMemo(() => {
    const emptyState = { items: [] as DepartmentTreeItem[], descendantIdsByDepartment: new Map<string, Set<string>>() };
    if (!overview) return emptyState;
    const memberships = overview.memberships;
    const childrenByParent = new Map<string, OrganizationDepartment[]>();

    // 部门树只渲染真实部门；“全部成员”仅作为筛选入口，避免伪部门影响新增下级部门后的层级展示。
    overview.departments.forEach((department) => {
      const parentKey = department.parentId ?? "root";
      const siblings = childrenByParent.get(parentKey) ?? [];
      siblings.push(department);
      childrenByParent.set(parentKey, siblings);
    });

    childrenByParent.forEach((departments) => {
      departments.sort((left, right) => {
        const sortDiff = (left.sortOrder ?? 0) - (right.sortOrder ?? 0);
        return sortDiff !== 0 ? sortDiff : left.name.localeCompare(right.name, "zh-Hans-CN");
      });
    });

    const collectDepartmentIds = (departmentId: string): Set<string> => {
      const ids = new Set<string>([departmentId]);
      (childrenByParent.get(departmentId) ?? []).forEach((child) => {
        collectDepartmentIds(child.id).forEach((id) => ids.add(id));
      });
      return ids;
    };

    const descendantIdsByDepartment = new Map<string, Set<string>>();
    overview.departments.forEach((department) => {
      descendantIdsByDepartment.set(department.id, collectDepartmentIds(department.id));
    });

    const walk = (parentId: string, level: number): DepartmentTreeItem[] =>
      (childrenByParent.get(parentId) ?? []).flatMap((department) => {
        const departmentIds = descendantIdsByDepartment.get(department.id) ?? new Set([department.id]);
        const memberCount = memberships.filter((membership) => membership.departmentId ? departmentIds.has(membership.departmentId) : false).length;
        const hasChildren = (childrenByParent.get(department.id) ?? []).length > 0;
        return [
          { department, level, memberCount, hasChildren },
          ...(expandedDepartmentIds.has(department.id) ? walk(department.id, level + 1) : []),
        ];
      });

    return { items: walk("root", 0), descendantIdsByDepartment };
  }, [expandedDepartmentIds, overview]);

  const visibleMemberships = useMemo(() => {
    if (!overview) return [];
    const keyword = memberKeyword.trim().toLowerCase();
    const selectedDepartmentIds = selectedDepartmentId === "all" ? null : departmentTreeState.descendantIdsByDepartment.get(selectedDepartmentId) ?? new Set<string>();

    return overview.memberships.filter((membership) => {
      const matchDepartment =
        selectedDepartmentId === "all"
        || (membership.departmentId ? selectedDepartmentIds?.has(membership.departmentId) : false);
      const matchKeyword =
        !keyword
        || membership.userDisplayName.toLowerCase().includes(keyword)
        || membership.roles.some((role) => role.name.toLowerCase().includes(keyword) || role.code.toLowerCase().includes(keyword))
        || (membership.departmentName ?? "").toLowerCase().includes(keyword);
      return matchDepartment && matchKeyword;
    });
  }, [departmentTreeState.descendantIdsByDepartment, memberKeyword, overview, selectedDepartmentId]);
  const membershipPagination = useClientPagination(visibleMemberships, 10);

  const selectedDepartment = overview?.departments.find((department) => department.id === selectedDepartmentId) ?? null;
  const toggleDepartmentExpanded = useCallback((departmentId: string) => {
    setExpandedDepartmentIds((current) => {
      const next = new Set(current);
      if (next.has(departmentId)) {
        next.delete(departmentId);
      } else {
        next.add(departmentId);
      }
      return next;
    });
  }, []);

  if (!hasTenantContext) {
    return (
      <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 text-sm text-[var(--color-text-secondary)]">
        系统管理员需要先在系统管理中选择目标租户，才能查看租户内人员组织。
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="tenant-org-actionbar">
        <div className="tenant-org-actionbar-buttons">
          {selectedDepartment ? (
            <button className="sys-btn sys-btn--default" onClick={() => onEditDepartment(selectedDepartment)} disabled={!overview}>
              <Edit size={14} />
              编辑当前部门
            </button>
          ) : null}
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
              <button
                type="button"
                className={`tenant-dept-tree-item tenant-dept-tree-item--overview ${selectedDepartmentId === "all" ? "tenant-dept-tree-item--active" : ""}`}
                onClick={() => setSelectedDepartmentId("all")}
              >
                <UsersRound size={15} />
                <span className="tenant-dept-tree-name">全部成员</span>
                <span className="tenant-dept-tree-count">{overview.memberships.length}</span>
              </button>
              {departmentTreeState.items.map(({ department, level, memberCount, hasChildren }) => (
                <button
                  key={department.id}
                  type="button"
                  className={`tenant-dept-tree-item ${selectedDepartmentId === department.id ? "tenant-dept-tree-item--active" : ""} ${department.status !== "active" ? "tenant-dept-tree-item--disabled" : ""}`}
                  style={{ paddingLeft: 12 + level * 18 }}
                  aria-expanded={hasChildren ? expandedDepartmentIds.has(department.id) : undefined}
                  onClick={() => {
                    setSelectedDepartmentId(department.id);
                    if (hasChildren) {
                      toggleDepartmentExpanded(department.id);
                    }
                  }}
                >
                  <span className="tenant-dept-tree-leading">
                    {hasChildren ? (
                      <span className="tenant-dept-tree-toggle" aria-hidden="true">
                        {expandedDepartmentIds.has(department.id) ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                      </span>
                    ) : (
                      <span className="tenant-dept-tree-toggle tenant-dept-tree-toggle--placeholder" />
                    )}
                    <Building2 size={15} />
                  </span>
                  <span className="tenant-dept-tree-name">{department.name}</span>
                  {department.status !== "active" ? <span className="tenant-dept-tree-status">停用</span> : null}
                  <span className="tenant-dept-tree-count">{memberCount}</span>
                </button>
              ))}
              {departmentTreeState.items.length === 0 ? (
                <div className="tenant-dept-tree-empty">暂无部门，请先新增部门</div>
              ) : null}
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
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {membershipPagination.pagedItems.map((membership) => (
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
                      <td>
                        <div className="sys-info-tags">
                          {membership.roles.length === 0 ? (
                            <span className="sys-info-tag">未分配角色</span>
                          ) : membership.roles.map((role) => (
                            <span key={role.id} className="sys-info-tag sys-info-tag--info">{role.name}</span>
                          ))}
                        </div>
                      </td>
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
                      <td colSpan={5} className="tenant-member-empty">暂无符合条件的成员</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
            <AdminPagination
              current={membershipPagination.current}
              pageSize={membershipPagination.pageSize}
              total={membershipPagination.total}
              onChange={membershipPagination.onChange}
            />
          </section>
        </div>
      ) : null}
    </div>
  );
}

function getTenantManagementErrorContext(error: unknown, tenantId?: string, extra?: Record<string, string | undefined>) {
  if (error instanceof AgentumApiError) {
    return { tenantId, code: error.code, requestId: error.requestId, ...extra };
  }

  return { tenantId, message: error instanceof Error ? error.message : "unknown", ...extra };
}

function RoleManagementPanel({
  overview,
  loading,
  error,
  hasTenantContext,
  onCreateRole,
  onEditRole,
}: {
  overview: TenantOrganizationOverview | null;
  loading: boolean;
  error: string;
  hasTenantContext: boolean;
  onCreateRole: () => void;
  onEditRole: (role: OrganizationRole) => void;
}) {
  const roles = overview?.roles ?? [];
  const rolePagination = useClientPagination(roles, 10);

  if (!hasTenantContext) {
    return (
      <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] bg-[var(--color-bg-hover)] p-4 text-sm text-[var(--color-text-secondary)]">
        系统管理员需要先在系统管理中选择目标租户，才能维护租户角色。
      </div>
    );
  }

  const roleMemberCount = (roleId: string) => overview?.memberships.filter((membership) => membership.roles.some((role) => role.id === roleId)).length ?? 0;

  return (
    <div className="space-y-4">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 20 }}>
        <p style={{ fontSize: 14, color: "var(--color-text-tertiary)", margin: 0 }}>租户内角色新增、编辑和停用</p>
        <button className="sys-btn sys-btn--primary" onClick={onCreateRole} disabled={!overview}>
          <PlusCircle size={14} />
          新增角色
        </button>
      </div>

      {loading ? (
        <div className="rounded-[var(--radius-md)] border border-[var(--color-border-light)] p-4 text-sm text-[var(--color-text-secondary)]">正在加载角色数据…</div>
      ) : null}

      {error ? (
        <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">{error}</div>
      ) : null}

      {overview ? (
        <>
          <div className="sys-card-grid">
            {rolePagination.pagedItems.map((role) => (
              <article key={role.id} className="sys-card" onClick={() => onEditRole(role)}>
              <div className="sys-card-header">
                <div className="sys-card-avatar sys-card-avatar--tenant"><ShieldCheck size={22} /></div>
                <div className="sys-card-info">
                  <div className="sys-card-name">{role.name}</div>
                  <div className="sys-card-code">{role.scope === "tenant" ? "租户角色" : role.scope}</div>
                </div>
              </div>
              <div className="sys-info-tags">
                <span className={`sys-info-tag ${role.status === "active" ? "sys-info-tag--primary" : ""}`}>{role.status === "active" ? "启用" : "停用"}</span>
                <span className="sys-info-tag">{roleMemberCount(role.id)} 名成员</span>
              </div>
              <div className="sys-card-footer">
                <span className="sys-card-footer-time">成员分配请在人员组织中编辑成员</span>
                <div className="sys-card-footer-actions" onClick={(e) => e.stopPropagation()}>
                  <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onEditRole(role)}><Edit size={14} /> 编辑</button>
                </div>
              </div>
              </article>
            ))}
          </div>
          <AdminPagination
            current={rolePagination.current}
            pageSize={rolePagination.pageSize}
            total={rolePagination.total}
            onChange={rolePagination.onChange}
          />
        </>
      ) : null}
    </div>
  );
}

function ResourceAuthorizationPanel({
  pageGrants,
  loading,
  error,
  grants,
  onCreateGrant,
  onCreatePageGrant,
  onEditPageGrant,
  onEditGrant,
  onDeletePageGrant,
  onDeleteGrant,
}: {
  pageGrants: PageGrant[];
  loading: boolean;
  error: string;
  grants: ResourceGrant[];
  onCreateGrant: () => void;
  onCreatePageGrant: () => void;
  onEditPageGrant: (grant: PageGrant) => void;
  onEditGrant: (grant: ResourceGrant) => void;
  onDeletePageGrant: (grant: PageGrant) => void;
  onDeleteGrant: (grant: ResourceGrant) => void;
}) {
  const pageGrantPagination = useClientPagination(pageGrants, 10);
  const grantPagination = useClientPagination(grants, 10);

  return (
    <div className="tenant-resource-panel">
      <div className="tenant-resource-toolbar">
        <p>分配模块入口和可用能力池</p>
        <div className="tenant-resource-toolbar-actions">
          <button className="sys-btn sys-btn--default" onClick={onCreatePageGrant}><PlusCircle size={14} /> 新增页签</button>
          <button className="sys-btn sys-btn--primary" onClick={onCreateGrant}><PlusCircle size={14} /> 新增能力</button>
        </div>
      </div>

      {error ? (
        <div className="rounded-[var(--radius-md)] border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-100">{error}</div>
      ) : null}

      {loading ? (
        <div className="sys-preview-card"><div className="sys-preview-card-title"><UserRoundCog size={16} /> 正在加载资源分配</div></div>
      ) : null}

      <div className="tenant-auth-section-block">
        <div className="tenant-auth-section-head tenant-auth-section-head--loose">
          <div>
            <h3>页签分配</h3>
          </div>
        </div>
        {pageGrants.length === 0 && !loading ? (
          <div className="sys-surface-empty">
            <Empty description="暂无页签分配" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        ) : (
          <div className="sys-card-grid tenant-auth-card-grid">
            {pageGrantPagination.pagedItems.map((grant) => (
              <article key={grant.id} className="sys-card tenant-auth-card" onClick={() => onEditPageGrant(grant)}>
                <div className="sys-card-header">
                  <div className="sys-card-avatar sys-card-avatar--tenant"><ShieldCheck size={22} /></div>
                  <div className="sys-card-info">
                    <div className="sys-card-name">{grant.groupName}</div>
                  </div>
                </div>
                <div className="tenant-auth-card-lines">
                  <div className="tenant-auth-card-line">
                    <span className="tenant-auth-card-line-label">页签</span>
                    <div className="sys-info-tags">
                      {grant.pages.map((page) => <span key={page.pageKey} className="sys-info-tag sys-info-tag--primary">{page.pageName}</span>)}
                    </div>
                  </div>
                  <div className="tenant-auth-card-line">
                    <span className="tenant-auth-card-line-label">对象</span>
                    <div className="sys-info-tags">
                      {grant.principals.map((principal) => <span key={`${principal.principalType}:${principal.principalId}`} className="sys-info-tag">{formatPrincipalType(principal.principalType)} · {principal.principalName}</span>)}
                    </div>
                  </div>
                </div>
                <div className="sys-card-footer">
                  <span className="sys-card-footer-time">创建于 {formatDateTime(grant.createdAt)}</span>
                  <div className="sys-card-footer-actions" onClick={(e) => e.stopPropagation()}>
                    <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onEditPageGrant(grant)}><Edit size={14} /> 编辑</button>
                    <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onDeletePageGrant(grant)}><X size={14} /> 删除</button>
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}
        <AdminPagination
          current={pageGrantPagination.current}
          pageSize={pageGrantPagination.pageSize}
          total={pageGrantPagination.total}
          onChange={pageGrantPagination.onChange}
        />
      </div>

      <div className="tenant-auth-section-block">
        <div className="tenant-auth-section-head tenant-auth-section-head--loose">
          <div>
            <h3>能力分配</h3>
          </div>
        </div>

        {!loading && grants.length === 0 ? (
          <div className="sys-surface-empty">
            <Empty description="暂无能力分配" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        ) : null}

        {grants.length > 0 ? (
          <div className="sys-card-grid tenant-auth-card-grid">
            {grantPagination.pagedItems.map((grant) => (
              <article key={grant.id} className="sys-card tenant-auth-card" onClick={() => onEditGrant(grant)}>
                <div className="sys-card-header">
                  <div className="sys-card-avatar sys-card-avatar--tenant"><UserRoundCog size={22} /></div>
                  <div className="sys-card-info">
                    <div className="sys-card-name">{grant.groupName}</div>
                  </div>
                </div>
                <div className="tenant-auth-card-lines">
                  <div className="tenant-auth-card-line">
                    <span className="tenant-auth-card-line-label">能力</span>
                    <div className="sys-info-tags">
                      {grant.resources.map((resource) => (
                        <span key={`${resource.resourceType}:${resource.resourceId}`} className="sys-info-tag sys-info-tag--primary">
                          {formatResourceType(resource.resourceType)} · {resource.resourceName}
                        </span>
                      ))}
                    </div>
                  </div>
                  <div className="tenant-auth-card-line">
                    <span className="tenant-auth-card-line-label">对象</span>
                    <div className="sys-info-tags">
                      {grant.principals.map((principal) => <span key={`${principal.principalType}:${principal.principalId}`} className="sys-info-tag">{formatPrincipalType(principal.principalType)} · {principal.principalName}</span>)}
                    </div>
                  </div>
                </div>
                <div className="sys-card-footer">
                  <span className="sys-card-footer-time">创建于 {formatDateTime(grant.createdAt)}</span>
                  <div className="sys-card-footer-actions" onClick={(e) => e.stopPropagation()}>
                    <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onEditGrant(grant)}><Edit size={14} /> 编辑</button>
                    <button className="sys-btn sys-btn--text sys-btn--sm" onClick={() => onDeleteGrant(grant)}><X size={14} /> 删除</button>
                  </div>
                </div>
              </article>
            ))}
          </div>
        ) : null}
        <AdminPagination
          current={grantPagination.current}
          pageSize={grantPagination.pageSize}
          total={grantPagination.total}
          onChange={grantPagination.onChange}
        />
      </div>
    </div>
  );
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

function formatPrincipalType(type: string): string {
  if (type === "role") return "角色";
  if (type === "department") return "部门";
  if (type === "user") return "用户";
  return type;
}

function formatPrincipalNames(principals: GrantPrincipal[]): string {
  if (principals.length === 0) return "未选择对象";
  return principals.map((principal) => principal.principalName).join("、");
}

function getPrincipalOptions(overview: TenantOrganizationOverview | null) {
  if (!overview) return [];
  return [
    {
      label: "角色",
      options: overview.roles.map((role) => ({ value: `role:${role.id}`, label: role.name })),
    },
    {
      label: "部门",
      options: overview.departments.map((department) => ({ value: `department:${department.id}`, label: department.name })),
    },
    {
      label: "人员",
      options: overview.members.map((member) => ({ value: `user:${member.id}`, label: `${member.displayName} (${member.username})` })),
    },
  ];
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
