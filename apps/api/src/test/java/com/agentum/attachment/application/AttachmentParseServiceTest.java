package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentum.attachment.domain.AttachmentParseResultEntity;
import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.attachment.domain.InputAttachmentEntity;
import com.agentum.attachment.infrastructure.AttachmentParseResultRepository;
import com.agentum.attachment.infrastructure.InputAttachmentRepository;
import com.agentum.shared.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 用真实本地解析器验证路由、外发边界和状态持久化；外部 OCR 使用可控替身。 */
class AttachmentParseServiceTest {

    private final InputAttachmentRepository attachments = mock(InputAttachmentRepository.class);
    private final AttachmentParseResultRepository results = mock(AttachmentParseResultRepository.class);
    private final AttachmentRecognitionSettingsService settingsService = mock(AttachmentRecognitionSettingsService.class);
    private final AttachmentObjectStorage storage = mock(AttachmentObjectStorage.class);
    private final LocalAttachmentParser local = spy(new LocalAttachmentParser());
    private final MineruAttachmentParser mineru = mock(MineruAttachmentParser.class);
    private final AttachmentRecognitionSettingEntity settings = mock(AttachmentRecognitionSettingEntity.class);
    private final AttachmentParseService service = new AttachmentParseService(
        attachments, results, settingsService, storage, local, mineru, Clock.systemUTC());
    private InputAttachmentEntity attachment;
    private AttachmentParseResultEntity result;

    @Test
    void hybridKeepsLocalSuccessWithoutSendingDocument() {
        prepare("hybrid", "txt", "合同正文".getBytes(StandardCharsets.UTF_8), List.of("txt"));
        service.parse(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo("ready");
        assertThat(result.getParserType()).isEqualTo("local-txt");
        assertThat(result.getFallbackReason()).isNull();
        verifyNoInteractions(mineru);
        verify(storage).storeParsedContent(any(), any(), eq("合同正文"));
    }

    @Test
    void hybridUpgradesPdfWithoutTextToOcr() throws Exception {
        prepare("hybrid", "pdf", emptyPdf(), List.of("pdf"));
        when(mineru.parse(eq(settings), eq("附件.pdf"), any())).thenReturn("扫描件识别正文");
        service.parse(attachment.getId());
        assertThat(result.getParserType()).isEqualTo("mineru");
        assertThat(result.getFallbackReason()).isEqualTo("LOCAL_TEXT_EMPTY");
        assertThat(attachment.getStatus()).isEqualTo("ready");
        verify(storage).storeParsedContent(any(), any(), eq("扫描件识别正文"));
    }

    @Test
    void hybridUpgradesLocalParseFailure() {
        prepare("hybrid", "docx", new byte[]{0x50, 0x4b, 0}, List.of("docx"));
        when(mineru.parse(eq(settings), anyString(), any())).thenReturn("后备正文");
        service.parse(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo("ready");
        assertThat(result.getFallbackReason()).isEqualTo("LOCAL_PARSE_FAILED");
    }

    @Test
    void hybridSendsImagesDirectly() {
        prepare("hybrid", "png", new byte[]{1, 2, 3}, List.of("png"));
        when(mineru.parse(eq(settings), anyString(), any())).thenReturn("图片文字");
        service.parse(attachment.getId());
        assertThat(result.getFallbackReason()).isEqualTo("LOCAL_UNSUPPORTED");
        assertThat(attachment.getStatus()).isEqualTo("ready");
        verifyNoInteractions(local);
    }

    @Test
    void hybridNeverSendsTypeOutsideMineruWhitelist() throws Exception {
        prepare("hybrid", "pdf", emptyPdf(), List.of("png"));
        service.parse(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo("failed");
        assertThat(attachment.getErrorCode()).isEqualTo("ATTACHMENT_FALLBACK_NOT_ALLOWED");
        assertThat(result.getFallbackReason()).isEqualTo("LOCAL_TEXT_EMPTY");
        verifyNoInteractions(mineru);
        verify(storage, never()).storeParsedContent(any(), any(), any());
    }

    @Test
    void hybridRetainsFailedOcrAttemptWithoutSavingEmptyContent() throws Exception {
        prepare("hybrid", "pdf", emptyPdf(), List.of("pdf"));
        when(mineru.parse(eq(settings), anyString(), any())).thenThrow(
            new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_HTTP_FAILED", "MinerU 返回 HTTP 503"));
        service.parse(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo("failed");
        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getParserType()).isEqualTo("mineru");
        assertThat(result.getFallbackReason()).isEqualTo("LOCAL_TEXT_EMPTY");
        assertThat(attachment.getErrorCode()).isEqualTo("ATTACHMENT_MINERU_HTTP_FAILED");
        verify(storage, never()).storeParsedContent(any(), any(), any());
    }

    @Test
    void localModeNeverCallsOcrEvenForScannedPdf() throws Exception {
        prepare("local", "pdf", emptyPdf(), List.of("pdf"));
        service.parse(attachment.getId());
        assertThat(attachment.getErrorCode()).isEqualTo("ATTACHMENT_PARSE_EMPTY");
        verifyNoInteractions(mineru);
    }

    @Test
    void complexModeContinuesToCallMineruDirectly() {
        prepare("mineru", "txt", "合同正文".getBytes(StandardCharsets.UTF_8), List.of("txt"));
        when(mineru.parse(eq(settings), anyString(), any())).thenReturn("版面正文");
        service.parse(attachment.getId());
        assertThat(result.getParserType()).isEqualTo("mineru");
        assertThat(result.getFallbackReason()).isNull();
        verifyNoInteractions(local);
    }

    @Test
    void hybridDoesNotRetryBusinessRejectionThroughOcr() {
        prepare("hybrid", "txt", new byte[]{1}, List.of("txt"));
        doThrow(new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_PARSE_LIMIT", "超出解析限制"))
            .when(local).parseContent(eq("txt"), any());
        service.parse(attachment.getId());
        assertThat(attachment.getErrorCode()).isEqualTo("ATTACHMENT_PARSE_LIMIT");
        verifyNoInteractions(mineru);
    }

    @Test
    void localRetainsFailedCompatibilityAttempt() {
        prepare("local", "docx", new byte[]{1}, List.of());
        doThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_LOCAL_PARSE_FAILED", "兼容解析失败",
            java.util.Map.of("parserType", "local-doc", "fallbackReason", "DOCX_AS_DOC")))
            .when(local).parseContent(eq("docx"), any());
        service.parse(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo("failed");
        assertThat(result.getParserType()).isEqualTo("local-doc");
        assertThat(result.getFallbackReason()).isEqualTo("DOCX_AS_DOC");
        verifyNoInteractions(mineru);
    }

    @Test
    void successfulParsingIsTruncatedAndDuplicateMessageDoesNotParseAgain() {
        prepare("hybrid", "txt", "123456789".getBytes(StandardCharsets.UTF_8), List.of());
        when(settings.getMaxExtractedChars()).thenReturn(4);
        service.parse(attachment.getId());
        service.parse(attachment.getId());
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getCharacterCount()).isEqualTo(9);
        verify(storage).read("original");
        verify(storage).storeParsedContent(any(), any(), eq("1234\n\n> 内容已按系统上限截断。"));
        verifyNoInteractions(mineru);
    }

    @Test
    void emptyMineruResultRemainsFailed() {
        prepare("hybrid", "png", new byte[]{1}, List.of("png"));
        when(settings.getMaxExtractedChars()).thenReturn(4);
        when(mineru.parse(eq(settings), anyString(), any())).thenReturn(" ".repeat(20));
        service.parse(attachment.getId());
        assertThat(attachment.getErrorCode()).isEqualTo("ATTACHMENT_PARSE_EMPTY");
        verify(storage, never()).storeParsedContent(any(), any(), any());
    }

    private void prepare(String engine, String extension, byte[] bytes, List<String> whitelist) {
        attachment = InputAttachmentEntity.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "materials", "materials", UUID.randomUUID(), "附件." + extension, extension,
            "application/octet-stream", bytes.length, "sha256", "original", engine, "queued", null, Instant.now());
        result = AttachmentParseResultEntity.parsing(attachment.getId(), engine, "v2", "hash", Instant.now());
        when(attachments.findByIdForUpdate(attachment.getId())).thenReturn(Optional.of(attachment));
        when(results.findByAttachmentId(attachment.getId())).thenReturn(Optional.of(result));
        when(settingsService.requireEntity()).thenReturn(settings);
        when(settings.getMineruSupportedExtensions()).thenReturn(whitelist);
        when(settings.getMaxExtractedChars()).thenReturn(200_000);
        when(storage.read("original")).thenReturn(bytes);
        when(storage.storeParsedContent(any(), any(), anyString())).thenReturn("parsed");
    }

    private static byte[] emptyPdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
