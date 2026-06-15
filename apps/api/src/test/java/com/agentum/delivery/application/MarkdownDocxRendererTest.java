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
            """, "交付文档", DocumentDeliveryStyle.defaults());

        Map<String, String> entries = unzip(bytes);

        assertThat(entries).containsKeys(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml",
            "word/styles.xml",
            "word/settings.xml"
        );
        assertThat(entries.get("word/document.xml"))
            .contains("交付文档")
            .contains("风险摘要")
            .contains("正文内容包含")
            .contains("第一项")
            .contains("结论")
            .contains("通过");
        assertThat(entries.get("word/styles.xml"))
            .contains("宋体")
            .contains("Times New Roman");
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
