package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agentum.attachment.infrastructure.AttachmentRecognitionSettingRepository;
import com.agentum.attachment.interfaces.AttachmentRecognitionApi;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttachmentRecognitionSettingsServiceTest {

    @Test
    void shouldRejectBackendOutsideSelectOptions() {
        AttachmentRecognitionSettingsService service = new AttachmentRecognitionSettingsService(
            mock(AttachmentRecognitionSettingRepository.class),
            mock(FieldEncryptionService.class),
            Clock.systemUTC()
        );
        AttachmentRecognitionApi.UpdateSettingsRequest request = new AttachmentRecognitionApi.UpdateSettingsRequest(
            true, "mineru", 20, 5, 200_000, "permanent", 30, List.of("pdf"),
            "http://mineru.internal", null, false, "custom-backend", "auto", "ch",
            true, true, 10, 300
        );

        assertThatThrownBy(() -> service.update(request, UUID.randomUUID()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode())
                .isEqualTo("ATTACHMENT_MINERU_BACKEND_INVALID"));
    }
}
