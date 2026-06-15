package com.agentum.delivery.infrastructure;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioDocumentDeliveryProperties.class)
public class DocumentDeliveryStorageConfiguration {

    @Bean
    public MinioClient minioClient(MinioDocumentDeliveryProperties properties) {
        return MinioClient.builder()
            .endpoint(properties.endpoint())
            .credentials(properties.accessKey(), properties.secretKey())
            .build();
    }
}
