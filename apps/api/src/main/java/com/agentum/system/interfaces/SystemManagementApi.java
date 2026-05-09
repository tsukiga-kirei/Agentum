package com.agentum.system.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 系统管理 REST 契约使用的请求与响应模型；仅应由 system_admin 调用对应 Controller。
 */
public final class SystemManagementApi {

    private SystemManagementApi() {
    }

    public record Summary(
        long tenantTotal,
        long tenantActive,
        long modelProviderTotal,
        long systemCapabilityTotal,
        long tenantCapabilityGrantTotal
    ) {
    }

    public record TenantRow(UUID id, String name, String code, String status) {
    }

    public record CreateTenantRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 100) String code,
        @NotBlank @Size(max = 50) String adminUsername,
        @NotBlank @Size(max = 50) String adminDisplayName,
        @NotBlank @Size(max = 100) String adminPassword,
        @Size(max = 100) String adminEmail
    ) {
    }

    public record UpdateTenantStatusRequest(
        @NotBlank @Size(max = 30) String status
    ) {
    }

    public record ModelProviderRow(
        UUID id,
        String name,
        String providerType,
        String baseUrl,
        String defaultModel,
        String status
    ) {
    }

    public record CreateModelProviderRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 80) String providerType,
        @Size(max = 500) String baseUrl,
        @Size(max = 160) String defaultModel,
        @Size(max = 30) String status
    ) {
    }

    public record CapabilityRow(
        UUID id,
        String capabilityType,
        String name,
        String code,
        String version,
        String riskLevel,
        String status
    ) {
    }

    public record CreateCapabilityRequest(
        @NotBlank @Size(max = 40) String capabilityType,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 100) String code,
        @Size(max = 40) String version,
        @Size(max = 20) String riskLevel,
        @Size(max = 30) String status
    ) {
    }

    public record GrantRow(
        UUID id,
        UUID tenantId,
        String tenantName,
        String tenantCode,
        UUID capabilityId,
        String capabilityName,
        String capabilityCode,
        String capabilityType,
        String grantStatus
    ) {
    }

    public record CreateGrantRequest(
        @NotNull UUID tenantId,
        @NotNull UUID capabilityId,
        @Size(max = 30) String status
    ) {
    }
}
