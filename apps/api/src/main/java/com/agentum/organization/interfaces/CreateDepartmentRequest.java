package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

// 部门创建只声明层级意图，parentId 是否属于当前租户必须由 service 层重新校验。
public record CreateDepartmentRequest(
    @NotBlank(message = "请输入部门名称") @Size(max = 160, message = "部门名称不能超过 160 个字符") String name,
    @Size(max = 80, message = "部门编码不能超过 80 个字符") String code,
    UUID parentId,
    Integer sortOrder
) {
}
