package com.agentum.delivery.application;

import java.util.UUID;

public interface DocumentDeliveryStorage {

    DocumentDeliveryArtifact store(UUID tenantId, UUID recordId, String fileName, byte[] bytes);

    DocumentDeliveryFile read(String storageKey, String fileName);

    static String sanitizeFileName(String fileName) {
        String value = fileName == null || fileName.isBlank() ? "交付文档.docx" : fileName.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-");
        value = value.replaceAll("\\.{2,}", "-");
        value = value.replaceAll("\\s+", " ").trim();
        value = value.replaceAll("^[.\\-\\s]+", "").replaceAll("[.\\-\\s]+$", "");
        if (value.isBlank()) {
            value = "交付文档.docx";
        }
        if (!value.toLowerCase().endsWith(".docx")) {
            value = value + ".docx";
        }
        return value.length() > 160 ? value.substring(0, 155) + ".docx" : value;
    }
}
