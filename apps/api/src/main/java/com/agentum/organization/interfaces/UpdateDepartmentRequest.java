package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateDepartmentRequest(
    @NotBlank(message = "请输入部门名称") @Size(max = 160, message = "部门名称不能超过 160 个字符") String name,
    UUID parentId,
    Integer sortOrder
) {
}
