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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class AttachmentRecognitionSettingsServiceTest {

    @Test
    void savesNewParserOptionsAndKeepsCredentialEncrypted() {
        var repository = mock(AttachmentRecognitionSettingRepository.class);
        var entity = mock(AttachmentRecognitionSettingEntity.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var encryption = mock(FieldEncryptionService.class);
        when(repository.findById((short) 1)).thenReturn(Optional.of(entity));
        when(encryption.encrypt("new-key")).thenReturn("encrypted-value");
        var service = new AttachmentRecognitionSettingsService(repository, encryption, Clock.systemUTC());
        var response = service.update(new AttachmentRecognitionApi.UpdateSettingsRequest(true, "hybrid", 20, 5,
            200_000, "permanent", 30, List.of("pdf", "docx"), "http://mineru.internal/proxy", "new-key", false,
            "mineru4", "advanced", "ocr", "pipeline", "auto", "ch", true, true, 10, 300), UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(response.mineruParser()).isEqualTo("mineru4");
        org.assertj.core.api.Assertions.assertThat(response.mineruTier()).isEqualTo("advanced");
        org.assertj.core.api.Assertions.assertThat(response.mineruOcrMode()).isEqualTo("ocr");
        org.assertj.core.api.Assertions.assertThat(response.mineruApiKeyConfigured()).isTrue();
        org.assertj.core.api.Assertions.assertThat(entity.getEncryptedMineruApiKey()).isEqualTo("encrypted-value");
        org.assertj.core.api.Assertions.assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(response).toString()).doesNotContain("new-key", "encrypted-value");
    }

    @ParameterizedTest
    @CsvSource({"unknown, standard, auto, ATTACHMENT_MINERU_PARSER_INVALID",
        "mineru4, invalid, auto, ATTACHMENT_MINERU_TIER_INVALID",
        "mineru4, standard, invalid, ATTACHMENT_MINERU_OCR_MODE_INVALID"})
    void rejectsInvalidNewParserOptions(String parser, String tier, String ocrMode, String code) {
        var service = new AttachmentRecognitionSettingsService(mock(AttachmentRecognitionSettingRepository.class),
            mock(FieldEncryptionService.class), Clock.systemUTC());
        var request = new AttachmentRecognitionApi.UpdateSettingsRequest(true, "hybrid", 20, 5, 200_000, "permanent", 30,
            List.of("pdf"), "http://mineru.internal", null, false, parser, tier, ocrMode, "pipeline", "auto", "ch", true, true, 10, 300);
        assertThatThrownBy(() -> service.update(request, UUID.randomUUID())).isInstanceOf(ApiException.class)
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode()).isEqualTo(code));
    }

    @ParameterizedTest
    @CsvSource({"mineru,/proxy/health", "mineru4,/proxy/v1/health"})
    void healthUsesUnsavedParserAndConnectionWithSavedEncryptedKey(String parser, String path) throws Exception {
        var requestedPath = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var repository = mock(AttachmentRecognitionSettingRepository.class);
            var entity = mock(AttachmentRecognitionSettingEntity.class);
            var encryption = mock(FieldEncryptionService.class);
            when(repository.findById((short) 1)).thenReturn(Optional.of(entity));
            when(entity.getEncryptedMineruApiKey()).thenReturn("encrypted");
            when(encryption.decrypt("encrypted")).thenReturn("saved-key");
            var service = new AttachmentRecognitionSettingsService(repository, encryption, Clock.systemUTC());
            var result = service.testConnection(new AttachmentRecognitionApi.TestConnectionRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy", null, parser, true, 2), UUID.randomUUID());
            org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("success");
            org.assertj.core.api.Assertions.assertThat(requestedPath.get()).isEqualTo(path);
            org.assertj.core.api.Assertions.assertThat(authorization.get()).isEqualTo("Bearer saved-key");
        } finally {
            server.stop(0);
        }
    }

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
            extensions, endpoint, null, false, "mineru", "standard", "auto", "pipeline", "auto", "ch", true, true, 10, 300);
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
            "http://mineru.internal", null, false, "mineru", "standard", "auto", "custom-backend", "auto", "ch",
            true, true, 10, 300
        );

        assertThatThrownBy(() -> service.update(request, UUID.randomUUID()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode())
                .isEqualTo("ATTACHMENT_MINERU_BACKEND_INVALID"));
    }
}
