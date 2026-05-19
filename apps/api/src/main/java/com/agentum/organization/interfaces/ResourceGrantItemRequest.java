package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ResourceGrantItemRequest(
    @NotBlank(message = "请选择资源类型") String resourceType,
    @NotNull(message = "请选择资源") UUID resourceId
) {
}
