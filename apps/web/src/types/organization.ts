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
  roleId: string;
  roleName: string;
  roleCode: string;
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
  code?: string;
  parentId?: string;
  sortOrder?: number;
};
