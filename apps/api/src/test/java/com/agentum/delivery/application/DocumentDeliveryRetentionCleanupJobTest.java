package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDeliveryRetentionCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-06-15T02:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void shouldDeleteExpiredWordDocumentAndMarkRecordExpired() {
        DeliveryRecordRepository repository = mock(DeliveryRecordRepository.class);
        DocumentDeliveryStorage storage = mock(DocumentDeliveryStorage.class);
        DocumentDeliveryRetentionCleanupJob job = new DocumentDeliveryRetentionCleanupJob(
            repository,
            storage,
            Clock.fixed(NOW, ZoneOffset.UTC),
            100
        );
        DeliveryRecordEntity record = expiredRecord();
        when(repository.findExpiredDocumentRecords(NOW.toString(), 100)).thenReturn(List.of(record));

        job.cleanupExpiredDocuments();

        verify(storage).delete("deliveries/documents/key.docx");
        verify(repository).save(eq(record));
        assertThat(record.getStatus()).isEqualTo("expired");
    }

    private static DeliveryRecordEntity expiredRecord() {
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "演示任务",
            "演示流程",
            OPERATOR_ID,
            5,
            "RUN-001",
            NOW
        );
        WorkflowNodeRunEntity nodeRun = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "delivery_report",
            "delivery",
            "交付结果",
            Map.of(),
            Map.of(),
            Map.of(),
            4,
            NOW
        );
        DeliveryRecordEntity record = DeliveryRecordEntity.started(
            run,
            nodeRun,
            null,
            "word_document",
            "Word 文档",
            "演示任务",
            Map.of(),
            OPERATOR_ID,
            NOW
        );
        record.succeed(Map.of(
            "adapter", "word_document",
            "storageKey", "deliveries/documents/key.docx",
            "expiresAt", "2026-06-14T02:00:00Z"
        ), NOW);
        return record;
    }
}
