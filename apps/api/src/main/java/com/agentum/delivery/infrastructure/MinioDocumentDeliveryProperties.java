package com.agentum.delivery.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentum.storage.minio")
public record MinioDocumentDeliveryProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    String objectPrefix,
    boolean autoCreateBucket
) {

    public MinioDocumentDeliveryProperties {
        endpoint = hasText(endpoint) ? endpoint.trim() : "http://localhost:9000";
        accessKey = hasText(accessKey) ? accessKey.trim() : "agentum";
        secretKey = hasText(secretKey) ? secretKey.trim() : "agentum_dev_password";
        bucket = hasText(bucket) ? bucket.trim() : "agentum";
        objectPrefix = normalizePrefix(objectPrefix);
    }

    public String normalizedObjectPrefix() {
        return objectPrefix;
    }

    private static String normalizePrefix(String value) {
        if (!hasText(value)) {
            return "deliveries/documents";
        }
        String normalized = value.trim()
            .replace("\\", "/")
            .replaceAll("^/+", "")
            .replaceAll("/+$", "")
            .replaceAll("/{2,}", "/");
        return normalized.isBlank() ? "deliveries/documents" : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
