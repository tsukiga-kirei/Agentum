package com.agentum.organization.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateResourceGrantRequest(
    @NotBlank(message = "请输入分配名称") @Size(max = 120, message = "分配名称不能超过 120 个字符") String groupName,
    @NotEmpty(message = "请选择分配对象") List<@Valid GrantPrincipalRequest> principals,
    @NotEmpty(message = "请选择能力资源") List<@Valid ResourceGrantItemRequest> resources
) {
}
