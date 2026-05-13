package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateResourceGrantRequest(
    @NotBlank(message = "请选择授权主体类型") String principalType,
    @NotNull(message = "请选择授权主体") UUID principalId,
    @NotBlank(message = "请选择资源类型") String resourceType,
    @NotNull(message = "请选择资源") UUID resourceId,
    List<String> actions
) {
}
