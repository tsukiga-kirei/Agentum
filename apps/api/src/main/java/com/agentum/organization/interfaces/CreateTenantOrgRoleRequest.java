package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateTenantOrgRoleRequest(
    @NotBlank(message = "请输入角色名称")
    @Size(max = 120, message = "角色名称不能超过 120 个字符")
    String name,

    @Size(max = 500, message = "角色说明不能超过 500 个字符")
    String description,

    List<String> pagePermissions,

    List<TenantResourcePermissionRequest> resourcePermissions
) {
}
