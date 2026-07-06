package com.agentum.delivery.application;

import java.util.UUID;

public interface DocumentDeliveryStorage {

    default DocumentDeliveryArtifact store(UUID tenantId, UUID recordId, String fileName, byte[] bytes) {
        return store(tenantId, recordId, fileName, MarkdownDocxRenderer.DOCX_CONTENT_TYPE, bytes);
    }

    DocumentDeliveryArtifact store(UUID tenantId, UUID recordId, String fileName, String contentType, byte[] bytes);

    default DocumentDeliveryFile read(String storageKey, String fileName) {
        return read(storageKey, fileName, MarkdownDocxRenderer.DOCX_CONTENT_TYPE);
    }

    DocumentDeliveryFile read(String storageKey, String fileName, String contentType);

    void delete(String storageKey);

    static String sanitizeFileName(String fileName) {
        return sanitizeFileName(fileName, "交付文档.docx", ".docx");
    }

    static String sanitizeFileName(String fileName, String fallbackFileName, String extension) {
        String safeExtension = extension == null || extension.isBlank() ? ".docx" : extension.trim();
        if (!safeExtension.startsWith(".")) {
            safeExtension = "." + safeExtension;
        }
        String fallback = fallbackFileName == null || fallbackFileName.isBlank() ? "交付文档" + safeExtension : fallbackFileName.trim();
        String value = fileName == null || fileName.isBlank() ? fallback : fileName.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-");
        value = value.replaceAll("\\.{2,}", "-");
        value = value.replaceAll("\\s+", " ").trim();
        value = value.replaceAll("^[.\\-\\s]+", "").replaceAll("[.\\-\\s]+$", "");
        if (value.isBlank()) {
            value = fallback;
        }
        if (!value.toLowerCase().endsWith(safeExtension.toLowerCase())) {
            value = value + safeExtension;
        }
        int maxBaseLength = Math.max(1, 160 - safeExtension.length());
        return value.length() > 160 ? value.substring(0, maxBaseLength) + safeExtension : value;
    }

    static String extensionForContentType(String contentType) {
        if (ExcelWorkbookRenderer.XLSX_CONTENT_TYPE.equals(contentType)) {
            return ".xlsx";
        }
        return ".docx";
    }

    static String fallbackFileNameForContentType(String contentType) {
        if (ExcelWorkbookRenderer.XLSX_CONTENT_TYPE.equals(contentType)) {
            return "交付表格.xlsx";
        }
        return "交付文档.docx";
    }
}
