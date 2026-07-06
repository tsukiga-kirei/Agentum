package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.system.domain.SystemCapabilityEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExcelDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-06T02:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    private ExcelWorkbookRenderer renderer;
    private DocumentDeliveryStorage storage;
    private ExcelDeliveryService service;

    @BeforeEach
    void setUp() {
        renderer = mock(ExcelWorkbookRenderer.class);
        storage = mock(DocumentDeliveryStorage.class);
        service = new ExcelDeliveryService(renderer, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRenderSheetsWithRuntimeVariablesAndStoreXlsx() {
        SystemCapabilityEntity capability = excelCapability(Map.of("retentionDays", 30));
        when(renderer.render(any())).thenReturn(new ExcelWorkbookRenderer.ExcelWorkbookRenderResult(new byte[] {1, 2, 3}, List.of(), 1));
        when(storage.store(eq(TENANT_ID), eq(RECORD_ID), any(), eq(ExcelWorkbookRenderer.XLSX_CONTENT_TYPE), any()))
            .thenReturn(new DocumentDeliveryArtifact(
                "风险明细-RUN-001.xlsx",
                "deliveries/documents/key.xlsx",
                ExcelWorkbookRenderer.XLSX_CONTENT_TYPE,
                3
            ));

        Map<String, Object> result = service.generateRuntimeWorkbook(
            TENANT_ID,
            OPERATOR_ID,
            RECORD_ID,
            capability,
            Map.of(
                "fileNameTemplate", "风险明细-{{runNumber}}.xlsx",
                "excelSheets", List.of(Map.of(
                    "name", "风险明细",
                    "bodyTemplate", "{{ risk_table }}",
                    "columnRules", List.of(Map.of("match", "金额", "type", "number"))
                ))
            ),
            Map.of("runNumber", "RUN-001", "risk_table", "|金额|\n|---|\n|1200|")
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExcelWorkbookRenderer.ExcelSheetRenderSpec>> sheetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(renderer).render(sheetsCaptor.capture());
        verify(storage).store(eq(TENANT_ID), eq(RECORD_ID), eq("风险明细-RUN-001.xlsx"), eq(ExcelWorkbookRenderer.XLSX_CONTENT_TYPE), any());
        assertThat(sheetsCaptor.getValue().getFirst().body()).contains("|金额|");
        assertThat(result)
            .containsEntry("adapter", "excel_workbook")
            .containsEntry("documentKind", "excel")
            .containsEntry("retentionDays", 30)
            .containsEntry("expiresAt", "2026-08-05T02:00:00Z");
    }

    private static SystemCapabilityEntity excelCapability(Map<String, Object> extraConfig) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("sourceType", "builtin");
        config.put("deliveryChannel", "document");
        config.put("documentKind", "excel");
        config.putAll(extraConfig);
        return SystemCapabilityEntity.create(
            "delivery",
            "Excel 工作簿交付",
            "excel_workbook_delivery",
            "v1",
            "",
            "medium",
            "active",
            config,
            NOW
        );
    }
}
