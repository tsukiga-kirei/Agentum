package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TenantResourcePermissionRequest(
    @NotBlank(message = "请选择资源类型")
    @Size(max = 40, message = "资源类型不能超过 40 个字符")
    String resourceType,

    @NotBlank(message = "请选择资源")
    @Size(max = 80, message = "资源 ID 不能超过 80 个字符")
    String resourceId,

    List<String> actions
) {
}
