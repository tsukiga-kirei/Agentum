package com.agentum.organization.interfaces;

import java.util.UUID;

// 成员部门可被清空，表示成员暂未归属具体部门。
public record UpdateMembershipDepartmentRequest(
    UUID departmentId
) {
}
