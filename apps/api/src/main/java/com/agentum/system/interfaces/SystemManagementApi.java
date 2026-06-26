package com.agentum.system.interfaces;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public record CreateTenantAdminRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 50) String displayName,
        @NotBlank @Size(max = 100) String password,
        @Size(max = 100) String email,
        UUID departmentId
    ) {
    }

    public record UpdateTenantAdminProfileRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 50) String displayName,
        @Size(max = 100) String email
    ) {
    }

    public record UpdateTenantAdminStatusRequest(
        @NotBlank @Size(max = 30) String status
    ) {
    }

    public record TenantSsoProviderRow(
        UUID id,
        UUID tenantId,
        String providerType,
        String name,
        String status,
        String issuer,
        String clientId,
        boolean clientSecretConfigured,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwksUri,
        boolean basicPasswordConfigured,
        String allowedIpRanges,
        String allowedDomains
    ) {
    }

    public record SaveTenantSsoProviderRequest(
        @NotBlank @Size(max = 30) String providerType,
        @Size(max = 160) String name,
        @Size(max = 30) String status,
        @Size(max = 500) String issuer,
        @Size(max = 200) String clientId,
        @Size(max = 2000) String clientSecret,
        @Size(max = 800) String authorizationEndpoint,
        @Size(max = 800) String tokenEndpoint,
        @Size(max = 800) String jwksUri,
        @Size(max = 2000) String basicPassword,
        @Size(max = 1000) String allowedIpRanges,
        @Size(max = 1000) String allowedDomains
    ) {
    }

    public record ModelProviderRow(
        UUID id,
        String name,
        String providerType,
        String baseUrl,
        String defaultModel,
        boolean apiKeyConfigured,
        String status,
        String connectivityStatus,
        Instant connectivityCheckedAt,
        Integer maxTokens,
        boolean reasoningModel
    ) {
    }

    public record ModelProviderTypeRow(
        String code,
        String name,
        String description,
        String authScheme,
        String defaultBaseUrl,
        String modelListEndpoint
    ) {
    }

    public record ModelProviderTestResult(
        UUID providerId,
        String status,
        String summary,
        List<String> availableModels,
        long latencyMs,
        Instant checkedAt,
        String connectivityStatus
    ) {
    }

    public record CreateModelProviderRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 80) String providerType,
        @Size(max = 500) String baseUrl,
        @NotBlank @Size(max = 160) String defaultModel,
        @Size(max = 2000) String apiKey,
        @Size(max = 30) String status,
        @NotNull @Min(256) @Max(131072) Integer maxTokens,
        boolean reasoningModel
    ) {
    }

    public record UpdateModelProviderRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 80) String providerType,
        @Size(max = 500) String baseUrl,
        @NotBlank @Size(max = 160) String defaultModel,
        @Size(max = 2000) String apiKey,
        @Size(max = 30) String status,
        @NotNull @Min(256) @Max(131072) Integer maxTokens,
        boolean reasoningModel
    ) {
    }

    public record CapabilityRow(
        UUID id,
        String capabilityType,
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String status,
        Map<String, Object> config,
        String connectivityStatus,
        Instant connectivityCheckedAt
    ) {
    }

    public record CreateCapabilityRequest(
        @NotBlank @Size(max = 40) String capabilityType,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 100) String code,
        @Size(max = 40) String version,
        @Size(max = 1000) String description,
        @Size(max = 20) String riskLevel,
        @Size(max = 30) String status,
        Map<String, Object> config
    ) {
    }

    public record UpdateCapabilityRequest(
        @NotBlank @Size(max = 40) String capabilityType,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 40) String version,
        @Size(max = 1000) String description,
        @Size(max = 20) String riskLevel,
        @Size(max = 30) String status,
        Map<String, Object> config
    ) {
    }

    public record CapabilityToolRow(
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {
    }

    public record CapabilityTestResult(
        UUID capabilityId,
        String status,
        String summary,
        List<CapabilityToolRow> tools,
        Instant checkedAt,
        String connectivityStatus
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

    public record UpdateGrantStatusRequest(
        @NotBlank @Size(max = 30) String status
    ) {
    }

    public record TenantModelAssignmentRow(
        UUID id,
        UUID tenantId,
        UUID providerId,
        String providerName,
        String providerType,
        String defaultModel,
        String assignmentStatus
    ) {
    }

    public record CreateTenantModelAssignmentRequest(
        @NotNull UUID tenantId,
        @NotNull UUID providerId,
        @Size(max = 160) String defaultModel,
        @Size(max = 30) String status
    ) {
    }

    public record UpdateTenantModelAssignmentStatusRequest(
        @NotBlank @Size(max = 30) String status
    ) {
    }
}
