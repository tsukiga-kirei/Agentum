package com.agentum.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.delivery.application.DocumentDeliveryArtifact;
import com.agentum.delivery.application.MarkdownDocxRenderer;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinioDocumentDeliveryStorageTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void shouldWriteDocumentToConfiguredBucketAndPrefix() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        MinioDocumentDeliveryStorage storage = new MinioDocumentDeliveryStorage(
            minioClient,
            new MinioDocumentDeliveryProperties("http://minio:9000", "ak", "sk", "agentum", "deliveries/documents", true)
        );

        DocumentDeliveryArtifact artifact = storage.store(TENANT_ID, RECORD_ID, "../授信:报告", new byte[] {1, 2, 3});

        assertThat(artifact.fileName()).isEqualTo("授信-报告.docx");
        assertThat(artifact.storageKey()).isEqualTo(
            "deliveries/documents/" + TENANT_ID + "/" + RECORD_ID + "/授信-报告.docx"
        );
        assertThat(artifact.contentType()).isEqualTo(MarkdownDocxRenderer.DOCX_CONTENT_TYPE);
        assertThat(artifact.sizeBytes()).isEqualTo(3);

        ArgumentCaptor<PutObjectArgs> putObjectArgs = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(putObjectArgs.capture());
        assertThat(putObjectArgs.getValue().bucket()).isEqualTo("agentum");
        assertThat(putObjectArgs.getValue().object()).isEqualTo(artifact.storageKey());
    }

    @Test
    void shouldCreateBucketWhenAutoCreateIsEnabled() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        MinioDocumentDeliveryStorage storage = new MinioDocumentDeliveryStorage(
            minioClient,
            new MinioDocumentDeliveryProperties("http://minio:9000", "ak", "sk", "agentum", "deliveries/documents", true)
        );

        storage.store(TENANT_ID, RECORD_ID, "交付文档.docx", new byte[] {1});

        ArgumentCaptor<MakeBucketArgs> makeBucketArgs = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(minioClient).makeBucket(makeBucketArgs.capture());
        assertThat(makeBucketArgs.getValue().bucket()).isEqualTo("agentum");
    }
}
