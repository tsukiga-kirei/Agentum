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
            .contains("<w:ind w:firstLine=\"480\"/>");
    }

    @Test
    void shouldApplyHeadingFontsAndTableStyles() throws IOException {
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(Map.of(
            "heading1ChineseFont", "黑体",
            "heading1LatinFont", "Arial",
            "heading2ChineseFont", "仿宋_GB2312",
            "tableChineseFont", "楷体",
            "tableLatinFont", "Georgia",
            "tableFontSize", 10,
            "tableCellAlignment", "center"
        ));

        byte[] bytes = renderer.render("""
            # 一级标题

            ## 二级标题

            | 字段 | 内容 |
            | --- | --- |
            | 结论 | 通过 |
            """, style);

        Map<String, String> entries = unzip(bytes);
        assertThat(style.headingChineseFont(1)).isEqualTo("黑体");
        assertThat(style.headingLatinFont(1)).isEqualTo("Arial");
        assertThat(style.headingChineseFont(2)).isEqualTo("仿宋_GB2312");
        assertThat(style.tableResolvedFontSize()).isEqualTo(10);
        assertThat(entries.get("word/document.xml"))
            .contains("黑体")
            .contains("Arial")
            .contains("仿宋_GB2312")
            .contains("楷体")
            .contains("Georgia")
            .contains("<w:jc w:val=\"center\"/>")
            .contains("<w:sz w:val=\"20\"/>");
        assertThat(entries.get("word/styles.xml"))
            .contains("黑体")
            .contains("仿宋_GB2312");
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
