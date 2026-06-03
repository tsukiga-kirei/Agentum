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
  status: string;
  description: string;
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
  defaultMembership: boolean;
  tenantAdmin: boolean;
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

export type UpdateDepartmentStatusRequest = {
  status: "active" | "disabled";
};

export type UpdateRoleStatusRequest = {
  status: "active" | "disabled";
};

export type CreateTenantRoleRequest = {
  name: string;
  description?: string;
};

export type UpdateTenantRoleRequest = {
  name: string;
  description?: string;
  status?: "active" | "disabled";
  membershipIds?: string[];
};

export type UpdateMembershipRoleRequest = {
  roleIds: string[];
};

export type UpdateMemberProfileRequest = {
  username: string;
  displayName: string;
  email?: string;
};

export type UpdateMembershipDepartmentRequest = {
  departmentId?: string;
};

export type UpdateMembershipStatusRequest = {
  status: "active" | "disabled";
};

export type PrincipalType = "role" | "department" | "user";

export type GrantPrincipal = {
  principalType: PrincipalType;
  principalId: string;
  principalName: string;
};

export type PageGrantItem = {
  pageKey: string;
  pageName: string;
};

export type ResourceGrantItem = {
  resourceType: string;
  resourceId: string;
  resourceName: string;
  resourceCode: string;
};

export type ResourceGrant = {
  id: string;
  groupName: string;
  principals: GrantPrincipal[];
  resources: ResourceGrantItem[];
  createdAt: string;
};

export type PageGrant = {
  id: string;
  groupName: string;
  principals: GrantPrincipal[];
  pages: PageGrantItem[];
  createdAt: string;
};

export type CreateResourceGrantRequest = {
  groupName: string;
  principals: Array<{
    principalType: PrincipalType;
    principalId: string;
  }>;
  resources: Array<{
    resourceType: string;
    resourceId: string;
  }>;
};

export type CreatePageGrantRequest = {
  groupName: string;
  principals: Array<{
    principalType: PrincipalType;
    principalId: string;
  }>;
  pageKeys: string[];
};
