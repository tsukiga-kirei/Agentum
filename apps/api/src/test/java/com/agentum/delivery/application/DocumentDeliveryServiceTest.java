package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.asset.application.AssetManagementService;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T02:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    private MarkdownDocxRenderer renderer;
    private DocumentDeliveryStorage storage;
    private DocumentDeliveryService service;

    @BeforeEach
    void setUp() {
        renderer = mock(MarkdownDocxRenderer.class);
        storage = mock(DocumentDeliveryStorage.class);
        service = new DocumentDeliveryService(
            renderer,
            storage,
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            mock(AssetManagementService.class),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRenderMarkdownTemplateAsFinalDocumentBody() {
        SystemCapabilityEntity capability = wordCapability(Map.of("retentionDays", 7));
        when(renderer.render(any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        when(storage.store(eq(TENANT_ID), eq(RECORD_ID), any(), any()))
            .thenReturn(new DocumentDeliveryArtifact(
                "交付文档-RUN-001-20260615.docx",
                "deliveries/documents/key.docx",
                MarkdownDocxRenderer.DOCX_CONTENT_TYPE,
                3
            ));

        Map<String, Object> result = service.generateRuntimeDocument(
            TENANT_ID,
            OPERATOR_ID,
            RECORD_ID,
            capability,
            Map.of(
                "markdownContent", "# 月报\n\n{{risk_summary}}",
                "fileNameTemplate", "交付文档-{{runNumber}}-{{dateCompact}}.docx"
            ),
            Map.of(
                "runNumber", "RUN-001",
                "risk_summary", Map.of("final_answer", "授信通过")
            )
        );

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(renderer).render(markdownCaptor.capture(), eq("交付文档-RUN-001-20260615"), any(DocumentDeliveryStyle.class));
        verify(storage).store(eq(TENANT_ID), eq(RECORD_ID), fileNameCaptor.capture(), any());
        assertThat(markdownCaptor.getValue()).isEqualTo("# 月报\n\n授信通过");
        assertThat(fileNameCaptor.getValue()).isEqualTo("交付文档-RUN-001-20260615.docx");
        assertThat(result)
            .containsEntry("retentionDays", 7)
            .containsEntry("expiresAt", "2026-06-22T02:00:00Z");
    }

    @Test
    void shouldRejectWordDeliveryWithoutMarkdownTemplate() {
        SystemCapabilityEntity capability = wordCapability(Map.of());

        assertThatThrownBy(() -> service.generateRuntimeDocument(
            TENANT_ID,
            OPERATOR_ID,
            RECORD_ID,
            capability,
            Map.of("fileNameTemplate", "交付文档.docx"),
            Map.of("risk_summary", "授信通过")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Word 文档交付必须配置交付正文模板");

        verify(renderer, never()).render(any(), any(), any());
        verify(storage, never()).store(any(), any(), any(), any());
    }

    private static SystemCapabilityEntity wordCapability(Map<String, Object> extraConfig) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("sourceType", "builtin");
        config.put("deliveryChannel", "document");
        config.put("documentKind", "word");
        config.putAll(extraConfig);
        return SystemCapabilityEntity.create(
            "delivery",
            "Word 文档交付",
            "word_document_delivery",
            "v1",
            "",
            "medium",
            "active",
            config,
            NOW
        );
    }
}
