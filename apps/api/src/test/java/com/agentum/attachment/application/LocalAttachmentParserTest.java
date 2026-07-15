package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
}
