package com.agentum.delivery.application;

import com.agentum.delivery.application.DocumentDeliveryStyle.ParagraphRule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * 轻量 DOCX 渲染器。
 *
 * <p>初版只覆盖 AI Markdown 交付最常见的受控子集，避免把运行态文档生成绑定到外部命令。
 * 后续如果接入 reference.docx 或复杂目录/图片，可在本类后面替换为 POI/docx4j/Worker 实现。</p>
 *
 * <p>渲染分两步：先把 Markdown 解析为 {@link Block} 列表并统计非表格段落总数与序号，
 * 再逐块渲染。两步式是为了支持"最后一行、倒数第二行"等需要总数的逐段个性化规则。</p>
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
        List<Block> blocks = parseBlocks(markdown);
        int totalContentParagraphs = 0;
        for (Block block : blocks) {
            if (isContentParagraph(block.type())) {
                totalContentParagraphs++;
            }
        }
        StringBuilder body = new StringBuilder();
        int contentIndex = 0;
        for (Block block : blocks) {
            switch (block.type()) {
                case BLANK -> body.append(emptyParagraph(
                    style,
                    bodyTextStyle(style),
                    "body",
                    0,
                    bodyParagraphSpacingFragment(style)
                ));
                case TABLE -> body.append(table(block.rows(), style));
                case CODE -> body.append(codeBlock(block.codeLines(), style));
                case HEADING, BODY, ORDERED_LIST, UNORDERED_LIST, QUOTE -> {
                    contentIndex++;
                    ParagraphRule rule = matchRule(style.paragraphRules(), contentIndex, totalContentParagraphs);
                    boolean centered = contentIndex == 1 && style.titleCentered();
                    ParagraphTextStyle targetTextStyle = resolvedParagraphTextStyle(block, style, rule);
                    appendBlankParagraphs(body, style, rule, rule == null ? 0 : rule.blankLinesBefore(), targetTextStyle);
                    if (block.type() == BlockType.HEADING) {
                        body.append(headingParagraph(block.text(), block.headingLevel(), style, centered, rule));
                    } else {
                        ParagraphKind kind = switch (block.type()) {
                            case ORDERED_LIST -> ParagraphKind.ORDERED_LIST;
                            case UNORDERED_LIST -> ParagraphKind.UNORDERED_LIST;
                            // 引用只保留 Markdown 语义识别和前缀剥离，排版完全按普通正文处理。
                            case QUOTE -> ParagraphKind.BODY;
                            default -> ParagraphKind.BODY;
                        };
                        body.append(paragraph(block.text(), style, kind, centered, rule));
                    }
                    appendBlankParagraphs(body, style, rule, rule == null ? 0 : rule.blankLinesAfter(), targetTextStyle);
                }
            }
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

    private List<Block> parseBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        List<String> lines = List.of(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        int consecutiveBlankLines = 0;
        for (int index = 0; index < lines.size();) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                consecutiveBlankLines++;
                // 单个空行仅作为分段不输出空段；连续多个空行才保留可见空段。
                if (consecutiveBlankLines > 1) {
                    blocks.add(Block.blank());
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
                blocks.add(Block.code(codeLines));
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
                blocks.add(Block.table(rows));
                continue;
            }
            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0) {
                blocks.add(Block.heading(headingLevel, trimmed.substring(countLeadingHash(trimmed)).trim()));
                index++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                blocks.add(Block.paragraph(BlockType.QUOTE, stripPrefix(trimmed, ">").trim()));
                index++;
                continue;
            }
            if (isBullet(trimmed)) {
                blocks.add(Block.paragraph(BlockType.UNORDERED_LIST, "• " + trimmed.substring(2).trim()));
                index++;
                continue;
            }
            int orderedPrefixLength = orderedPrefixLength(trimmed);
            if (orderedPrefixLength > 0) {
                blocks.add(Block.paragraph(
                    BlockType.ORDERED_LIST,
                    trimmed.substring(0, orderedPrefixLength).trim() + " " + trimmed.substring(orderedPrefixLength).trim()
                ));
                index++;
                continue;
            }
            List<String> paragraphLines = new ArrayList<>();
            while (index < lines.size() && canJoinParagraphLine(lines, index)) {
                paragraphLines.add(lines.get(index).trim());
                index++;
            }
            blocks.add(Block.paragraph(BlockType.BODY, String.join(" ", paragraphLines)));
        }
        return blocks;
    }

    private boolean isContentParagraph(BlockType type) {
        return type == BlockType.HEADING
            || type == BlockType.BODY
            || type == BlockType.ORDERED_LIST
            || type == BlockType.UNORDERED_LIST
            || type == BlockType.QUOTE;
    }

    /** 命中冲突时显式段号优先，同优先级后添加者覆盖。 */
    private ParagraphRule matchRule(List<ParagraphRule> rules, int index, int total) {
        ParagraphRule best = null;
        int bestPriority = -1;
        for (ParagraphRule rule : rules) {
            if (rule.matches(index, total) && rule.priority() >= bestPriority) {
                best = rule;
                bestPriority = rule.priority();
            }
        }
        return best;
    }

    private void appendBlankParagraphs(
        StringBuilder body,
        DocumentDeliveryStyle style,
        ParagraphRule rule,
        int count,
        ParagraphTextStyle targetTextStyle
    ) {
        if (count <= 0 || rule == null) {
            return;
        }
        String heightMode = rule.blankLineHeightMode();
        String paragraphSpacing = switch (heightMode) {
            case "target" -> paragraphSpacingFragment(style, rule);
            case "body" -> bodyParagraphSpacingFragment(style);
            default -> spacingFragment("pt", 0, 0);
        };
        for (int i = 0; i < count; i++) {
            body.append(emptyParagraph(
                style,
                targetTextStyle,
                heightMode,
                rule.blankLineHeightPt(),
                paragraphSpacing
            ));
        }
    }

    private ParagraphTextStyle resolvedParagraphTextStyle(Block block, DocumentDeliveryStyle style, ParagraphRule rule) {
        int size = style.bodyFontSize();
        String latinFont = style.latinFont();
        String chineseFont = style.chineseFont();
        if (block.type() == BlockType.HEADING) {
            size = style.headingFontSize(block.headingLevel());
            latinFont = style.headingLatinFont(block.headingLevel());
            chineseFont = style.headingChineseFont(block.headingLevel());
        }
        if (rule != null) {
            size = rule.fontSize() > 0 ? rule.fontSize() : size;
            latinFont = rule.latinFont().isBlank() ? latinFont : rule.latinFont();
            chineseFont = rule.chineseFont().isBlank() ? chineseFont : rule.chineseFont();
        }
        return new ParagraphTextStyle(latinFont, chineseFont, size);
    }

    private ParagraphTextStyle bodyTextStyle(DocumentDeliveryStyle style) {
        return new ParagraphTextStyle(style.latinFont(), style.chineseFont(), style.bodyFontSize());
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

    private String headingParagraph(String text, int level, DocumentDeliveryStyle style, boolean centered, ParagraphRule rule) {
        int safeLevel = Math.max(1, Math.min(5, level));
        int size = style.headingFontSize(safeLevel);
        String latinFont = style.headingLatinFont(safeLevel);
        String chineseFont = style.headingChineseFont(safeLevel);
        String numberFont = style.headingNumberFont(safeLevel);
        boolean bold = style.headingBold(safeLevel);
        if (rule != null) {
            if (rule.fontSize() > 0) {
                size = rule.fontSize();
            }
            if (!rule.latinFont().isBlank()) {
                latinFont = rule.latinFont();
            }
            if (!rule.chineseFont().isBlank()) {
                chineseFont = rule.chineseFont();
            }
            if (!rule.numberFont().isBlank()) {
                numberFont = rule.numberFont();
            }
        }
        StringBuilder pPr = new StringBuilder();
        pPr.append("<w:pPr><w:pStyle w:val=\"Heading").append(safeLevel).append("\"/>");
        pPr.append(headingSpacingTag(style, rule));
        String alignment = paragraphAlignment(style.headingAlignment(safeLevel), rule, centered);
        pPr.append(headingIndentTag(style, rule, size, "center".equals(alignment)));
        if (!alignment.isEmpty()) {
            pPr.append("<w:jc w:val=\"").append(alignment).append("\"/>");
        }
        pPr.append("</w:pPr>");
        return "<w:p>" + pPr + runs(text, latinFont, chineseFont, numberFont, size, bold, false) + "</w:p>";
    }

    private String paragraph(String text, DocumentDeliveryStyle style, ParagraphKind kind, boolean centered, ParagraphRule rule) {
        int size = style.bodyFontSize();
        String latinFont = style.latinFont();
        String chineseFont = style.chineseFont();
        String numberFont = style.numberFont();
        if (rule != null) {
            if (rule.fontSize() > 0) {
                size = rule.fontSize();
            }
            if (!rule.latinFont().isBlank()) {
                latinFont = rule.latinFont();
            }
            if (!rule.chineseFont().isBlank()) {
                chineseFont = rule.chineseFont();
            }
            if (!rule.numberFont().isBlank()) {
                numberFont = rule.numberFont();
            }
        }
        StringBuilder pPr = new StringBuilder();
        pPr.append("<w:pPr>");
        pPr.append("<w:spacing ").append(paragraphSpacingFragment(style, rule))
            .append(" w:line=\"").append(style.resolvedLineTwips())
            .append("\" w:lineRule=\"").append(style.resolvedLineSpacingRule()).append("\"/>");
        String alignment = paragraphAlignment(style.bodyAlignment(), rule, centered);
        boolean centerAligned = "center".equals(alignment);
        switch (kind) {
            case BODY -> pPr.append(bodyIndentTag(style, rule, size, centerAligned));
            case ORDERED_LIST -> pPr.append(listIndentTag(style, true, rule, size, centerAligned));
            case UNORDERED_LIST -> pPr.append(listIndentTag(style, false, rule, size, centerAligned));
        }
        if (!alignment.isEmpty()) {
            pPr.append("<w:jc w:val=\"").append(alignment).append("\"/>");
        }
        pPr.append("</w:pPr>");
        return "<w:p>" + pPr + runs(text, latinFont, chineseFont, numberFont, size, false, false) + "</w:p>";
    }

    /**
     * 计算段落对齐：逐段个性化规则优先，其次为首行单独居中，再次为正文或当前标题级别配置。
     */
    private String paragraphAlignment(String baseAlignment, ParagraphRule rule, boolean centered) {
        if (rule != null && !rule.alignment().isBlank()) {
            return "left".equals(rule.alignment()) ? "" : rule.alignment();
        }
        if (centered) {
            return "center";
        }
        return baseAlignment == null || baseAlignment.isBlank() || "left".equals(baseAlignment) ? "" : baseAlignment;
    }

    private String paragraphSpacingFragment(DocumentDeliveryStyle style, ParagraphRule rule) {
        String unit = style.paragraphSpacingUnit();
        double before = style.paragraphSpacingBefore();
        double after = style.paragraphSpacingAfter();
        if (rule != null && !rule.spacingUnit().isBlank()) {
            unit = rule.spacingUnit();
            before = rule.spacingBefore();
            after = rule.spacingAfter();
        }
        return spacingFragment(unit, before, after);
    }

    private String bodyParagraphSpacingFragment(DocumentDeliveryStyle style) {
        return spacingFragment(
            style.paragraphSpacingUnit(),
            style.paragraphSpacingBefore(),
            style.paragraphSpacingAfter()
        );
    }

    private String headingSpacingTag(DocumentDeliveryStyle style, ParagraphRule rule) {
        return "<w:spacing " + paragraphSpacingFragment(style, rule) + "/>";
    }

    private String headingIndentTag(DocumentDeliveryStyle style, ParagraphRule rule, int size, boolean centered) {
        if (rule != null && !rule.firstLineIndentMode().isBlank()) {
            if ("none".equals(rule.firstLineIndentMode())) {
                return "";
            }
            return buildIndTag(rule.firstLineIndentMode(), rule.firstLineIndentChars(), rule.firstLineIndentCm(), size);
        }
        if (style.headingFirstLineIndent() && !centered) {
            return buildIndTag(style.firstLineIndentMode(), style.firstLineIndentChars(), style.firstLineIndentCm(), style.bodyFontSize());
        }
        return "";
    }

    private String bodyIndentTag(DocumentDeliveryStyle style, ParagraphRule rule, int size, boolean centered) {
        if (rule != null && !rule.firstLineIndentMode().isBlank()) {
            if ("none".equals(rule.firstLineIndentMode())) {
                return "";
            }
            return buildIndTag(rule.firstLineIndentMode(), rule.firstLineIndentChars(), rule.firstLineIndentCm(), size);
        }
        if (centered) {
            return "";
        }
        return buildIndTag(style.firstLineIndentMode(), style.firstLineIndentChars(), style.firstLineIndentCm(), style.bodyFontSize());
    }

    /**
     * 列表不再强制悬挂缩进：系统或节点可分别配置有序、无序列表，逐段规则仍保持最高优先级。
     */
    private String listIndentTag(
        DocumentDeliveryStyle style,
        boolean ordered,
        ParagraphRule rule,
        int size,
        boolean centered
    ) {
        if (rule != null && !rule.firstLineIndentMode().isBlank()) {
            return bodyIndentTag(style, rule, size, centered);
        }
        String mode = ordered ? style.orderedListIndentMode() : style.unorderedListIndentMode();
        if ("none".equals(mode) || centered) {
            return "";
        }
        if ("hanging".equals(mode)) {
            double leftChars = ordered ? style.orderedListLeftIndentChars() : style.unorderedListLeftIndentChars();
            double hangingChars = ordered ? style.orderedListHangingIndentChars() : style.unorderedListHangingIndentChars();
            return buildHangingIndTag(leftChars, hangingChars, size);
        }
        return bodyIndentTag(style, null, size, centered);
    }

    /**
     * 可见空行使用独立空段落，等价于在 Word 中手动回车。
     * target/body 模式继承对应段落的字号、字体与段距；exact 模式只使用固定磅值行高且段距归零。
     * 字体和字号必须写入 pPr/rPr（段落标记格式），否则 Word 会忽略空文本 Run 并按默认字号压缩空行。
     */
    private String emptyParagraph(
        DocumentDeliveryStyle style,
        ParagraphTextStyle targetTextStyle,
        String blankLineHeightMode,
        int blankLineHeightPt,
        String paragraphSpacing
    ) {
        ParagraphTextStyle textStyle = "target".equals(blankLineHeightMode)
            ? targetTextStyle
            : bodyTextStyle(style);
        int lineTwips = "exact".equals(blankLineHeightMode)
            ? blankLineHeightPt * 20
            : style.resolvedLineTwips();
        String lineRule = "exact".equals(blankLineHeightMode)
            ? "exact"
            : style.resolvedLineSpacingRule();
        int halfPoint = Math.max(1, textStyle.fontSize() * 2);
        String paragraphMarkProperties = "<w:rPr>"
            + fontRunXml(textStyle.latinFont(), textStyle.chineseFont())
            + "<w:sz w:val=\"" + halfPoint + "\"/><w:szCs w:val=\"" + halfPoint + "\"/></w:rPr>";
        String pPr = "<w:pPr><w:spacing " + paragraphSpacing + " w:line=\"" + lineTwips
            + "\" w:lineRule=\"" + lineRule + "\"/>" + paragraphMarkProperties + "</w:pPr>";
        return "<w:p>" + pPr + "</w:p>";
    }

    private String codeBlock(List<String> codeLines, DocumentDeliveryStyle style) {
        StringBuilder result = new StringBuilder();
        for (String line : codeLines) {
            result.append("<w:p><w:pPr><w:spacing w:after=\"0\"/><w:shd w:fill=\"F6F8FA\"/></w:pPr>")
                .append(run(line, style.latinFont(), style.chineseFont(), Math.max(8, style.bodyFontSize() - 1), false, false, true))
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
            """.stripIndent());
        if (style.tableBorders()) {
            int borderSize = Math.max(2, (int) Math.round(style.tableBorderWidthPt() * 8));
            result.append("<w:tblBorders>");
            for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
                result.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"")
                    .append(borderSize).append("\" w:space=\"0\" w:color=\"auto\"/>");
            }
            result.append("</w:tblBorders>");
        }
        // 单元格上下内边距，避免文字直接顶住框线。
        int verticalPaddingTwips = Math.max(0, (int) Math.round(style.tableCellPaddingVerticalPt() * 20));
        result.append("<w:tblCellMar>")
            .append("<w:top w:w=\"").append(verticalPaddingTwips).append("\" w:type=\"dxa\"/>")
            .append("<w:left w:w=\"108\" w:type=\"dxa\"/>")
            .append("<w:bottom w:w=\"").append(verticalPaddingTwips).append("\" w:type=\"dxa\"/>")
            .append("<w:right w:w=\"108\" w:type=\"dxa\"/>")
            .append("</w:tblCellMar>");
        result.append("</w:tblPr><w:tblGrid>");
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
                result.append("<w:vAlign w:val=\"").append(xml(style.tableCellVerticalAlignment())).append("\"/>");
                result.append("</w:tcPr>")
                    .append(tableCellParagraph(cell, style, rowIndex == 0 && style.tableHeaderBold()))
                    .append("</w:tc>");
            }
            result.append("</w:tr>");
        }
        result.append("</w:tbl>").append(emptyParagraph(
            style,
            bodyTextStyle(style),
            "body",
            0,
            spacingFragment("pt", 0, 0)
        ));
        return result.toString();
    }

    private String runs(
        String text,
        String latinFont,
        String chineseFont,
        String numberFont,
        int sizePt,
        boolean defaultBold,
        boolean defaultItalic
    ) {
        List<InlineRun> runs = parseInline(text, defaultBold, defaultItalic);
        StringBuilder result = new StringBuilder();
        for (InlineRun inlineRun : runs) {
            appendRunsByNumberFont(result, inlineRun, latinFont, chineseFont, numberFont, sizePt);
        }
        return result.toString();
    }

    private void appendRunsByNumberFont(
        StringBuilder result,
        InlineRun inlineRun,
        String latinFont,
        String chineseFont,
        String numberFont,
        int sizePt
    ) {
        String text = inlineRun.text();
        if (inlineRun.code() || text.isEmpty()) {
            result.append(run(text, latinFont, chineseFont, sizePt, inlineRun.bold(), inlineRun.italic(), inlineRun.code()));
            return;
        }
        int segmentStart = 0;
        boolean numberSegment = Character.isDigit(text.charAt(0));
        for (int index = 1; index <= text.length(); index++) {
            boolean boundary = index == text.length() || Character.isDigit(text.charAt(index)) != numberSegment;
            if (boundary) {
                String segment = text.substring(segmentStart, index);
                result.append(run(
                    segment,
                    numberSegment ? numberFont : latinFont,
                    chineseFont,
                    sizePt,
                    inlineRun.bold(),
                    inlineRun.italic(),
                    false
                ));
                if (index < text.length()) {
                    segmentStart = index;
                    numberSegment = !numberSegment;
                }
            }
        }
    }

    private String tableCellParagraph(String text, DocumentDeliveryStyle style, boolean bold) {
        String pPr = "<w:pPr><w:spacing w:after=\"0\" w:line=\"" + style.resolvedTableLineTwips()
            + "\" w:lineRule=\"" + style.resolvedTableLineSpacingRule() + "\"/><w:jc w:val=\""
            + xml(style.tableCellAlignment()) + "\"/></w:pPr>";
        return "<w:p>" + pPr + runs(
            text,
            style.tableResolvedLatinFont(),
            style.tableResolvedChineseFont(),
            style.tableResolvedNumberFont(),
            style.tableResolvedFontSize(),
            bold,
            false
        ) + "</w:p>";
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
        int count = countLeadingHash(trimmed);
        if (count == 0 || count >= trimmed.length() || trimmed.charAt(count) != ' ') {
            return 0;
        }
        // Markdown 最多六级，六级标题统一按五级渲染。
        return Math.min(5, count);
    }

    private int countLeadingHash(String trimmed) {
        int count = 0;
        while (count < trimmed.length() && count < 6 && trimmed.charAt(count) == '#') {
            count++;
        }
        return count;
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

    private String spacingFragment(String unit, double before, double after) {
        if ("line".equalsIgnoreCase(unit)) {
            long beforeLines = Math.round(before * 100);
            long afterLines = Math.round(after * 100);
            return "w:beforeLines=\"" + beforeLines + "\" w:before=\"0\" w:afterLines=\"" + afterLines + "\" w:after=\"0\"";
        }
        return "w:before=\"" + spacingToTwips(before, unit) + "\" w:after=\"" + spacingToTwips(after, unit) + "\"";
    }

    private int spacingToTwips(double value, String unit) {
        String normalized = unit == null ? "pt" : unit.toLowerCase();
        return switch (normalized) {
            case "cm" -> (int) Math.round(value / 2.54 * 1440);
            case "mm" -> (int) Math.round(value / 25.4 * 1440);
            default -> (int) Math.round(value * 20);
        };
    }

    private String buildIndTag(String mode, double chars, double cm, int sizePt) {
        if ("cm".equalsIgnoreCase(mode)) {
            return "<w:ind w:firstLine=\"" + cmToTwips(cm) + "\"/>";
        }
        int twips = (int) Math.round(sizePt * 20 * chars);
        int charsHundredths = (int) Math.round(chars * 100);
        return "<w:ind w:firstLine=\"" + twips + "\" w:firstLineChars=\"" + charsHundredths + "\"/>";
    }

    private String buildHangingIndTag(double leftChars, double hangingChars, int sizePt) {
        double safeLeftChars = Math.max(0, leftChars);
        double safeHangingChars = Math.min(safeLeftChars, Math.max(0, hangingChars));
        int leftTwips = (int) Math.round(sizePt * 20 * safeLeftChars);
        int hangingTwips = (int) Math.round(sizePt * 20 * safeHangingChars);
        int leftCharsHundredths = (int) Math.round(safeLeftChars * 100);
        int hangingCharsHundredths = (int) Math.round(safeHangingChars * 100);
        return "<w:ind w:left=\"" + leftTwips + "\" w:leftChars=\"" + leftCharsHundredths
            + "\" w:hanging=\"" + hangingTwips + "\" w:hangingChars=\"" + hangingCharsHundredths + "\"/>";
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
        StringBuilder styles = new StringBuilder();
        styles.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            .append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        styles.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">")
            .append("<w:name w:val=\"Normal\"/>")
            .append("<w:rPr>")
            .append(fontRunXml(style.latinFont(), style.chineseFont()))
            .append("<w:sz w:val=\"").append(style.bodyFontSize() * 2).append("\"/>")
            .append("<w:szCs w:val=\"").append(style.bodyFontSize() * 2).append("\"/>")
            .append("</w:rPr></w:style>");
        // 标题样式仅声明字体与字号，是否加粗完全交由 run 级 rPr 控制，便于"不加粗"生效。
        for (int level = 1; level <= 5; level++) {
            int size = style.headingFontSize(level) * 2;
            styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level).append("\">")
                .append("<w:name w:val=\"heading ").append(level).append("\"/>")
                .append("<w:basedOn w:val=\"Normal\"/>")
                .append("<w:rPr>")
                .append(fontRunXml(style.headingLatinFont(level), style.headingChineseFont(level)))
                .append("<w:sz w:val=\"").append(size).append("\"/><w:szCs w:val=\"").append(size).append("\"/>")
                .append("</w:rPr></w:style>");
        }
        styles.append("</w:styles>");
        return styles.toString();
    }

    private String fontRunXml(String latinFont, String chineseFont) {
        return "<w:rFonts w:ascii=\"" + xml(latinFont) + "\" w:hAnsi=\"" + xml(latinFont) + "\" w:eastAsia=\"" + xml(chineseFont) + "\"/>";
    }

    private enum ParagraphKind {
        BODY,
        ORDERED_LIST,
        UNORDERED_LIST
    }

    private enum BlockType {
        HEADING,
        BODY,
        ORDERED_LIST,
        UNORDERED_LIST,
        QUOTE,
        TABLE,
        CODE,
        BLANK
    }

    private record Block(BlockType type, int headingLevel, String text, List<List<String>> rows, List<String> codeLines) {

        static Block blank() {
            return new Block(BlockType.BLANK, 0, "", null, null);
        }

        static Block heading(int level, String text) {
            return new Block(BlockType.HEADING, level, text, null, null);
        }

        static Block paragraph(BlockType type, String text) {
            return new Block(type, 0, text, null, null);
        }

        static Block table(List<List<String>> rows) {
            return new Block(BlockType.TABLE, 0, "", rows, null);
        }

        static Block code(List<String> codeLines) {
            return new Block(BlockType.CODE, 0, "", null, codeLines);
        }
    }

    private record InlineRun(String text, boolean bold, boolean italic, boolean code) {
    }

    private record ParagraphTextStyle(String latinFont, String chineseFont, int fontSize) {
    }
}
