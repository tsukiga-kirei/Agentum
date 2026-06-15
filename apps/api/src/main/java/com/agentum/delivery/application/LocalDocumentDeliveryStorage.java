package com.agentum.delivery.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 初版文档交付的本地文件存储。
 *
 * <p>当前阶段先把 DOCX 写入本地运行目录，便于端到端闭环；后续接 MinIO/S3 时只替换本组件，
 * 交付记录中的 storageKey 与下载接口语义保持不变。</p>
 */
@Component
public class LocalDocumentDeliveryStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentDeliveryStorage.class);

    private final Path root;

    public LocalDocumentDeliveryStorage(@Value("${agentum.delivery.document.storage-root:.agentum/deliveries}") String storageRoot) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public DocumentDeliveryArtifact store(UUID tenantId, UUID recordId, String fileName, byte[] bytes) {
        String safeFileName = sanitizeFileName(fileName);
        String storageKey = tenantId + "/" + recordId + "/" + safeFileName;
        Path target = resolveStorageKey(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return new DocumentDeliveryArtifact(
                safeFileName,
                storageKey,
                MarkdownDocxRenderer.DOCX_CONTENT_TYPE,
                bytes == null ? 0 : bytes.length
            );
        } catch (IOException exception) {
            log.warn(
                "Word 文档交付文件写入失败 tenantId={} recordId={} storageKey={} requestId={}",
                tenantId,
                recordId,
                storageKey,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_DOCUMENT_STORE_FAILED", "Word 文档写入失败，请稍后重试");
        }
    }

    public DocumentDeliveryFile read(String storageKey, String fileName) {
        Path path = resolveStorageKey(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_DOCUMENT_FILE_NOT_FOUND", "交付文档文件不存在或已过期");
        }
        try {
            return new DocumentDeliveryFile(sanitizeFileName(fileName), MarkdownDocxRenderer.DOCX_CONTENT_TYPE, Files.readAllBytes(path));
        } catch (IOException exception) {
            log.warn("Word 文档交付文件读取失败 storageKey={} requestId={}", storageKey, RequestIds.current(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_DOCUMENT_READ_FAILED", "Word 文档读取失败，请稍后重试");
        }
    }

    public static String sanitizeFileName(String fileName) {
        String value = fileName == null || fileName.isBlank() ? "交付文档.docx" : fileName.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            value = "交付文档.docx";
        }
        if (!value.toLowerCase().endsWith(".docx")) {
            value = value + ".docx";
        }
        return value.length() > 160 ? value.substring(0, 155) + ".docx" : value;
    }

    private Path resolveStorageKey(String storageKey) {
        String safeKey = storageKey == null ? "" : storageKey.trim();
        Path path = root.resolve(safeKey).normalize();
        if (!path.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_STORAGE_KEY_INVALID", "交付文档路径不合法");
        }
        return path;
    }
}
