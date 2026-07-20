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
    void shouldRenderHeadingAlignmentAndChineseFontSizeNames() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "bodyFontSize", "小四",
            "heading1FontSize", "四号",
            "heading1Alignment", "center"
        ));

        byte[] bytes = renderer.render("# 居中标题\n\n正文", style);

        Map<String, String> entries = unzip(bytes);
        assertThat(style.bodyFontSize()).isEqualTo(12);
        assertThat(style.heading1FontSize()).isEqualTo(14);
        assertThat(style.headingAlignment(1)).isEqualTo("center");
        assertThat(entries.get("word/document.xml"))
            .contains("居中标题")
            .contains("<w:jc w:val=\"center\"/>");
        assertThat(entries.get("word/styles.xml"))
            .contains("<w:sz w:val=\"24\"/>")
            .contains("<w:sz w:val=\"28\"/>");
    }

    @Test
    void shouldApplyAlignmentIndependentlyForEachHeadingLevel() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "heading1Alignment", "center",
            "heading2Alignment", "right",
            "heading3Alignment", "both"
        ));

        byte[] bytes = renderer.render("# 一级\n\n## 二级\n\n### 三级", style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("<w:pStyle w:val=\"Heading1\"/><w:spacing")
            .contains("<w:jc w:val=\"center\"/>")
            .contains("<w:jc w:val=\"right\"/>")
            .contains("<w:jc w:val=\"both\"/>");
    }

    @Test
    void shouldLetParagraphRuleOverrideFirstContentAndHeadingAlignment() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "titleCentered", true,
            "heading1Alignment", "right",
            "headingFirstLineIndent", true,
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "first",
                "alignment", "left"
            ))
        ));

        byte[] bytes = renderer.render("# 首行标题", style);

        assertThat(unzip(bytes).get("word/document.xml"))
            .doesNotContain("<w:jc w:val=\"center\"/>")
            .doesNotContain("<w:jc w:val=\"right\"/>")
            .contains("<w:ind w:firstLine=\"480\" w:firstLineChars=\"200\"/>");
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
    void shouldRenderQuotesWithBodyStyleWithoutForcedIndentOrItalic() throws IOException {
        byte[] bytes = renderer.render("> 引用内容", DocumentDeliveryStyle.defaults());

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("引用内容")
            .contains("<w:ind w:firstLine=\"480\" w:firstLineChars=\"200\"/>")
            .doesNotContain("<w:ind w:left=\"480\"/>")
            .doesNotContain("<w:i/>")
            .doesNotContain("<w:iCs/>");
    }

    @Test
    void shouldLetOrderedAndUnorderedListsInheritBodyIndentByDefault() throws IOException {
        byte[] bytes = renderer.render("1. 有序列表\n\n- 无序列表", DocumentDeliveryStyle.defaults());

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("1.")
            .contains("•")
            .contains("<w:ind w:firstLine=\"480\" w:firstLineChars=\"200\"/>")
            .doesNotContain("w:hanging=");
    }

    @Test
    void shouldApplyIndependentHangingAndNoIndentListStyles() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "orderedListIndentMode", "hanging",
            "orderedListLeftIndentChars", 4,
            "orderedListHangingIndentChars", 2,
            "unorderedListIndentMode", "none"
        ));

        byte[] bytes = renderer.render("1. 有序列表\n\n- 无序列表", style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("<w:ind w:left=\"960\" w:leftChars=\"400\" w:hanging=\"480\" w:hangingChars=\"200\"/>");
        assertThat(documentXml.split("<w:ind", -1)).hasSize(2);
    }

    @Test
    void shouldAllowParagraphRuleToOverrideListIndent() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "orderedListIndentMode", "hanging",
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "first",
                "firstLineIndentMode", "chars",
                "firstLineIndentChars", 1
            ))
        ));

        byte[] bytes = renderer.render("1. 个性化列表", style);

        assertThat(unzip(bytes).get("word/document.xml"))
            .contains("<w:ind w:firstLine=\"240\" w:firstLineChars=\"100\"/>")
            .doesNotContain("w:hanging=");
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
        assertThat(docXmlSingle.split("<w:p>", -1)).hasSize(3);

        byte[] bytesDouble = renderer.render("第一段\n\n\n第二段", DocumentDeliveryStyle.defaults());
        Map<String, String> entriesDouble = unzip(bytesDouble);
        String docXmlDouble = entriesDouble.get("word/document.xml");

        assertThat(docXmlDouble).contains("第一段").contains("第二段");
        assertThat(docXmlDouble.split("<w:p>", -1)).hasSize(4);
        assertThat(docXmlDouble)
            .contains("<w:spacing w:before=\"0\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/><w:rPr>");
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

    @Test
    void shouldApplyTableVerticalAlignmentAndCellPadding() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "tableCellVerticalAlignment", "top",
            "tableCellPaddingVerticalPt", 2
        ));

        byte[] bytes = renderer.render("| 表头 | 数值 |\n| --- | --- |\n| 一 | 1 |", style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("<w:vAlign w:val=\"top\"/>")
            .contains("<w:top w:w=\"40\" w:type=\"dxa\"/>")
            .contains("<w:bottom w:w=\"40\" w:type=\"dxa\"/>");
    }

    @Test
    void shouldApplyBodyJustifyAlignment() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of("bodyAlignment", "both"));

        byte[] bytes = renderer.render("正文两端对齐内容", style);

        assertThat(unzip(bytes).get("word/document.xml")).contains("<w:jc w:val=\"both\"/>");
    }

    @Test
    void shouldApplyParagraphSpacingInLineUnit() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphSpacingUnit", "line",
            "paragraphSpacingBefore", 1,
            "paragraphSpacingAfter", 2
        ));

        byte[] bytes = renderer.render("行单位段距正文", style);

        assertThat(unzip(bytes).get("word/document.xml"))
            .contains("w:beforeLines=\"100\"")
            .contains("w:afterLines=\"200\"");
    }

    @Test
    void shouldApplyParagraphSpacingInCentimeters() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphSpacingUnit", "cm",
            "paragraphSpacingBefore", 1,
            "paragraphSpacingAfter", 0
        ));

        byte[] bytes = renderer.render("厘米单位段距正文", style);

        assertThat(unzip(bytes).get("word/document.xml")).contains("w:before=\"567\"");
    }

    @Test
    void shouldRenderHeadingLevelsFourAndFiveAndMapSixToFive() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.defaults();

        byte[] bytes = renderer.render("""
            #### 四级标题

            ##### 五级标题

            ###### 六级标题
            """, style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("<w:pStyle w:val=\"Heading4\"/>")
            .contains("<w:pStyle w:val=\"Heading5\"/>")
            .contains("六级标题");
        // 四级标题字号默认继承三级（13pt → 半磅 26）。
        assertThat(style.headingFontSize(4)).isEqualTo(13);
    }

    @Test
    void shouldRenderHeadingWithoutBoldWhenDisabled() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of("heading1Bold", false));

        byte[] bytes = renderer.render("# 不加粗标题", style);

        assertThat(unzip(bytes).get("word/document.xml")).doesNotContain("<w:b/>");
    }

    @Test
    void shouldApplyParagraphRuleByIndexWithBlankLines() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "index",
                "targetIndex", 1,
                "alignment", "right",
                "blankLinesAfter", 1
            ))
        ));

        byte[] bytes = renderer.render("第一段\n\n第二段", style);

        assertThat(unzip(bytes).get("word/document.xml"))
            .contains("<w:jc w:val=\"right\"/>")
            .contains("<w:spacing w:before=\"0\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/><w:rPr>");
    }

    @Test
    void shouldWriteTargetParagraphFormatToBlankParagraphMark() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "heading1FontSize", 20,
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "first",
                "blankLinesAfter", 1
            ))
        ));

        byte[] bytes = renderer.render("# 大标题", style);

        String documentXml = unzip(bytes).get("word/document.xml");
        assertThat(documentXml)
            .contains("<w:spacing w:before=\"0\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/><w:rPr>"
                + "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/>"
                + "<w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/></w:rPr></w:pPr></w:p>")
            .doesNotContain("<w:t xml:space=\"preserve\"></w:t>");
        assertThat(documentXml.split("<w:sz w:val=\"40\"/>", -1)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldApplyExactBlankLineHeightWithoutParagraphSpacing() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphSpacingBefore", 12,
            "paragraphSpacingAfter", 18,
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "first",
                "blankLinesAfter", 1,
                "blankLineHeightMode", "exact",
                "blankLineHeightPt", 24
            ))
        ));

        byte[] bytes = renderer.render("正文", style);

        assertThat(unzip(bytes).get("word/document.xml"))
            .contains("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"480\" w:lineRule=\"exact\"/>");
    }

    @Test
    void shouldApplyParagraphRuleWithLastSelector() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "last",
                "chineseFont", "楷体"
            ))
        ));

        byte[] bytes = renderer.render("第一段\n\n最后一段", style);

        assertThat(unzip(bytes).get("word/document.xml")).contains("楷体");
    }

    @Test
    void shouldRoundTripParagraphRulesThroughMap() {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "index",
                "targetIndex", 2,
                "alignment", "center",
                "fontSize", "三号",
                "spacingUnit", "line",
                "spacingBefore", 0,
                "spacingAfter", 1
            ))
        ));

        assertThat(style.paragraphRules()).hasSize(1);
        DocumentDeliveryStyle.ParagraphRule rule = style.paragraphRules().get(0);
        assertThat(rule.targetType()).isEqualTo("index");
        assertThat(rule.targetIndex()).isEqualTo(2);
        assertThat(rule.fontSize()).isEqualTo(16);

        DocumentDeliveryStyle reparsed = DocumentDeliveryStyle.from(style.toMap());
        assertThat(reparsed.paragraphRules()).hasSize(1);
        assertThat(reparsed.paragraphRules().get(0).alignment()).isEqualTo("center");
        assertThat(reparsed.paragraphRules().get(0).spacingUnit()).isEqualTo("line");
    }

    @Test
    void shouldRoundTripListIndentStylesThroughMap() {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "orderedListIndentMode", "hanging",
            "orderedListLeftIndentChars", 4,
            "orderedListHangingIndentChars", 2,
            "unorderedListIndentMode", "none"
        ));

        DocumentDeliveryStyle reparsed = DocumentDeliveryStyle.from(style.toMap());

        assertThat(reparsed.orderedListIndentMode()).isEqualTo("hanging");
        assertThat(reparsed.orderedListLeftIndentChars()).isEqualTo(4);
        assertThat(reparsed.orderedListHangingIndentChars()).isEqualTo(2);
        assertThat(reparsed.unorderedListIndentMode()).isEqualTo("none");
    }

    @Test
    void shouldRoundTripHeadingAlignmentAndParagraphBlankLineStyleThroughMap() {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "heading1Alignment", "center",
            "heading5Alignment", "right",
            "titleCentered", true,
            "paragraphRules", java.util.List.of(Map.of(
                "targetType", "first",
                "blankLinesBefore", 1,
                "blankLineHeightMode", "exact",
                "blankLineHeightPt", 24
            ))
        ));

        DocumentDeliveryStyle reparsed = DocumentDeliveryStyle.from(style.toMap());

        assertThat(reparsed.headingAlignment(1)).isEqualTo("center");
        assertThat(reparsed.headingAlignment(5)).isEqualTo("right");
        assertThat(reparsed.titleCentered()).isTrue();
        assertThat(reparsed.paragraphRules()).singleElement().satisfies(rule -> {
            assertThat(rule.blankLinesBefore()).isEqualTo(1);
            assertThat(rule.blankLineHeightMode()).isEqualTo("exact");
            assertThat(rule.blankLineHeightPt()).isEqualTo(24);
        });
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
