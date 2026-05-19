package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrantPrincipalRequest(
    @NotBlank(message = "请选择分配对象类型") String principalType,
    @NotNull(message = "请选择分配对象") UUID principalId
) {
}
