package com.agentum.delivery.application;

public record DocumentDeliveryArtifact(
    String fileName,
    String storageKey,
    String contentType,
    long sizeBytes
) {
}
