package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class MarkdownDocxRendererTest {

    private final MarkdownDocxRenderer renderer = new MarkdownDocxRenderer();

    @Test
    void shouldRenderCommonMarkdownBlocksIntoDocxPackage() throws IOException {
        byte[] bytes = renderer.render("""
            # 风险摘要

            正文内容包含 **重点** 和 `代码`。

            - 第一项
            - 第二项

            | 字段 | 内容 |
            | --- | --- |
            | 结论 | 通过 |
            """, DocumentDeliveryStyle.defaults());

        Map<String, String> entries = unzip(bytes);

        assertThat(entries).containsKeys(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml",
            "word/styles.xml",
            "word/settings.xml"
        );
        assertThat(entries.get("word/document.xml"))
            .contains("风险摘要")
            .contains("正文内容包含")
            .contains("第一项")
            .contains("结论")
            .contains("通过");
        assertThat(entries.get("word/styles.xml"))
            .contains("宋体")
            .contains("Times New Roman");
    }

    @Test
    void shouldRenderCenteredTitleAndChineseFontSizeNames() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "bodyFontSize", "小四",
            "heading1FontSize", "四号",
            "titleCentered", true
        ));

        byte[] bytes = renderer.render("居中标题\n\n正文", style);

        Map<String, String> entries = unzip(bytes);
        assertThat(style.bodyFontSize()).isEqualTo(12);
        assertThat(style.heading1FontSize()).isEqualTo(14);
        assertThat(style.titleCentered()).isTrue();
        assertThat(entries.get("word/document.xml"))
            .contains("居中标题")
            .contains("<w:jc w:val=\"center\"/>");
        assertThat(entries.get("word/styles.xml"))
            .contains("<w:sz w:val=\"24\"/>")
            .contains("<w:sz w:val=\"28\"/>");
    }

    @Test
    void shouldApplyFirstLineIndentToHeadingsWhenEnabled() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "bodyFontSize", 12,
            "firstLineIndentChars", 2,
            "headingFirstLineIndent", true
        ));

        byte[] bytes = renderer.render("# 一级标题\n\n正文", style);

        Map<String, String> entries = unzip(bytes);
        assertThat(entries.get("word/document.xml"))
            .contains("一级标题")
            .contains("<w:ind w:firstLine=\"480\" w:firstLineChars=\"200\"/>");
    }

    @Test
    void shouldApplyFirstLineIndentInCentimeters() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "firstLineIndentMode", "cm",
            "firstLineIndentCm", 0.75,
            "headingFirstLineIndent", true
        ));

        byte[] bytes = renderer.render("# 一级标题\n\n正文", style);

        Map<String, String> entries = unzip(bytes);
        assertThat(entries.get("word/document.xml"))
            .contains("一级标题")
            .contains("<w:ind w:firstLine=\"425\"/>");
    }

    @Test
    void shouldApplyHeadingFontsAndTableStyles() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.ofEntries(
            Map.entry("numberFont", "Calibri"),
            Map.entry("heading1ChineseFont", "黑体"),
            Map.entry("heading1LatinFont", "Arial"),
            Map.entry("heading1NumberFont", "Georgia"),
            Map.entry("heading2ChineseFont", "仿宋_GB2312"),
            Map.entry("tableChineseFont", "楷体"),
            Map.entry("tableLatinFont", "Georgia"),
            Map.entry("tableNumberFont", "Arial"),
            Map.entry("tableFontSize", 10),
            Map.entry("tableCellAlignment", "center"),
            Map.entry("tableHeaderBold", true),
            Map.entry("tableBorders", true),
            Map.entry("tableBorderWidthPt", 1),
            Map.entry("tableLineSpacingMode", "exact"),
            Map.entry("tableLineSpacingPt", 16)
        ));

        byte[] bytes = renderer.render("""
            # 一级标题 2026

            ## 二级标题

            | 字段 | 2026 内容 |
            | --- | --- |
            | 结论 | 通过 |
            """, style);

        Map<String, String> entries = unzip(bytes);
        assertThat(style.headingChineseFont(1)).isEqualTo("黑体");
        assertThat(style.headingLatinFont(1)).isEqualTo("Arial");
        assertThat(style.headingNumberFont(1)).isEqualTo("Georgia");
        assertThat(style.headingChineseFont(2)).isEqualTo("仿宋_GB2312");
        assertThat(style.tableResolvedFontSize()).isEqualTo(10);
        assertThat(entries.get("word/document.xml"))
            .contains("黑体")
            .contains("Arial")
            .contains("仿宋_GB2312")
            .contains("楷体")
            .contains("Georgia")
            .contains("<w:t xml:space=\"preserve\">2026</w:t>")
            .contains("<w:jc w:val=\"center\"/>")
            .contains("<w:sz w:val=\"20\"/>")
            .contains("<w:top w:val=\"single\" w:sz=\"8\"")
            .contains("w:line=\"320\" w:lineRule=\"exact\"")
            .contains("<w:b/><w:bCs/>")
            .doesNotContain("<w:shd w:fill=\"F6F8FA\"/>");
        assertThat(entries.get("word/styles.xml"))
            .contains("黑体")
            .contains("仿宋_GB2312");
    }

    @Test
    void shouldRemoveTableBordersAndKeepHeaderPlainByDefault() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of("tableBorders", false));

        byte[] bytes = renderer.render("| 表头 | 数值 |\n| --- | --- |\n| 一 | 1 |", style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .doesNotContain("<w:tblBorders>")
            .doesNotContain("<w:shd")
            .doesNotContain("<w:b/>");
    }

    @Test
    void shouldApplyExactLineSpacingInPoints() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "lineSpacingMode", "exact",
            "lineSpacingPt", 22
        ));

        byte[] bytes = renderer.render("固定行距正文", style);

        Map<String, String> entries = unzip(bytes);
        assertThat(style.resolvedLineSpacingRule()).isEqualTo("exact");
        assertThat(style.resolvedLineTwips()).isEqualTo(440);
        assertThat(entries.get("word/document.xml"))
            .contains("w:lineRule=\"exact\"")
            .contains("w:line=\"440\"");
    }

    @Test
    void shouldRenderTableAcrossPageContentWidth() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "marginLeftCm", 3.18,
            "marginRightCm", 3.18
        ));

        byte[] bytes = renderer.render("""
            | 资产类别 | 本月涨跌幅 | 年初至今涨跌幅 |
            | --- | --- | --- |
            | 沪深300 | +2.1% | +8.5% |
            """, style);

        int contentWidthTwips = 11906 - (int) Math.round(3.18 / 2.54 * 1440) * 2;
        int columnWidthTwips = Math.max(360, contentWidthTwips / 3);
        Map<String, String> entries = unzip(bytes);
        assertThat(entries.get("word/document.xml"))
            .contains("<w:tblW w:w=\"5000\" w:type=\"pct\"/>")
            .contains("<w:tblLayout w:type=\"fixed\"/>")
            .contains("<w:gridCol w:w=\"" + columnWidthTwips + "\"/>")
            .contains("<w:tcW w:w=\"" + columnWidthTwips + "\" w:type=\"dxa\"/>");
    }

    @Test
    void shouldNotRenderEmptyParagraphsForSingleBlankLinesButRenderForConsecutiveBlankLines() throws IOException {
        byte[] bytesSingle = renderer.render("第一段\n\n第二段", DocumentDeliveryStyle.defaults());
        Map<String, String> entriesSingle = unzip(bytesSingle);
        String docXmlSingle = entriesSingle.get("word/document.xml");

        assertThat(docXmlSingle).contains("第一段").contains("第二段");
        assertThat(docXmlSingle).doesNotContain("<w:t xml:space=\"preserve\"></w:t>");

        byte[] bytesDouble = renderer.render("第一段\n\n\n第二段", DocumentDeliveryStyle.defaults());
        Map<String, String> entriesDouble = unzip(bytesDouble);
        String docXmlDouble = entriesDouble.get("word/document.xml");

        assertThat(docXmlDouble).contains("第一段").contains("第二段");
        assertThat(docXmlDouble).contains("<w:t xml:space=\"preserve\"></w:t>");

        int firstIndex = docXmlDouble.indexOf("<w:t xml:space=\"preserve\"></w:t>");
        int lastIndex = docXmlDouble.lastIndexOf("<w:t xml:space=\"preserve\"></w:t>");
        assertThat(firstIndex).isEqualTo(lastIndex).isNotEqualTo(-1);
    }

    @Test
    void shouldApplyConfiguredSpacingToHeadings() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphSpacingBefore", 10,
            "paragraphSpacingAfter", 15
        ));

        byte[] bytes = renderer.render("# 一级标题\n\n## 二级标题", style);

        Map<String, String> entries = unzip(bytes);
        String docXml = entries.get("word/document.xml");

        assertThat(docXml)
            .contains("<w:spacing w:before=\"200\" w:after=\"300\"/>");
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
