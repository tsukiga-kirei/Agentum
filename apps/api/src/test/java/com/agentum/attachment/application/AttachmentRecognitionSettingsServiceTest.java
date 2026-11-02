package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.agentum.attachment.infrastructure.AttachmentRecognitionSettingRepository;
import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.attachment.interfaces.AttachmentRecognitionApi;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.security.FieldEncryptionService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AttachmentRecognitionSettingsServiceTest {

    @Test
    void hybridRequiresMineruEndpoint() {
        var service = new AttachmentRecognitionSettingsService(mock(AttachmentRecognitionSettingRepository.class),
            mock(FieldEncryptionService.class), Clock.systemUTC());
        assertThatThrownBy(() -> service.update(request("hybrid", null, List.of("pdf")), UUID.randomUUID()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode())
                .isEqualTo("ATTACHMENT_MINERU_ENDPOINT_REQUIRED"));
    }

    @Test
    void acceptsHybridSettingsAndRejectsDangerousWhitelist() {
        var repository = mock(AttachmentRecognitionSettingRepository.class);
        var entity = mock(AttachmentRecognitionSettingEntity.class);
        when(repository.findById((short) 1)).thenReturn(Optional.of(entity));
        var service = new AttachmentRecognitionSettingsService(repository, mock(FieldEncryptionService.class), Clock.systemUTC());
        service.update(request("hybrid", "http://mineru.internal", List.of("pdf")), UUID.randomUUID());
        verify(repository).save(entity);
        assertThatThrownBy(() -> service.update(request("hybrid", "http://mineru.internal", List.of("exe")), UUID.randomUUID()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode())
                .isEqualTo("ATTACHMENT_EXTENSION_BLOCKED"));
    }

    private static AttachmentRecognitionApi.UpdateSettingsRequest request(String engine, String endpoint, List<String> extensions) {
        return new AttachmentRecognitionApi.UpdateSettingsRequest(true, engine, 20, 5, 200_000, "permanent", 30,
            extensions, endpoint, null, false, "pipeline", "auto", "ch", true, true, 10, 300);
    }

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
