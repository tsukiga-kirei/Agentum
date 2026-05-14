// 组织管理类型对齐后端租户人员组织概览接口。
// 该文件仍是手写契约，后续接入 OpenAPI 生成后应替换为 generated types 的再导出。
export type OrganizationMember = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  status: string;
  lastLoginAt: string;
};

export type OrganizationDepartment = {
  id: string;
  parentId: string | null;
  name: string;
  code: string;
  sortOrder: number;
  status: string;
};

export type OrganizationRole = {
  id: string;
  code: string;
  name: string;
  scope: string;
  status: string;
};

export type OrganizationMembership = {
  id: string;
  userId: string;
  userDisplayName: string;
  departmentId: string | null;
  departmentName: string;
  roles: Array<{
    id: string;
    code: string;
    name: string;
  }>;
  spaceCode: string;
  defaultMembership: boolean;
  status: string;
};

export type TenantOrganizationOverview = {
  tenantId: string;
  tenantName: string;
  tenantCode: string;
  members: OrganizationMember[];
  departments: OrganizationDepartment[];
  roles: OrganizationRole[];
  memberships: OrganizationMembership[];
};

export type PageResponse<T> = {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
};

export type TenantOrgRole = {
  id: string;
  name: string;
  description: string;
  pagePermissions: string[];
  resourcePermissions: TenantResourcePermission[];
  systemRole: boolean;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type TenantResourcePermission = {
  resourceType: string;
  resourceId: string;
  resourceName: string;
  resourceCode: string;
  actions: string[];
};

export type TenantResourcePermissionRequest = {
  resourceType: string;
  resourceId: string;
  actions: string[];
};

export type TenantResourceOption = {
  resourceType: string;
  resourceId: string;
  resourceName: string;
  resourceCode: string;
  version: string;
  riskLevel: string;
};

export type CreateTenantOrgRoleRequest = {
  name: string;
  description?: string;
  pagePermissions: string[];
  resourcePermissions: TenantResourcePermissionRequest[];
};

export type UpdateTenantOrgRoleRequest = {
  name: string;
  description?: string;
  pagePermissions: string[];
  resourcePermissions: TenantResourcePermissionRequest[];
  status: "active" | "disabled";
};

// 新增成员会携带初始密码，调用方必须避免写入日志、URL、localStorage 或错误详情。
export type CreateMemberRequest = {
  username: string;
  password: string;
  displayName: string;
  email?: string;
  departmentId?: string;
  roleId: string;
  spaceCode?: string;
};

// 部门新增只表达层级和排序意图；上级部门归属必须由后端按租户再次校验。
export type CreateDepartmentRequest = {
  name: string;
  parentId?: string;
  sortOrder?: number;
};

export type UpdateDepartmentRequest = {
  name: string;
  parentId?: string;
  sortOrder?: number;
};

export type CreateTenantRoleRequest = {
  name: string;
  description?: string;
};

export type UpdateTenantRoleRequest = {
  name: string;
  description?: string;
  status: "active" | "disabled";
  membershipIds?: string[];
};

export type UpdateMembershipRoleRequest = {
  roleIds: string[];
};

export type UpdateMembershipDepartmentRequest = {
  departmentId?: string;
};

export type UpdateMembershipStatusRequest = {
  status: "active" | "disabled";
};

export type PrincipalType = "role" | "department" | "user";

export type ResourceGrant = {
  id: string;
  principalType: PrincipalType;
  principalId: string;
  principalName: string;
  resourceType: string;
  resourceId: string;
  resourceName: string;
  resourceCode: string;
  actions: string[];
  createdAt: string;
};

export type PageGrant = {
  id: string;
  principalType: PrincipalType;
  principalId: string;
  principalName: string;
  pageKey: string;
  pageName: string;
  createdAt: string;
};

export type CreateResourceGrantRequest = {
  principalType: PrincipalType;
  principalId: string;
  resourceType: string;
  resourceId: string;
  actions: string[];
};

export type CreatePageGrantRequest = {
  principalType: PrincipalType;
  principalId: string;
  pageKey: string;
};
