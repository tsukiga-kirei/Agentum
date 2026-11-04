package com.agentum.delivery.application;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
class ExcelDataRegionWriterTest {
    @Test void writesUnequalSideBySideListsAndPreservesLeadingZeros() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(); var columns = List.of(new ExcelDataRegionWriter.Column("no", "编号", "text", ""));
            ExcelDataRegionWriter.write(sheet, List.of(
                new ExcelDataRegionWriter.Region("A3", false, columns, List.of(Map.of("no", "001"), Map.of("no", "002"))),
                new ExcelDataRegionWriter.Region("H3", false, columns, List.of(Map.of("no", "fund")))));
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("001");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("002");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("fund");
            assertThat(sheet.getRow(3).getCell(7)).isNull();
        }
    }
    @Test void rejectsOverlapsAndFixedContentBeforeWriting() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(); var columns = List.of(new ExcelDataRegionWriter.Column("n", "n", "text", ""));
            var r = new ExcelDataRegionWriter.Region("A1", false, columns, List.of(Map.of("n", "x")));
            assertThatThrownBy(() -> ExcelDataRegionWriter.write(sheet, List.of(r, r))).hasMessageContaining("重叠");
            assertThat((Object) sheet.getRow(0)).isNull();
            sheet.createRow(0).createCell(0).setCellValue("固定标题");
            assertThatThrownBy(() -> ExcelDataRegionWriter.write(sheet, List.of(r), false)).hasMessageContaining("覆盖");
        }
    }
    @Test void rendersSampleScaleWithoutModelTextConversion() throws Exception {
        var rows = java.util.stream.IntStream.range(0, 6879).mapToObj(i -> Map.<String, Object>of("n", i)).toList();
        var region = new ExcelDataRegionWriter.Region("A3", false, List.of(new ExcelDataRegionWriter.Column("n", "余额", "number", "0.00")), rows);
        var renderer = new ExcelWorkbookRenderer();
        var output = renderer.render(List.of(new ExcelWorkbookRenderer.ExcelSheetRenderSpec("明细", "", "A1", "text", Map.of(), List.of(), List.of(), List.of(), List.of(region))));
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(output.bytes()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(6880);
            assertThat(book.getSheetAt(0).getRow(6880).getCell(0).getNumericCellValue()).isEqualTo(6878);
        }
    }
    @Test void emptyLeftRegionDoesNotMoveLongerRightRegion() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(); var columns = List.of(new ExcelDataRegionWriter.Column("v", "值", "text", ""));
            ExcelDataRegionWriter.write(sheet, List.of(new ExcelDataRegionWriter.Region("A3", false, columns, List.of()),
                new ExcelDataRegionWriter.Region("H3", false, columns, List.of(Map.of("v", "1"), Map.of("v", "2"), Map.of("v", "3")))));
            assertThat(sheet.getRow(2).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(4).getCell(7).getStringCellValue()).isEqualTo("3");
        }
    }

}
