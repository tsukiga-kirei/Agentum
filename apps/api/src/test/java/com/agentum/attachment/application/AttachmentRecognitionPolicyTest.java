package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentRecognitionPolicyTest {

    @Test
    void shouldExposeLocalParserExtensionsInStableOrder() {
        assertThat(AttachmentRecognitionPolicy.localSupportedExtensions())
            .containsExactly("txt", "md", "csv", "pdf", "doc", "docx", "xls", "xlsx");
    }

    @Test
    void shouldUseConfiguredMineruExtensionsForComplexRecognition() {
        AttachmentRecognitionSettingEntity settings = mock(AttachmentRecognitionSettingEntity.class);
        when(settings.getRecognitionEngine()).thenReturn("mineru");
        when(settings.getMineruSupportedExtensions()).thenReturn(List.of("pdf", "png"));

        assertThat(AttachmentRecognitionPolicy.supportedExtensions(settings)).containsExactly("pdf", "png");
    }
}
