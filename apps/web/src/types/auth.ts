// 认证相关类型契约，与后端 auth 模块的 API 返回对齐。
// 当前仅前端占位，后续接入后端 JWT/Session 后，字段应从 shared-contract 生成。

/** 主题模式 */
export type ThemeMode = "light" | "dark";

/** 登录入口类型：业务人员 / 空间管理员 / 系统管理员 */
export type PortalType = "business" | "space_admin" | "system_admin";

/** 登录页可选租户。后续由 GET /api/public/tenants 返回。 */
export type TenantOption = {
  id: string;
  name: string;
  code: string;
};

/** 用户角色 */
export type UserRole =
  | "system_admin"
  | "space_admin"
  | "designer"
  | "agent_admin"
  | "capability_admin"
  | "reviewer"
  | "executor"
  | "observer";

/** 登录请求参数 */
export type LoginRequest = {
  username: string;
  password: string;
  portal: PortalType;
  tenantId?: string;
};

/** 登录响应中的用户信息 */
export type AuthUser = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  avatar: string;
  role: UserRole;
  tenantId: string | null;
  tenantName: string;
  tenantCode: string;
  organization: string;
  space: string;
  lastLoginAt: string;
};

/** 登录响应 */
export type LoginResponse = {
  token: string;
  user: AuthUser;
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
  space: string;
  timezone: string;
  language: string;
  theme: "light" | "dark" | "system";
  lastLoginAt: string;
  lastLoginDevice: string;
  accountStatus: "active" | "disabled" | "locked";
  mfaEnabled: boolean;
};
