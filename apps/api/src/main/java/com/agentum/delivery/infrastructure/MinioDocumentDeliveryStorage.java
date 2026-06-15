package com.agentum.delivery.infrastructure;

import com.agentum.delivery.application.DocumentDeliveryArtifact;
import com.agentum.delivery.application.DocumentDeliveryFile;
import com.agentum.delivery.application.DocumentDeliveryStorage;
import com.agentum.delivery.application.MarkdownDocxRenderer;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MinioDocumentDeliveryStorage implements DocumentDeliveryStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioDocumentDeliveryStorage.class);

    private final MinioClient minioClient;
    private final MinioDocumentDeliveryProperties properties;
    private volatile boolean bucketReady;

    public MinioDocumentDeliveryStorage(MinioClient minioClient, MinioDocumentDeliveryProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public DocumentDeliveryArtifact store(UUID tenantId, UUID recordId, String fileName, byte[] bytes) {
        String safeFileName = DocumentDeliveryStorage.sanitizeFileName(fileName);
        byte[] payload = bytes == null ? new byte[0] : bytes;
        String objectKey = objectKey(tenantId, recordId, safeFileName);
        ensureBucketReady();
        try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .contentType(MarkdownDocxRenderer.DOCX_CONTENT_TYPE)
                .stream(input, payload.length, -1)
                .build());
            log.info(
                "Word 文档交付文件已写入 MinIO tenantId={} recordId={} bucket={} objectKey={} sizeBytes={} requestId={}",
                tenantId,
                recordId,
                properties.bucket(),
                objectKey,
                payload.length,
                RequestIds.current()
            );
            return new DocumentDeliveryArtifact(safeFileName, objectKey, MarkdownDocxRenderer.DOCX_CONTENT_TYPE, payload.length);
        } catch (Exception exception) {
            log.warn(
                "Word 文档交付文件写入 MinIO 失败 tenantId={} recordId={} bucket={} objectKey={} requestId={}",
                tenantId,
                recordId,
                properties.bucket(),
                objectKey,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_STORE_FAILED", "Word 文档写入对象存储失败，请稍后重试");
        }
    }

    @Override
    public DocumentDeliveryFile read(String storageKey, String fileName) {
        String objectKey = sanitizeObjectKey(storageKey);
        ensureObjectExists(objectKey);
        try (var input = minioClient.getObject(GetObjectArgs.builder()
            .bucket(properties.bucket())
            .object(objectKey)
            .build())) {
            return new DocumentDeliveryFile(
                DocumentDeliveryStorage.sanitizeFileName(fileName),
                MarkdownDocxRenderer.DOCX_CONTENT_TYPE,
                input.readAllBytes()
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                "Word 文档交付文件读取 MinIO 失败 bucket={} objectKey={} requestId={}",
                properties.bucket(),
                objectKey,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_DOCUMENT_READ_FAILED", "Word 文档读取失败，请稍后重试");
        }
    }

    @Override
    public void delete(String storageKey) {
        String objectKey = sanitizeObjectKey(storageKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
            log.info("Word 文档交付文件已从 MinIO 清理 bucket={} objectKey={} requestId={}", properties.bucket(), objectKey, RequestIds.current());
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code()) || "NoSuchObject".equals(exception.errorResponse().code())) {
                log.info("Word 文档交付文件清理时对象已不存在 bucket={} objectKey={} requestId={}", properties.bucket(), objectKey, RequestIds.current());
                return;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_OBJECT_DELETE_FAILED", "交付文档对象删除失败，请稍后重试");
        } catch (Exception exception) {
            log.warn(
                "Word 文档交付文件删除 MinIO 失败 bucket={} objectKey={} requestId={}",
                properties.bucket(),
                objectKey,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_OBJECT_DELETE_FAILED", "交付文档对象删除失败，请稍后重试");
        }
    }

    private void ensureObjectExists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build());
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code()) || "NoSuchObject".equals(exception.errorResponse().code())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_DOCUMENT_FILE_NOT_FOUND", "交付文档文件不存在或已过期");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_OBJECT_STORAGE_FAILED", "对象存储访问失败，请稍后重试");
        } catch (Exception exception) {
            log.warn(
                "Word 文档交付文件状态检查失败 bucket={} objectKey={} requestId={}",
                properties.bucket(),
                objectKey,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_OBJECT_STORAGE_FAILED", "对象存储访问失败，请稍后重试");
        }
    }

    private void ensureBucketReady() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
                if (!exists) {
                    if (!properties.autoCreateBucket()) {
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_BUCKET_NOT_FOUND", "交付文档对象存储桶不存在");
                    }
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                    log.info("Word 文档交付 MinIO bucket 已自动创建 bucket={} requestId={}", properties.bucket(), RequestIds.current());
                }
                bucketReady = true;
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("Word 文档交付 MinIO bucket 检查失败 bucket={} requestId={}", properties.bucket(), RequestIds.current(), exception);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_DOCUMENT_BUCKET_CHECK_FAILED", "交付文档对象存储桶检查失败");
            }
        }
    }

    private String objectKey(UUID tenantId, UUID recordId, String safeFileName) {
        return properties.normalizedObjectPrefix() + "/" + tenantId + "/" + recordId + "/" + safeFileName;
    }

    private String sanitizeObjectKey(String storageKey) {
        String key = storageKey == null ? "" : storageKey.trim().replace("\\", "/");
        if (key.isBlank() || key.startsWith("/") || key.contains("..") || key.chars().anyMatch(ch -> ch < 32)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_STORAGE_KEY_INVALID", "交付文档对象路径不合法");
        }
        return key;
    }
}
