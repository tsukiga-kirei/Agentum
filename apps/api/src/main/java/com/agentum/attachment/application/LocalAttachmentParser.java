package com.agentum.attachment.application;

import com.agentum.shared.api.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 简单识别只做确定性的本地文本提取，不执行 Office 公式、宏和外部链接。
 * 解析结果统一为 Markdown，便于下游智能体按同一数据边界读取。
 */
@Component
public class LocalAttachmentParser {

    public String parse(String extension, byte[] bytes) {
        String type = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        try {
            return switch (type) {
                case "txt", "md", "csv" -> decodeText(bytes);
                case "docx" -> parseDocx(bytes);
                case "doc" -> parseDoc(bytes);
                case "xlsx", "xls" -> parseWorkbook(bytes);
                case "pdf" -> parsePdf(bytes);
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_LOCAL_TYPE_UNSUPPORTED", "简单识别暂不支持该文件类型");
            };
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_LOCAL_PARSE_FAILED", "文件内容无法使用简单识别解析");
        }
    }

    private static String decodeText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String parseDocx(byte[] bytes) throws Exception {
        StringBuilder content = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            document.getParagraphs().forEach(paragraph -> appendLine(content, paragraph.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                String line = row.getTableCells().stream().map(cell -> cell.getText().replace("\n", " ").trim()).reduce((a, b) -> a + " | " + b).orElse("");
                appendLine(content, line.isBlank() ? "" : "| " + line + " |");
            }));
        }
        return content.toString().trim();
    }

    private static String parseDoc(byte[] bytes) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes)); WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText().trim();
        }
    }

    private static String parseWorkbook(byte[] bytes) throws Exception {
        StringBuilder content = new StringBuilder();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                appendLine(content, "## " + sheet.getSheetName());
                int rowLimit = Math.min(sheet.getLastRowNum(), 5000);
                for (int rowIndex = 0; rowIndex <= rowLimit; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    StringBuilder line = new StringBuilder("|");
                    int cellLimit = Math.min(row.getLastCellNum(), 200);
                    for (int cellIndex = 0; cellIndex < cellLimit; cellIndex++) {
                        Cell cell = row.getCell(cellIndex);
                        line.append(' ').append(cell == null ? "" : formatter.formatCellValue(cell).replace("|", "\\|")).append(" |");
                    }
                    appendLine(content, line.toString());
                }
            }
        }
        return content.toString().trim();
    }

    private static String parsePdf(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document).trim();
        }
    }

    private static void appendLine(StringBuilder builder, String text) {
        if (text != null && !text.isBlank()) {
            builder.append(text.trim()).append('\n');
        }
    }
}
