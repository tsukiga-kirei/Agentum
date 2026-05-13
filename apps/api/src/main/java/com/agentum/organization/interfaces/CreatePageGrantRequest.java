package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreatePageGrantRequest(
    @NotBlank(message = "请选择授权主体类型") String principalType,
    @NotNull(message = "请选择授权主体") UUID principalId,
    @NotBlank(message = "请选择页签") String pageKey
) {
}
