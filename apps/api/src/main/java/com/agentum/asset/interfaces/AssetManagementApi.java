package com.agentum.asset.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssetManagementApi {

    private AssetManagementApi() {
    }

    public record AssetSummary(
        long openedToMeSystemTotal,
        long tenantSystemPoolTotal,
        long myAssetTotal
    ) {
    }

    public record SystemCapabilityAssetRow(
        UUID id,
        String assetType,
        String name,
        String code,
        String version,
        String description,
        String promptContent,
        String riskLevel,
        String status,
        boolean assignedToMe,
        String assignmentScope,
        String openSource,
        String accessLevel,
        String ownerDisplayName,
        Instant openedAt
    ) {
    }

    public record MyAssetRow(
        UUID id,
        String assetType,
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String status,
        String readScope,
        String editScope,
        String accessLevel,
        boolean canManageAccess,
        String sourceType,
        UUID baseSystemCapabilityId,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
    ) {
    }

    public record MyAssetDetail(
        UUID id,
        String assetType,
        String name,
        String code,
        String version,
        String description,
        String riskLevel,
        String status,
        String readScope,
        String editScope,
        String accessLevel,
        boolean canManageAccess,
        String sourceType,
        UUID baseSystemCapabilityId,
        Map<String, Object> config,
        List<UUID> readUserIds,
        List<UUID> editUserIds,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
    ) {
    }

    public record ShareableMemberRow(
        UUID userId,
        String username,
        String displayName
    ) {
    }

    public record CreateMyAssetRequest(
        @NotBlank @Size(max = 40) String assetType,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 100) String code,
        @Size(max = 40) String version,
        @Size(max = 1000) String description,
        @Size(max = 20) String riskLevel,
        @Size(max = 30) String readScope,
        @Size(max = 30) String editScope,
        UUID baseSystemCapabilityId,
        Map<String, Object> config,
        List<UUID> readUserIds,
        List<UUID> editUserIds
    ) {
    }

    public record UpdateMyAssetRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 40) String version,
        @Size(max = 1000) String description,
        @Size(max = 20) String riskLevel,
        Map<String, Object> config
    ) {
    }

    public record UpdateMyAssetAccessRequest(
        @NotBlank @Size(max = 30) String readScope,
        @NotBlank @Size(max = 30) String editScope,
        List<UUID> readUserIds,
        List<UUID> editUserIds
    ) {
    }
}
