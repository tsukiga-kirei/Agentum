package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentDeliveryStorageTest {

    @Test
    void shouldSanitizeDocxFileNameForObjectStorage() {
        String fileName = DocumentDeliveryStorage.sanitizeFileName(" ../授信:报告*终版 ");

        assertThat(fileName).isEqualTo("授信-报告-终版.docx");
    }

    @Test
    void shouldKeepExistingDocxSuffix() {
        String fileName = DocumentDeliveryStorage.sanitizeFileName("交付文档.docx");

        assertThat(fileName).isEqualTo("交付文档.docx");
    }
}
