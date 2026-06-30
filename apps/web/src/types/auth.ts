// 认证相关类型契约，与后端 auth 模块的 API 返回对齐。
// 角色分配模型支持一个用户多角色多租户和角色切换。

/** 主题模式 */
export type ThemeMode = "light" | "dark";

/** 登录入口类型：业务人员 / 租户管理员 / 系统管理员 */
export type PortalType = "business" | "tenant_admin" | "system_admin";

/** 登录页可选租户。后续由 GET /api/public/tenants 返回。 */
export type TenantOption = {
  id: string;
  name: string;
  code: string;
};

/** 租户公开 SSO 身份源，登录页只用它渲染企业登录按钮。 */
export type SsoProviderOption = {
  id: string;
  name: string;
  providerType: "oidc" | "basic" | "saml";
};

/** 系统角色（三大入口角色），用于 user_role_assignments.role */
export type SystemRole = "system_admin" | "tenant_admin" | "business";

/**
 * 用户角色（兼容旧代码和未来租户内角色扩展）。
 * 第一阶段以系统角色为主；后续租户内自定义角色会从 tenant_org_roles 获取。
 */
export type UserRole =
  | "system_admin"
  | "tenant_admin"
  | "business"
  // 以下为历史兼容，后续通过 tenant_org_roles 替代
  | "designer"
  | "agent_admin"
  | "capability_admin"
  | "reviewer"
  | "executor"
  | "observer";

/** 角色分配信息，来自 user_role_assignments 表 */
export type RoleInfo = {
  id: string;
  role: SystemRole;
  tenantId: string | null;
  tenantName: string | null;
  label: string;
};

/** 菜单项，由后端根据角色计算返回 */
export type MenuItem = {
  key: string;
  label: string;
  icon: string;
  description: string;
};

/** 登录请求参数 */
export type LoginRequest = {
  username: string;
  password: string;
  portal: PortalType;
  tenantId?: string;
};

/** 首次部署初始化状态。 */
export type BootstrapStatusResponse = {
  needsSetup: boolean;
};

/** 首次部署创建系统管理员请求。 */
export type BootstrapAdminRequest = {
  username: string;
  displayName: string;
  password: string;
  email?: string;
};

/** 登录响应中的用户信息 */
export type AuthUser = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  avatar: string;
  role: SystemRole;
  tenantId: string | null;
  tenantName: string;
  tenantCode: string;
  organization: string;
  lastLoginAt: string;
};

/** 登录响应，包含完整角色列表和菜单 */
export type LoginResponse = {
  token: string;
  user: AuthUser;
  roles: RoleInfo[];
  activeRole: RoleInfo;
  permissions: string[];
  menus: MenuItem[];
};

/** /api/auth/me 响应（结构与 LoginResponse 相同，token 为 null） */
export type MeResponse = {
  token: string | null;
  user: AuthUser;
  roles: RoleInfo[];
  activeRole: RoleInfo;
  permissions: string[];
  menus: MenuItem[];
};

/** 角色切换请求 */
export type SwitchRoleRequest = {
  roleId: string;
};

/** 角色切换响应 */
export type SwitchRoleResponse = {
  token: string;
  user: AuthUser;
  activeRole: RoleInfo;
  permissions: string[];
  menus: MenuItem[];
};

/** 个人资料页展示的用户详细信息 */
export type UserProfile = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  phone: string;
  avatar: string;
  role: UserRole;
  organization: string;
  timezone: string;
  language: string;
  theme: "light" | "dark" | "system";
  lastLoginAt: string;
  lastLoginDevice: string;
  accountStatus: "active" | "disabled" | "locked";
  mfaEnabled: boolean;
};
