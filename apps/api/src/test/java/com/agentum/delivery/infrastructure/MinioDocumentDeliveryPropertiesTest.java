package com.agentum.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MinioDocumentDeliveryPropertiesTest {

    @Test
    void shouldUseMinioDefaultsWhenPropertiesAreBlank() {
        MinioDocumentDeliveryProperties properties = new MinioDocumentDeliveryProperties("", "", "", "", "", true);

        assertThat(properties.endpoint()).isEqualTo("http://localhost:9000");
        assertThat(properties.accessKey()).isEqualTo("agentum");
        assertThat(properties.secretKey()).isEqualTo("agentum_dev_password");
        assertThat(properties.bucket()).isEqualTo("agentum");
        assertThat(properties.normalizedObjectPrefix()).isEqualTo("deliveries/documents");
        assertThat(properties.autoCreateBucket()).isTrue();
    }

    @Test
    void shouldNormalizeObjectPrefix() {
        MinioDocumentDeliveryProperties properties = new MinioDocumentDeliveryProperties(
            "http://minio:9000",
            "ak",
            "sk",
            "bucket",
            "/deliveries//word/",
            false
        );

        assertThat(properties.normalizedObjectPrefix()).isEqualTo("deliveries/word");
    }
}
