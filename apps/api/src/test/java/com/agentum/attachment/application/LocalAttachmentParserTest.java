package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import com.agentum.shared.api.ApiException;
import org.junit.jupiter.api.Test;

class LocalAttachmentParserTest {

    private final LocalAttachmentParser parser = new LocalAttachmentParser();

    @Test
    void shouldDecodeUtf8TextAndRemoveBom() {
        byte[] bytes = "\uFEFF授信材料正文".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse("txt", bytes)).isEqualTo("授信材料正文");
    }

    @Test
    void shouldExtractDocxParagraphsAndTables() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("合同摘要");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("甲方");
            table.getRow(0).getCell(1).setText("云程科技");
            document.write(output);
            bytes = output.toByteArray();
        }

        assertThat(parser.parse("docx", bytes)).contains("合同摘要", "| 甲方 | 云程科技 |");
    }

    @Test
    void shouldExtractWorkbookSheetsAndCells() throws Exception {
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("明细");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("企业名称");
            row.createCell(1).setCellValue("云程科技");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(parser.parse("xlsx", bytes)).contains("## 明细", "| 企业名称 | 云程科技 |");
    }

    @Test
    void shouldUseLegacyWordParserForMisnamedDocxWithoutChangingOriginalBytes() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/attachments/legacy-word.doc")) {
            bytes = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        byte[] original = bytes.clone();
        InputAttachmentService.validateFileSignature("docx", bytes);
        var parsed = parser.parseContent("docx", bytes);
        assertThat(parsed.content()).isNotBlank().isEqualTo(parser.parse("doc", bytes));
        assertThat(parsed.parserType()).isEqualTo("local-doc");
        assertThat(parsed.fallbackReason()).isEqualTo("DOCX_AS_DOC");
        assertThat(bytes).containsExactly(original);
    }

    @Test
    void shouldRejectExcelOleMasqueradingAsWord() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("表格");
            workbook.write(output);
            byte[] bytes = output.toByteArray();
            assertThat(AttachmentFileFormat.isLegacyWord(bytes)).isFalse();
            assertThatThrownBy(() -> InputAttachmentService.validateFileSignature("docx", bytes))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("ATTACHMENT_SIGNATURE_INVALID"));
        }
    }

    @Test
    void shouldRejectEncryptedPackageAndDamagedOleInsteadOfTreatingThemAsDoc() throws Exception {
        try (POIFSFileSystem filesystem = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1}), "WordDocument");
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1}), "0Table");
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1}), "EncryptedPackage");
            filesystem.writeFilesystem(output);
            assertThat(AttachmentFileFormat.isLegacyWord(output.toByteArray())).isFalse();
        }
        byte[] damaged = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, 0, 0, 0, 0};
        assertThatThrownBy(() -> InputAttachmentService.validateFileSignature("docx", damaged)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse("docx", damaged))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("ATTACHMENT_LOCAL_PARSE_FAILED"));
    }

    @Test
    void shouldRetainLegacyAttemptWhenBothWordParsersFail() throws Exception {
        // 合法 OLE 目录但损坏的 Word 数据，用于验证“识别出格式”不等于“正文解析成功”。
        try (POIFSFileSystem filesystem = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1}), "WordDocument");
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1}), "0Table");
            filesystem.writeFilesystem(output);
            byte[] bytes = output.toByteArray();
            assertThatThrownBy(() -> parser.parseContent("docx", bytes))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getDetails())
                    .containsEntry("parserType", "local-doc").containsEntry("fallbackReason", "DOCX_AS_DOC"));
        }
    }
}
