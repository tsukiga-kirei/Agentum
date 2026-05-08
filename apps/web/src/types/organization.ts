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

export type CreateMemberRequest = {
  username: string;
  password: string;
  displayName: string;
  email?: string;
  departmentId?: string;
  roleId: string;
  spaceCode?: string;
};
