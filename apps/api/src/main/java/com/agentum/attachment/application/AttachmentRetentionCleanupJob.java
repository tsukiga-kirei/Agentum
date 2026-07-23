package com.agentum.attachment.application;

import com.agentum.attachment.domain.InputAttachmentEntity;
import com.agentum.attachment.infrastructure.AttachmentParseResultRepository;
import com.agentum.attachment.infrastructure.InputAttachmentRepository;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.logging.LogContext;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AttachmentRetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRetentionCleanupJob.class);
    private final InputAttachmentRepository attachmentRepository;
    private final AttachmentParseResultRepository parseResultRepository;
    private final AttachmentObjectStorage storage;
    private final Clock clock;
    private final int batchSize;

    public AttachmentRetentionCleanupJob(
        InputAttachmentRepository attachmentRepository,
        AttachmentParseResultRepository parseResultRepository,
        AttachmentObjectStorage storage,
        Clock clock,
        @Value("${agentum.attachment.cleanup-batch-size:100}") int batchSize
    ) {
        this.attachmentRepository = attachmentRepository;
        this.parseResultRepository = parseResultRepository;
        this.storage = storage;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(500, batchSize));
    }

    @Scheduled(fixedDelayString = "${agentum.attachment.cleanup-fixed-delay-ms:3600000}")
    @Transactional
    public void cleanupExpiredAttachments() {
        List<InputAttachmentEntity> attachments = attachmentRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(
            clock.instant(),
            PageRequest.of(0, batchSize)
        );
        for (InputAttachmentEntity attachment : attachments) {
            // 清理任务按附件落库的可信租户与运行字段恢复上下文，避免全部归入系统日志。
            try (LogContext.Scope ignored = LogContext.openTenantOperation(
                attachment.getTenantId(), null, attachment.getRunId(), null, attachment.getNodeRunId(), RequestIds.current()
            )) {
                cleanup(attachment);
            }
        }
    }

    private void cleanup(InputAttachmentEntity attachment) {
        try {
            parseResultRepository.findByAttachmentId(attachment.getId()).ifPresent(result -> {
                if (result.getContentStorageKey() != null) {
                    storage.delete(result.getContentStorageKey());
                }
            });
            storage.delete(attachment.getStorageKey());
            attachmentRepository.delete(attachment);
            log.info(
                "过期输入附件已清理 tenantId={} runId={} nodeRunId={} attachmentId={} requestId={}",
                attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), RequestIds.current()
            );
        } catch (RuntimeException exception) {
            // 清理失败保留数据库记录，下个调度周期继续重试，避免先删元数据后丢失对象定位信息。
            log.warn(
                "过期输入附件清理失败 tenantId={} runId={} nodeRunId={} attachmentId={} requestId={}",
                attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), RequestIds.current(), exception
            );
        }
    }
}
