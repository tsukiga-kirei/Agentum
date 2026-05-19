package com.agentum.organization.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePageGrantRequest(
    @NotBlank(message = "请输入分配名称") @Size(max = 120, message = "分配名称不能超过 120 个字符") String groupName,
    @NotEmpty(message = "请选择分配对象") List<@Valid GrantPrincipalRequest> principals,
    @NotEmpty(message = "请选择页签") List<@NotBlank(message = "请选择页签") String> pageKeys
) {
}
