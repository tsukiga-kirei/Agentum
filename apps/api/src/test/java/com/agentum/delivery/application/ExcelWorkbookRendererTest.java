package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.delivery.application.ExcelWorkbookRenderer.ExcelSheetRenderSpec;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class ExcelWorkbookRendererTest {

    private final ExcelWorkbookRenderer renderer = new ExcelWorkbookRenderer();

    @Test
    void shouldRenderMarkdownTableAndApplyColumnTypeWithTextFallback() throws Exception {
        ExcelWorkbookRenderer.ExcelWorkbookRenderResult result = renderer.render(List.of(new ExcelSheetRenderSpec(
            "风险明细",
            """
                | 风险类型 | 等级 | 金额 |
                | --- | --- | --- |
                | 司法风险 | 高 | 1200.50 |
                | 经营风险 | 中 | 暂未披露 |
                """,
            "A1",
            "text",
            Map.of("headerBold", true, "autoFilter", true),
            List.of(Map.of("match", "金额", "type", "number", "format", "#,##0.00")),
            List.of(),
            List.of()
        )));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))) {
            var sheet = workbook.getSheet("风险明细");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("风险类型");
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1200.50);
            assertThat(sheet.getRow(2).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("暂未披露");
        }
    }

    @Test
    void shouldTurnModelBulletKeyValueListIntoTable() throws Exception {
        ExcelWorkbookRenderer.ExcelWorkbookRenderResult result = renderer.render(List.of(new ExcelSheetRenderSpec(
            "模型输出",
            """
                1. 风险类型：司法风险，等级：高，说明：存在被执行记录
                2. 风险类型：经营风险，等级：中，说明：近一年变更频繁
                """,
            "A1",
            "text",
            Map.of(),
            List.of(),
            List.of(),
            List.of()
        )));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))) {
            var sheet = workbook.getSheet("模型输出");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("风险类型");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("司法风险");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("近一年变更频繁");
        }
    }
}
