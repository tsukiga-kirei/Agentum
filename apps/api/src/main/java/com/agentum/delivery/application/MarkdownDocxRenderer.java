package com.agentum.delivery.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * 轻量 DOCX 渲染器。
 *
 * <p>初版只覆盖 AI Markdown 交付最常见的受控子集，避免把运行态文档生成绑定到外部命令。
 * 后续如果接入 reference.docx 或复杂目录/图片，可在本类后面替换为 POI/docx4j/Worker 实现。</p>
 */
@Component
public class MarkdownDocxRenderer {

    public static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");

    public byte[] render(String markdown, DocumentDeliveryStyle style) {
        String normalizedMarkdown = markdown == null || markdown.isBlank() ? "暂无文档内容。" : markdown;
        DocumentDeliveryStyle effectiveStyle = style == null ? DocumentDeliveryStyle.defaults() : style;
        String documentXml = buildDocumentXml(normalizedMarkdown, effectiveStyle);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", contentTypesXml());
            writeEntry(zip, "_rels/.rels", rootRelationshipsXml());
            writeEntry(zip, "word/_rels/document.xml.rels", documentRelationshipsXml());
            writeEntry(zip, "word/styles.xml", stylesXml(effectiveStyle));
            writeEntry(zip, "word/settings.xml", settingsXml());
            writeEntry(zip, "word/document.xml", documentXml);
            zip.finish();
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("DOCX 文件生成失败", exception);
        }
    }

    private String buildDocumentXml(String markdown, DocumentDeliveryStyle style) {
        StringBuilder body = new StringBuilder();
        List<String> lines = List.of(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        boolean firstContentBlock = true;
        int consecutiveBlankLines = 0;
        for (int index = 0; index < lines.size();) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                consecutiveBlankLines++;
                if (consecutiveBlankLines > 1) {
                    body.append(emptyParagraph(style));
                }
                index++;
                continue;
            }
            consecutiveBlankLines = 0;
            if (trimmed.startsWith("```")) {
                List<String> codeLines = new ArrayList<>();
                index++;
                while (index < lines.size() && !lines.get(index).trim().startsWith("```")) {
                    codeLines.add(lines.get(index));
                    index++;
                }
                if (index < lines.size()) {
                    index++;
                }
                body.append(codeBlock(codeLines, style));
                firstContentBlock = false;
                continue;
            }
            if (isTableStart(lines, index)) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(splitTableRow(lines.get(index)));
                index += 2;
                while (index < lines.size() && looksLikeTableRow(lines.get(index))) {
                    rows.add(splitTableRow(lines.get(index)));
                    index++;
                }
                body.append(table(rows, style));
                firstContentBlock = false;
                continue;
            }
            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0) {
                body.append(headingParagraph(
                    trimmed.substring(headingLevel).trim(),
                    headingLevel,
                    style,
                    firstContentBlock && style.titleCentered()
                ));
                firstContentBlock = false;
                index++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                body.append(paragraph(stripPrefix(trimmed, ">").trim(), style, ParagraphKind.QUOTE, false));
                firstContentBlock = false;
                index++;
                continue;
            }
            if (isBullet(trimmed)) {
                body.append(paragraph("• " + trimmed.substring(2).trim(), style, ParagraphKind.LIST, false));
                firstContentBlock = false;
                index++;
                continue;
            }
            int orderedPrefixLength = orderedPrefixLength(trimmed);
            if (orderedPrefixLength > 0) {
                body.append(paragraph(
                    trimmed.substring(0, orderedPrefixLength).trim() + " " + trimmed.substring(orderedPrefixLength).trim(),
                    style,
                    ParagraphKind.LIST,
                    false
                ));
                firstContentBlock = false;
                index++;
                continue;
            }
            List<String> paragraphLines = new ArrayList<>();
            while (index < lines.size() && canJoinParagraphLine(lines, index)) {
                paragraphLines.add(lines.get(index).trim());
                index++;
            }
            boolean centerFirstLine = firstContentBlock && style.titleCentered();
            body.append(paragraph(String.join(" ", paragraphLines), style, ParagraphKind.BODY, centerFirstLine));
            firstContentBlock = false;
        }
        body.append(sectionProperties(style));
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
            """.stripIndent()
            + body
            + """
              </w:body>
            </w:document>
            """.stripIndent();
    }

    private boolean canJoinParagraphLine(List<String> lines, int index) {
        String trimmed = lines.get(index).trim();
        return !trimmed.isBlank()
            && !trimmed.startsWith("```")
            && headingLevel(trimmed) == 0
            && !trimmed.startsWith(">")
            && !isBullet(trimmed)
            && orderedPrefixLength(trimmed) == 0
            && !isTableStart(lines, index);
    }

    private String headingParagraph(String text, int level, DocumentDeliveryStyle style, boolean centered) {
        int safeLevel = Math.max(1, Math.min(3, level));
        int size = switch (safeLevel) {
            case 1 -> style.heading1FontSize();
            case 2 -> style.heading2FontSize();
            default -> style.heading3FontSize();
        };
        StringBuilder pPr = new StringBuilder();
        pPr.append("<w:pPr><w:pStyle w:val=\"Heading").append(safeLevel).append("\"/>");
        if (style.headingFirstLineIndent() && !centered) {
            pPr.append(buildIndTag(style));
        }
        if (centered) {
            pPr.append("<w:jc w:val=\"center\"/>");
        }
        pPr.append("<w:spacing w:before=\"").append(safeLevel == 1 ? 240 : 180)
            .append("\" w:after=\"").append(safeLevel == 1 ? 160 : 120).append("\"/>");
        pPr.append("</w:pPr>");
        return "<w:p>" + pPr + runs(
            text,
            style.headingLatinFont(safeLevel),
            style.headingChineseFont(safeLevel),
            size,
            true,
            false
        ) + "</w:p>";
    }

    private String paragraph(String text, DocumentDeliveryStyle style, ParagraphKind kind, boolean centered) {
        String pPr = switch (kind) {
            case BODY -> {
                StringBuilder builder = new StringBuilder();
                builder.append("<w:pPr><w:spacing w:before=\"").append(twips(style.paragraphSpacingBefore()))
                    .append("\" w:after=\"").append(twips(style.paragraphSpacingAfter()))
                    .append("\" w:line=\"").append(style.resolvedLineTwips()).append("\" w:lineRule=\"").append(style.resolvedLineSpacingRule()).append("\"/>");
                if (!centered) {
                    builder.append(buildIndTag(style));
                }
                if (centered) {
                    builder.append("<w:jc w:val=\"center\"/>");
                }
                builder.append("</w:pPr>");
                yield builder.toString();
            }
            case LIST -> "<w:pPr><w:spacing w:before=\"" + twips(style.paragraphSpacingBefore()) + "\" w:after=\"" + twips(style.paragraphSpacingAfter()) + "\" w:line=\"" + style.resolvedLineTwips() + "\" w:lineRule=\"" + style.resolvedLineSpacingRule() + "\"/><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>";
            case QUOTE -> "<w:pPr><w:spacing w:before=\"" + twips(style.paragraphSpacingBefore()) + "\" w:after=\"" + twips(style.paragraphSpacingAfter()) + "\" w:line=\"" + style.resolvedLineTwips() + "\" w:lineRule=\"" + style.resolvedLineSpacingRule() + "\"/><w:ind w:left=\"480\"/></w:pPr>";
            case TABLE_CELL -> {
                StringBuilder builder = new StringBuilder();
                builder.append("<w:pPr><w:spacing w:after=\"0\" w:line=\"")
                    .append(style.resolvedLineTwips())
                    .append("\" w:lineRule=\"").append(style.resolvedLineSpacingRule()).append("\"/>");
                builder.append("<w:jc w:val=\"").append(xml(style.tableCellAlignment())).append("\"/>");
                builder.append("</w:pPr>");
                yield builder.toString();
            }
            case CODE -> "<w:pPr><w:spacing w:after=\"60\"/><w:shd w:fill=\"F6F8FA\"/></w:pPr>";
        };
        if (kind == ParagraphKind.TABLE_CELL) {
            return "<w:p>" + pPr + runs(
                text,
                style.tableResolvedLatinFont(),
                style.tableResolvedChineseFont(),
                style.tableResolvedFontSize(),
                false,
                false
            ) + "</w:p>";
        }
        return "<w:p>" + pPr + runs(text, style, style.bodyFontSize(), false, kind == ParagraphKind.QUOTE) + "</w:p>";
    }

    private String emptyParagraph(DocumentDeliveryStyle style) {
        return paragraph("", style, ParagraphKind.BODY, false);
    }

    private String codeBlock(List<String> codeLines, DocumentDeliveryStyle style) {
        StringBuilder result = new StringBuilder();
        for (String line : codeLines) {
            result.append("<w:p><w:pPr><w:spacing w:after=\"0\"/><w:shd w:fill=\"F6F8FA\"/></w:pPr>")
                .append(run(line, style, Math.max(8, style.bodyFontSize() - 1), false, false, true))
                .append("</w:p>");
        }
        return result.toString();
    }

    private String table(List<List<String>> rows, DocumentDeliveryStyle style) {
        if (rows.isEmpty()) {
            return "";
        }
        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount <= 0) {
            return "";
        }
        int contentWidthTwips = pageContentWidthTwips(style);
        int columnWidthTwips = Math.max(360, contentWidthTwips / columnCount);
        StringBuilder result = new StringBuilder();
        result.append("""
            <w:tbl>
              <w:tblPr>
                <w:tblW w:w="5000" w:type="pct"/>
                <w:tblLayout w:type="fixed"/>
                <w:tblBorders>
                  <w:top w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                  <w:left w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                  <w:bottom w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                  <w:right w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                  <w:insideH w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                  <w:insideV w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>
                </w:tblBorders>
              </w:tblPr>
              <w:tblGrid>
            """.stripIndent());
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            result.append("<w:gridCol w:w=\"").append(columnWidthTwips).append("\"/>");
        }
        result.append("</w:tblGrid>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            result.append("<w:tr>");
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                String cell = columnIndex < row.size() ? row.get(columnIndex) : "";
                result.append("<w:tc><w:tcPr><w:tcW w:w=\"")
                    .append(columnWidthTwips)
                    .append("\" w:type=\"dxa\"/>");
                if (rowIndex == 0) {
                    result.append("<w:shd w:fill=\"F6F8FA\"/>");
                }
                result.append("</w:tcPr>")
                    .append(paragraph(cell, style, ParagraphKind.TABLE_CELL, false))
                    .append("</w:tc>");
            }
            result.append("</w:tr>");
        }
        result.append("</w:tbl>").append(emptyParagraph(style));
        return result.toString();
    }

    private String runs(String text, DocumentDeliveryStyle style, int sizePt, boolean defaultBold, boolean defaultItalic) {
        return runs(text, style.latinFont(), style.chineseFont(), sizePt, defaultBold, defaultItalic);
    }

    private String runs(
        String text,
        String latinFont,
        String chineseFont,
        int sizePt,
        boolean defaultBold,
        boolean defaultItalic
    ) {
        List<InlineRun> runs = parseInline(text, defaultBold, defaultItalic);
        StringBuilder result = new StringBuilder();
        for (InlineRun inlineRun : runs) {
            result.append(run(inlineRun.text(), latinFont, chineseFont, sizePt, inlineRun.bold(), inlineRun.italic(), inlineRun.code()));
        }
        return result.toString();
    }

    private String run(String text, DocumentDeliveryStyle style, int sizePt, boolean bold, boolean italic, boolean code) {
        return run(text, style.latinFont(), style.chineseFont(), sizePt, bold, italic, code);
    }

    private String run(String text, String latinFont, String chineseFont, int sizePt, boolean bold, boolean italic, boolean code) {
        String font = code ? "Consolas" : latinFont;
        String eastAsiaFont = code ? "Consolas" : chineseFont;
        int halfPoint = Math.max(1, sizePt * 2);
        StringBuilder rPr = new StringBuilder();
        rPr.append("<w:rPr><w:rFonts w:ascii=\"").append(xml(font)).append("\" w:hAnsi=\"").append(xml(font)).append("\" w:eastAsia=\"").append(xml(eastAsiaFont)).append("\"/>")
            .append("<w:sz w:val=\"").append(halfPoint).append("\"/><w:szCs w:val=\"").append(halfPoint).append("\"/>");
        if (bold) {
            rPr.append("<w:b/><w:bCs/>");
        }
        if (italic) {
            rPr.append("<w:i/><w:iCs/>");
        }
        if (code) {
            rPr.append("<w:shd w:fill=\"F6F8FA\"/>");
        }
        rPr.append("</w:rPr>");
        return "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r>";
    }

    private List<InlineRun> parseInline(String text, boolean defaultBold, boolean defaultItalic) {
        List<InlineRun> result = new ArrayList<>();
        boolean bold = defaultBold;
        boolean italic = defaultItalic;
        boolean code = false;
        StringBuilder buffer = new StringBuilder();
        for (int index = 0; index < text.length();) {
            if (text.startsWith("**", index)) {
                flushInline(result, buffer, bold, italic, code);
                bold = !bold;
                index += 2;
            } else if (text.charAt(index) == '*' && !code) {
                flushInline(result, buffer, bold, italic, false);
                italic = !italic;
                index++;
            } else if (text.charAt(index) == '`') {
                flushInline(result, buffer, bold, italic, code);
                code = !code;
                index++;
            } else {
                buffer.append(text.charAt(index));
                index++;
            }
        }
        flushInline(result, buffer, bold, italic, code);
        if (result.isEmpty()) {
            result.add(new InlineRun("", bold, italic, code));
        }
        return result;
    }

    private void flushInline(List<InlineRun> result, StringBuilder buffer, boolean bold, boolean italic, boolean code) {
        if (buffer.length() == 0) {
            return;
        }
        result.add(new InlineRun(buffer.toString(), bold, italic, code));
        buffer.setLength(0);
    }

    private String sectionProperties(DocumentDeliveryStyle style) {
        return """
            <w:sectPr>
              <w:pgSz w:w="11906" w:h="16838"/>
              <w:pgMar w:top="%d" w:right="%d" w:bottom="%d" w:left="%d" w:header="720" w:footer="720" w:gutter="0"/>
            </w:sectPr>
            """.formatted(
            cmToTwips(style.marginTopCm()),
            cmToTwips(style.marginRightCm()),
            cmToTwips(style.marginBottomCm()),
            cmToTwips(style.marginLeftCm())
        );
    }

    private boolean isTableStart(List<String> lines, int index) {
        return index + 1 < lines.size()
            && looksLikeTableRow(lines.get(index))
            && isTableSeparator(lines.get(index + 1));
    }

    private boolean looksLikeTableRow(String line) {
        return line != null && line.contains("|") && splitTableRow(line).size() >= 2;
    }

    private boolean isTableSeparator(String line) {
        if (!looksLikeTableRow(line)) {
            return false;
        }
        return splitTableRow(line).stream().allMatch(cell -> TABLE_SEPARATOR_CELL.matcher(cell.trim()).matches());
    }

    private List<String> splitTableRow(String line) {
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] cells = normalized.split("\\|", -1);
        List<String> result = new ArrayList<>();
        for (String cell : cells) {
            result.add(cell.trim());
        }
        return result;
    }

    private int headingLevel(String trimmed) {
        int count = 0;
        while (count < trimmed.length() && count < 6 && trimmed.charAt(count) == '#') {
            count++;
        }
        if (count == 0 || count >= trimmed.length() || trimmed.charAt(count) != ' ') {
            return 0;
        }
        return Math.min(3, count);
    }

    private boolean isBullet(String trimmed) {
        return trimmed.length() > 2
            && (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ "));
    }

    private int orderedPrefixLength(String trimmed) {
        int index = 0;
        while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
            index++;
        }
        if (index == 0 || index + 1 >= trimmed.length()) {
            return 0;
        }
        char marker = trimmed.charAt(index);
        return (marker == '.' || marker == ')') && trimmed.charAt(index + 1) == ' ' ? index + 1 : 0;
    }

    private String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private int twips(int points) {
        return points * 20;
    }

    private String buildIndTag(DocumentDeliveryStyle style) {
        if ("cm".equalsIgnoreCase(style.firstLineIndentMode())) {
            return "<w:ind w:firstLine=\"" + cmToTwips(style.firstLineIndentCm()) + "\"/>";
        } else {
            int twips = (int) Math.round(style.bodyFontSize() * 20 * style.firstLineIndentChars());
            int chars = (int) Math.round(style.firstLineIndentChars() * 100);
            return "<w:ind w:firstLine=\"" + twips + "\" w:firstLineChars=\"" + chars + "\"/>";
        }
    }

    private int cmToTwips(double cm) {
        return (int) Math.round(cm / 2.54 * 1440);
    }

    private int pageContentWidthTwips(DocumentDeliveryStyle style) {
        int pageWidthTwips = 11906;
        return Math.max(1440, pageWidthTwips - cmToTwips(style.marginLeftCm()) - cmToTwips(style.marginRightCm()));
    }

    private String xml(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypesXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
              <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
            </Types>
            """.stripIndent();
    }

    private String rootRelationshipsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """.stripIndent();
    }

    private String documentRelationshipsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
            </Relationships>
            """.stripIndent();
    }

    private String settingsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:defaultTabStop w:val="420"/>
            </w:settings>
            """.stripIndent();
    }

    private String stylesXml(DocumentDeliveryStyle style) {
        String baseFonts = fontRunXml(style.latinFont(), style.chineseFont());
        String heading1Fonts = fontRunXml(style.headingLatinFont(1), style.headingChineseFont(1));
        String heading2Fonts = fontRunXml(style.headingLatinFont(2), style.headingChineseFont(2));
        String heading3Fonts = fontRunXml(style.headingLatinFont(3), style.headingChineseFont(3));
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                <w:name w:val="Normal"/>
                <w:rPr>
                  %s
                  <w:sz w:val="%d"/>
                  <w:szCs w:val="%d"/>
                </w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading1">
                <w:name w:val="heading 1"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr>%s<w:b/><w:bCs/><w:sz w:val="%d"/><w:szCs w:val="%d"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading2">
                <w:name w:val="heading 2"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr>%s<w:b/><w:bCs/><w:sz w:val="%d"/><w:szCs w:val="%d"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading3">
                <w:name w:val="heading 3"/>
                <w:basedOn w:val="Normal"/>
                <w:rPr>%s<w:b/><w:bCs/><w:sz w:val="%d"/><w:szCs w:val="%d"/></w:rPr>
              </w:style>
            </w:styles>
            """.formatted(
            baseFonts,
            style.bodyFontSize() * 2,
            style.bodyFontSize() * 2,
            heading1Fonts,
            style.heading1FontSize() * 2,
            style.heading1FontSize() * 2,
            heading2Fonts,
            style.heading2FontSize() * 2,
            style.heading2FontSize() * 2,
            heading3Fonts,
            style.heading3FontSize() * 2,
            style.heading3FontSize() * 2
        ).stripIndent();
    }

    private String fontRunXml(String latinFont, String chineseFont) {
        return """
            <w:rFonts w:ascii="%s" w:hAnsi="%s" w:eastAsia="%s"/>
            """.formatted(xml(latinFont), xml(latinFont), xml(chineseFont)).stripIndent();
    }

    private enum ParagraphKind {
        BODY,
        LIST,
        QUOTE,
        TABLE_CELL,
        CODE
    }

    private record InlineRun(String text, boolean bold, boolean italic, boolean code) {
    }
}
