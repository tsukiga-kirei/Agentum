package com.agentum.asset.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
        String riskLevel,
        String status,
        boolean assignedToMe,
        String assignmentScope,
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
        String visibility,
        String sourceType,
        UUID baseSystemCapabilityId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record CreateMyAssetRequest(
        @NotBlank @Size(max = 40) String assetType,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 100) String code,
        @Size(max = 40) String version,
        @Size(max = 1000) String description,
        @Size(max = 20) String riskLevel,
        @Size(max = 30) String visibility,
        UUID baseSystemCapabilityId,
        Map<String, Object> config
    ) {
    }
}
