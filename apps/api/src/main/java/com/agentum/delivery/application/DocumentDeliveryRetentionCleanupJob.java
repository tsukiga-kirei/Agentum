package com.agentum.delivery.application;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.logging.LogContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentDeliveryRetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeliveryRetentionCleanupJob.class);

    private final DeliveryRecordRepository deliveryRecordRepository;
    private final DocumentDeliveryStorage storage;
    private final Clock clock;
    private final int batchSize;

    public DocumentDeliveryRetentionCleanupJob(
        DeliveryRecordRepository deliveryRecordRepository,
        DocumentDeliveryStorage storage,
        Clock clock,
        @Value("${agentum.delivery.document.cleanup-batch-size:100}") int batchSize
    ) {
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.storage = storage;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(500, batchSize));
    }

    @Scheduled(fixedDelayString = "${agentum.delivery.document.cleanup-fixed-delay-ms:3600000}")
    @Transactional
    public void cleanupExpiredDocuments() {
        String nowIso = clock.instant().toString();
        List<DeliveryRecordEntity> records = deliveryRecordRepository.findExpiredDocumentRecords(nowIso, batchSize);
        if (records.isEmpty()) {
            return;
        }
        for (DeliveryRecordEntity record : records) {
            // 清理线程没有登录身份，按记录自身的租户恢复日志范围。
            try (LogContext.Scope ignored = LogContext.openTenantOperation(
                record.getTenantId(), null, record.getRunId(), null, null, RequestIds.current()
            )) {
                cleanupRecord(record);
            }
        }
    }

    private void cleanupRecord(DeliveryRecordEntity record) {
        Map<String, Object> result = record.getResultSnapshot() == null ? Map.of() : record.getResultSnapshot();
        String storageKey = stringValue(result.get("storageKey"));
        if (storageKey.isBlank()) {
            return;
        }
        try {
            storage.delete(storageKey);
            record.expire(clock.instant());
            deliveryRecordRepository.save(record);
            log.info(
                "过期交付文件已清理 tenantId={} recordId={} storageKey={} requestId={}",
                record.getTenantId(),
                record.getId(),
                storageKey,
                RequestIds.current()
            );
        } catch (RuntimeException exception) {
            log.warn(
                "过期交付文件清理失败 tenantId={} recordId={} storageKey={} requestId={}",
                record.getTenantId(),
                record.getId(),
                storageKey,
                RequestIds.current(),
                exception
            );
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
