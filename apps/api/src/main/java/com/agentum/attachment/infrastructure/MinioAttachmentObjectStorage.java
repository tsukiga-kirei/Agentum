package com.agentum.attachment.infrastructure;

import com.agentum.attachment.application.AttachmentObjectStorage;
import com.agentum.delivery.infrastructure.MinioDocumentDeliveryProperties;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import io.minio.GetObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MinioAttachmentObjectStorage implements AttachmentObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioAttachmentObjectStorage.class);
    private final MinioClient client;
    private final MinioDocumentDeliveryProperties properties;
    private volatile boolean bucketReady;

    public MinioAttachmentObjectStorage(MinioClient client, MinioDocumentDeliveryProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String storeOriginal(UUID tenantId, UUID attachmentId, String fileName, String contentType, byte[] bytes) {
        String safeName = fileName == null ? "attachment.bin" : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-");
        return store("attachments/input/" + tenantId + "/" + attachmentId + "/original/" + safeName, contentType, bytes);
    }

    @Override
    public String storeParsedContent(UUID tenantId, UUID attachmentId, String markdown) {
        return store("attachments/input/" + tenantId + "/" + attachmentId + "/parsed/content.md", "text/markdown; charset=UTF-8", markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] read(String storageKey) {
        ensureBucketReady();
        try (var input = client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(sanitize(storageKey)).build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            log.warn("附件对象读取失败 requestId={}", RequestIds.current(), exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_STORAGE_READ_FAILED", "附件对象读取失败，请稍后重试");
        }
    }

    @Override
    public void delete(String storageKey) {
        ensureBucketReady();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(sanitize(storageKey)).build());
        } catch (Exception exception) {
            log.warn("附件对象删除失败 requestId={}", RequestIds.current(), exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_STORAGE_DELETE_FAILED", "附件删除失败，请稍后重试");
        }
    }

    private String store(String key, String contentType, byte[] bytes) {
        ensureBucketReady();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(key).contentType(contentType).stream(input, bytes.length, -1).build());
            return key;
        } catch (Exception exception) {
            log.warn("附件对象写入失败 sizeBytes={} requestId={}", bytes.length, RequestIds.current(), exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_STORAGE_WRITE_FAILED", "附件写入对象存储失败，请稍后重试");
        }
    }

    /** 附件可能早于产品交付首次写入对象存储，因此不能依赖其他模块预先创建 Bucket。 */
    private void ensureBucketReady() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
                if (!exists) {
                    if (!properties.autoCreateBucket()) {
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_STORAGE_BUCKET_MISSING", "附件对象存储 Bucket 不存在");
                    }
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                    log.info("附件对象存储 Bucket 已创建 requestId={}", RequestIds.current());
                }
                bucketReady = true;
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("附件对象存储 Bucket 初始化失败 requestId={}", RequestIds.current(), exception);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_STORAGE_UNAVAILABLE", "附件对象存储暂不可用，请稍后重试");
            }
        }
    }

    private static String sanitize(String value) {
        String key = value == null ? "" : value.trim().replace("\\", "/");
        if (key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_STORAGE_KEY_INVALID", "附件对象路径不合法");
        }
        return key;
    }
}
