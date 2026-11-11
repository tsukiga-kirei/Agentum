package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentParseResultEntity;
import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.attachment.domain.InputAttachmentEntity;
import com.agentum.attachment.infrastructure.AttachmentParseResultRepository;
import com.agentum.attachment.infrastructure.InputAttachmentRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentParseService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentParseService.class);
    private final InputAttachmentRepository attachmentRepository;
    private final AttachmentParseResultRepository resultRepository;
    private final AttachmentRecognitionSettingsService settingsService;
    private final AttachmentObjectStorage storage;
    private final LocalAttachmentParser localParser;
    private final MineruAttachmentParser mineruParser;
    private final Clock clock;

    public AttachmentParseService(
        InputAttachmentRepository attachmentRepository, AttachmentParseResultRepository resultRepository,
        AttachmentRecognitionSettingsService settingsService, AttachmentObjectStorage storage,
        LocalAttachmentParser localParser, MineruAttachmentParser mineruParser, Clock clock
    ) {
        this.attachmentRepository = attachmentRepository;
        this.resultRepository = resultRepository;
        this.settingsService = settingsService;
        this.storage = storage;
        this.localParser = localParser;
        this.mineruParser = mineruParser;
        this.clock = clock;
    }

    /** 重复 MQ 消息通过附件终态和唯一解析结果共同保证幂等。 */
    @Transactional
    public void parse(java.util.UUID attachmentId) {
        // 悲观锁把同一附件的重复消息串行化；消费者崩溃时事务回滚为 queued，下次消息仍可安全重试。
        InputAttachmentEntity attachment = attachmentRepository.findByIdForUpdate(attachmentId).orElse(null);
        if (attachment == null || "ready".equals(attachment.getStatus()) || "failed".equals(attachment.getStatus())) return;
        AttachmentRecognitionSettingEntity settings = settingsService.requireEntity();
        Instant now = clock.instant();
        attachment.markParsing(now);
        attachmentRepository.save(attachment);
        String configHash = sha256(attachment.getRecognitionEngine() + "|mineru-adapter-v4|" + settings.getMineruEndpoint()
            + "|" + settings.getMineruSupportedExtensions() + "|" + settings.getMineruBackend()
            + "|" + settings.getMineruParser() + "|" + settings.getMineruTier() + "|" + settings.getMineruOcrMode()
            + "|" + settings.getMineruParseMethod() + "|" + settings.getMineruLanguage()
            + "|" + settings.isMineruEnableTable() + "|" + settings.isMineruEnableFormula()
            + "|" + settings.getMaxExtractedChars());
        AttachmentParseResultEntity result = resultRepository.findByAttachmentId(attachmentId)
            .orElseGet(() -> AttachmentParseResultEntity.parsing(attachmentId, attachment.getRecognitionEngine(), "v4", configHash, now));
        resultRepository.save(result);
        try {
            byte[] bytes = storage.read(attachment.getStorageKey());
            String parsed = parseContent(attachment, result, settings, bytes);
            // 必须在添加截断提示前判断正文，避免大量空白被提示文字伪装成识别成功。
            if (parsed == null || parsed.isBlank()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_PARSE_EMPTY", "附件未提取到可用文本");
            int originalLength = parsed.length();
            boolean truncated = originalLength > settings.getMaxExtractedChars();
            String finalContent = truncated ? parsed.substring(0, settings.getMaxExtractedChars()) + "\n\n> 内容已按系统上限截断。" : parsed;
            String key = storage.storeParsedContent(attachment.getTenantId(), attachment.getId(), finalContent);
            result.ready(key, originalLength, truncated, clock.instant());
            attachment.markReady(clock.instant());
            resultRepository.save(result);
            attachmentRepository.save(attachment);
            log.info("附件解析成功 tenantId={} runId={} nodeRunId={} attachmentId={} engine={} parserType={} fallbackReason={} characterCount={} truncated={} requestId={}", attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), attachment.getRecognitionEngine(), result.getParserType(), result.getFallbackReason(), originalLength, truncated, RequestIds.current());
        } catch (ApiException exception) {
            fail(attachment, result, exception.getCode(), exception.getMessage());
        } catch (Exception exception) {
            log.error("附件解析发生非预期异常 tenantId={} runId={} nodeRunId={} attachmentId={} requestId={}", attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), RequestIds.current(), exception);
            fail(attachment, result, "ATTACHMENT_PARSE_FAILED", "附件识别失败，请检查文件内容或识别服务");
        }
    }

    private String parseContent(InputAttachmentEntity attachment, AttachmentParseResultEntity result,
                                AttachmentRecognitionSettingEntity settings, byte[] bytes) {
        String extension = attachment.getExtension();
        if ("mineru".equals(attachment.getRecognitionEngine())) {
            result.recordParser("mineru4".equals(settings.getMineruParser()) ? "mineru4" : "mineru", null);
            return mineruParser.parse(settings, attachment.getOriginalFileName(), bytes);
        }
        boolean hybrid = "hybrid".equals(attachment.getRecognitionEngine());
        String fallbackReason = "LOCAL_UNSUPPORTED";
        if (AttachmentRecognitionPolicy.localSupportedExtensions().contains(extension)) {
            result.recordParser("local-" + extension, null);
            try {
                LocalAttachmentParser.ParsedContent parsed = localParser.parseContent(extension, bytes);
                result.recordParser(parsed.parserType(), parsed.fallbackReason());
                if (parsed.content().isBlank()) {
                    throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "ATTACHMENT_PARSE_EMPTY", "附件未提取到可用文本");
                }
                return parsed.content();
            } catch (ApiException exception) {
                if (exception.getDetails().get("parserType") instanceof String parserType
                    && exception.getDetails().get("fallbackReason") instanceof String reason) {
                    result.recordParser(parserType, reason);
                }
                // 只有明确的提取失败或空正文可以升级 OCR，权限、配额等业务异常不能通过后备服务绕过。
                if (!hybrid || !Set.of("ATTACHMENT_LOCAL_PARSE_FAILED", "ATTACHMENT_PARSE_EMPTY").contains(exception.getCode())) throw exception;
                fallbackReason = "ATTACHMENT_PARSE_EMPTY".equals(exception.getCode()) ? "LOCAL_TEXT_EMPTY" : "LOCAL_PARSE_FAILED";
            }
        } else if (!hybrid) {
            return localParser.parse(extension, bytes);
        }
        // 管理员白名单仍是外发边界；混合模式支持本地类型，不代表允许将这些文档全部外发。
        if (!settings.getMineruSupportedExtensions().contains(extension)) {
            result.recordParser(result.getParserType(), fallbackReason);
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "ATTACHMENT_FALLBACK_NOT_ALLOWED", "本地识别未获得可用正文，且该类型未获准发送至 MinerU");
        }
        result.recordParser("mineru4".equals(settings.getMineruParser()) ? "mineru4" : "mineru", fallbackReason);
        log.info("附件切换至复杂识别 tenantId={} runId={} nodeRunId={} attachmentId={} fallbackReason={} requestId={}",
            attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), fallbackReason, RequestIds.current());
        return mineruParser.parse(settings, attachment.getOriginalFileName(), bytes);
    }

    private void fail(InputAttachmentEntity attachment, AttachmentParseResultEntity result, String code, String message) {
        Instant now = clock.instant();
        result.failed(code, message, now);
        attachment.markFailed(code, message, now);
        resultRepository.save(result);
        attachmentRepository.save(attachment);
        log.warn("附件解析失败 tenantId={} runId={} nodeRunId={} attachmentId={} engine={} parserType={} fallbackReason={} errorCode={} requestId={}", attachment.getTenantId(), attachment.getRunId(), attachment.getNodeRunId(), attachment.getId(), attachment.getRecognitionEngine(), result.getParserType(), result.getFallbackReason(), code, RequestIds.current());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
