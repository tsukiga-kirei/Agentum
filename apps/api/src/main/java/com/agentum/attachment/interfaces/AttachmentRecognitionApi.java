package com.agentum.attachment.interfaces;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AttachmentRecognitionApi {

    private AttachmentRecognitionApi() {
    }

    public record Settings(
        boolean recognitionEnabled,
        String recognitionEngine,
        int maxFileSizeMb,
        int maxFilesPerField,
        int maxExtractedChars,
        String retentionPolicy,
        int retentionDays,
        List<String> mineruSupportedExtensions,
        String mineruEndpoint,
        boolean mineruApiKeyConfigured,
        String mineruBackend,
        String mineruParseMethod,
        String mineruLanguage,
        boolean mineruEnableFormula,
        boolean mineruEnableTable,
        int mineruConnectTimeoutSeconds,
        int mineruReadTimeoutSeconds,
        Instant updatedAt
    ) {
    }

    public record UpdateSettingsRequest(
        boolean recognitionEnabled,
        @NotBlank @Size(max = 20) String recognitionEngine,
        @Min(1) @Max(200) int maxFileSizeMb,
        @Min(1) @Max(20) int maxFilesPerField,
        @Min(1000) @Max(2000000) int maxExtractedChars,
        @NotBlank @Size(max = 20) String retentionPolicy,
        @Min(1) @Max(3650) int retentionDays,
        @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 20) String> mineruSupportedExtensions,
        @Size(max = 800) String mineruEndpoint,
        @Size(max = 2000) String mineruApiKey,
        boolean clearMineruApiKey,
        @NotBlank @Size(max = 80) String mineruBackend,
        @NotBlank @Size(max = 20) String mineruParseMethod,
        @NotBlank @Size(max = 40) String mineruLanguage,
        boolean mineruEnableFormula,
        boolean mineruEnableTable,
        @Min(1) @Max(120) int mineruConnectTimeoutSeconds,
        @Min(10) @Max(3600) int mineruReadTimeoutSeconds
    ) {
    }

    public record TestConnectionRequest(
        @NotBlank @Size(max = 800) String mineruEndpoint,
        @Size(max = 2000) String mineruApiKey,
        boolean useSavedApiKey,
        @Min(1) @Max(120) int connectTimeoutSeconds
    ) {
    }

    public record TestConnectionResult(String status, String summary, long latencyMs) {
    }
}
