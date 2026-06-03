package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;

public record UpdateDepartmentStatusRequest(
    @NotBlank(message = "请输入部门状态") String status
) {
}
