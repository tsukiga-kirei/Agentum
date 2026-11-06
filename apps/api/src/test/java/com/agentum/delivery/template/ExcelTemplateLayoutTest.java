package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;
import com.agentum.delivery.application.MarkdownDocxRenderer;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;

class ExcelTemplateLayoutTest {
    private final TemplateFileRenderer renderer = new TemplateFileRenderer(new MarkdownDocxRenderer());
    private byte[] bytes(XSSFWorkbook book) throws IOException { var out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray(); }
    private XSSFCell cell(XSSFSheet sheet, int row, int column) { var r = sheet.getRow(row); if (r == null) r = sheet.createRow(row); return r.createCell(column); }
    private TemplateModels.Field typed(TemplateModels.Field field) {
        return new TemplateModels.Field(field.key(), field.label(), field.key().equals("amount") ? "number" : field.type(), false, field.maxLength(), 100000, field.description(), field.columns().stream().map(this::typed).toList());
    }
    private TemplateModels.Spec spec(byte[] source) { var spec = renderer.inspect(source, "excel"); return new TemplateModels.Spec(spec.fields().stream().map(this::typed).toList(), spec.locations(), spec.defaultStyle(), spec.sheetStyles(), spec.excelRegions()); }
    private XSSFWorkbook render(byte[] source, Map<String, Object> values) throws IOException { return new XSSFWorkbook(new ByteArrayInputStream(renderer.render(source, "excel", spec(source), values, Map.of()))); }

    @Test void stackedListsMoveHeadersTotalsAndCrossSheetReferencesAfterAllDataIsWritten() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var summary = book.createSheet("汇总"); var detail = book.createSheet("明细");
            cell(summary, 0, 0).setCellFormula("SUM('明细'!B2:B2)");
            cell(summary, 1, 0).setCellFormula("SUM('明细'!B5:B5)");
            var name = book.createName(); name.setNameName("data_a_amount"); name.setRefersToFormula("'明细'!$B$2:$B$2");
            cell(summary, 2, 0).setCellFormula("SUM(data_a_amount)");
            cell(summary, 3, 0).setCellFormula("SUMIFS('明细'!B2:B2,'明细'!A2:A2,\"X\")");
            cell(summary, 5, 0).setCellFormula("SUM('明细'!C2:C2)");
            cell(summary, 4, 0).setCellFormula("IF(\"B2:B2\"=\"B2:B2\",SUM('明细'!B2:B2),0)");
            cell(detail, 0, 0).setCellValue("第一份"); cell(detail, 0, 5).setCellValue(2);
            cell(detail, 1, 0).setCellValue("{{a[].kind}}"); cell(detail, 1, 1).setCellValue("{{a[].amount}}"); cell(detail, 1, 2).setCellFormula("B2*$F$1");
            cell(detail, 2, 0).setCellValue("第一份合计"); cell(detail, 2, 1).setCellFormula("SUM(B2:B2)");
            cell(detail, 3, 0).setCellValue("第二份表头");
            cell(detail, 4, 0).setCellValue("{{b[].kind}}"); cell(detail, 4, 1).setCellValue("{{b[].amount}}");
            cell(detail, 5, 0).setCellValue("第二份合计"); cell(detail, 5, 1).setCellFormula("SUM(B5:B5)");
            detail.addMergedRegion(new CellRangeAddress(3, 3, 0, 2));
            book.setPrintArea(1, 0, 2, 0, 5); source = bytes(book);
        }
        try (var book = render(source, Map.of("a", List.of(Map.of("kind", "X", "amount", 10), Map.of("kind", "Y", "amount", 20), Map.of("kind", "X", "amount", 30)), "b", List.of(Map.of("kind", "B", "amount", 7), Map.of("kind", "B", "amount", 9))))) {
            var detail = book.getSheet("明细"); var summary = book.getSheet("汇总");
            assertThat(detail.getRow(4).getCell(0).getStringCellValue()).isEqualTo("第一份合计");
            assertThat(detail.getRow(5).getCell(0).getStringCellValue()).isEqualTo("第二份表头");
            assertThat(detail.getRow(8).getCell(1).getCellFormula()).isEqualTo("SUM(B7:B8)");
            assertThat(detail.getRow(3).getCell(2).getCellFormula()).isEqualTo("B4*$F$1");
            assertThat(detail.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(60);
            assertThat(summary.getRow(0).getCell(0).getCellFormula()).isEqualTo("SUM('明细'!B2:B4)");
            assertThat(summary.getRow(0).getCell(0).getNumericCellValue()).isEqualTo(60);
            assertThat(summary.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(16);
            assertThat(summary.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(60);
            assertThat(summary.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(40);
            assertThat(summary.getRow(4).getCell(0).getCellFormula()).contains("\"B2:B2\"", "'明细'!B2:B4");
            assertThat(summary.getRow(5).getCell(0).getNumericCellValue()).isEqualTo(120);
            assertThat(book.getName("data_b_amount").getRefersToFormula()).contains("$B$7:$B$8");
            assertThat(book.getPrintArea(1)).contains("$C$9");
            assertThat(detail.getMergedRegions()).anySatisfy(range -> assertThat(range.formatAsString()).isEqualTo("A6:C6"));
        }
    }
    @Test void unequalParallelListsShareSpaceWithoutIncludingEachOthersOrTheirOwnTotals() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("并列");
            cell(sheet, 1, 1).setCellValue("{{left[].amount}}"); cell(sheet, 1, 7).setCellValue("{{right[].amount}}");
            cell(sheet, 2, 1).setCellFormula("SUM(B2:B2)"); cell(sheet, 2, 7).setCellFormula("SUM(H2:H2)"); source = bytes(book);
        }
        try (var book = render(source, Map.of("left", List.of(Map.of("amount", 1), Map.of("amount", 2)), "right", List.of(Map.of("amount", 10), Map.of("amount", 20), Map.of("amount", 30))))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(1).getCellFormula()).isEqualTo("SUM(B2:B3)");
            assertThat(sheet.getRow(4).getCell(7).getCellFormula()).isEqualTo("SUM(H2:H4)");
            assertThat(sheet.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(3);
            assertThat(sheet.getRow(4).getCell(7).getNumericCellValue()).isEqualTo(60);
        }
    }
    @Test void horizontalExpansionMovesFollowingColumnsAndCopiesRelativeAndAbsoluteReferences() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("横向");
            cell(sheet, 0, 1).setCellValue("{{items[].kind}}"); cell(sheet, 1, 1).setCellValue("{{items[].amount}}");
            cell(sheet, 2, 1).setCellFormula("B2*$A$6"); cell(sheet, 5, 0).setCellValue(3);
            cell(sheet, 1, 2).setCellFormula("SUM(B2:B2)"); sheet.setColumnWidth(2, 4500); source = bytes(book);
        }
        try (var book = render(source, Map.of("items", List.of(Map.of("kind", "A", "amount", 2), Map.of("kind", "B", "amount", 4), Map.of("kind", "C", "amount", 6))))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(3).getCellFormula()).isEqualTo("D2*$A$6");
            assertThat(sheet.getRow(2).getCell(3).getNumericCellValue()).isEqualTo(18);
            assertThat(sheet.getRow(1).getCell(4).getCellFormula()).isEqualTo("SUM(B2:D2)");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(12);
            assertThat(sheet.getColumnWidth(4)).isEqualTo(4500);
        }
    }
    @Test void fullColumnAndRangesSkippingHeaderRowsRemainValid() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var summary = book.createSheet("汇总"); var sheet = book.createSheet("信贷业务");
            cell(sheet, 0, 3).setCellValue(999); cell(sheet, 2, 3).setCellValue("{{credit[].amount}}");
            cell(summary, 0, 0).setCellFormula("SUM('信贷业务'!D3:D1048576)");
            cell(summary, 1, 0).setCellFormula("SUM('信贷业务'!D:D)"); source = bytes(book);
        }
        try (var book = render(source, Map.of("credit", List.of(Map.of("amount", 10), Map.of("amount", 20))))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue()).isEqualTo(30);
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1029);
        }
    }
    @Test void nativeTablesResizeCalculatedColumnsAndUnequalTotalsStayAtTheirOwnEnds() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var summary = book.createSheet("汇总"); var sheet = book.createSheet("投资业务");
            cell(sheet, 0, 0).setCellValue("kind"); cell(sheet, 0, 1).setCellValue("amount"); cell(sheet, 0, 2).setCellValue("converted");
            cell(sheet, 1, 0).setCellValue("{{bonds[].kind}}"); cell(sheet, 1, 1).setCellValue("{{bonds[].amount}}"); cell(sheet, 1, 2).setCellFormula("B2*2");
            cell(sheet, 2, 0).setCellValue("债券合计"); 
            var bonds = sheet.createTable(new AreaReference("A1:C3", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007)); bonds.setName("tbl_bonds"); bonds.setDisplayName("tbl_bonds"); bonds.getCTTable().setTotalsRowCount(1); cell(sheet, 2, 1).setCellFormula("SUBTOTAL(109,tbl_bonds[amount])");
            cell(sheet, 0, 7).setCellValue("kind"); cell(sheet, 0, 8).setCellValue("amount");
            cell(sheet, 1, 7).setCellValue("{{funds[].kind}}"); cell(sheet, 1, 8).setCellValue("{{funds[].amount}}");
            cell(sheet, 2, 7).setCellValue("基金合计"); 
            var funds = sheet.createTable(new AreaReference("H1:I3", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007)); funds.setName("tbl_funds"); funds.setDisplayName("tbl_funds"); funds.getCTTable().setTotalsRowCount(1); cell(sheet, 2, 8).setCellFormula("SUM(tbl_funds[amount])");
            cell(summary, 0, 0).setCellFormula("SUM(tbl_bonds[amount])"); cell(summary, 1, 0).setCellFormula("SUM(tbl_funds[amount])");
            cell(summary, 2, 0).setCellFormula("SUM(tbl_bonds[converted])"); cell(summary, 3, 0).setCellFormula("'投资业务'!B3");
            source = bytes(book);
        }
        try (var book = render(source, Map.of("bonds", List.of(Map.of("kind", "债券", "amount", 10), Map.of("kind", "债券", "amount", 20)), "funds", List.of(Map.of("kind", "基金", "amount", 1), Map.of("kind", "基金", "amount", 2), Map.of("kind", "基金", "amount", 3))))) {
            var sheet = book.getSheet("投资业务");
            assertThat(sheet.getTables().get(0).getArea().formatAsString()).isEqualTo("A1:C4");
            assertThat(sheet.getTables().get(1).getArea().formatAsString()).isEqualTo("H1:I5");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("债券合计");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(30);
            assertThat(sheet.getRow(4).getCell(8).getNumericCellValue()).isEqualTo(6);
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getCellFormula()).isEqualTo("SUM(tbl_bonds[amount])");
            assertThat(book.getSheetAt(0).getRow(2).getCell(0).getNumericCellValue()).isEqualTo(60);
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getCellFormula()).isEqualTo("'投资业务'!B4");
        }
    }
    @Test void reservedTableRowsAndCustomRangeNamesUseActualArrayLengthIncludingNulls() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("信贷"); var summary = book.createSheet("汇总");
            cell(sheet, 0, 0).setCellValue("amount"); cell(sheet, 1, 0).setCellValue("{{credit[].amount}}"); cell(sheet, 5, 0).setCellValue("合计");
            var table = sheet.createTable(new AreaReference("A1:A6", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007)); table.setName("tbl_credit"); table.setDisplayName("tbl_credit"); table.getCTTable().setTotalsRowCount(1);
            var name = book.createName(); name.setNameName("credit_amounts"); name.setRefersToFormula("'信贷'!$A$2:$A$2");
            cell(summary, 0, 0).setCellFormula("SUM(credit_amounts)"); source = bytes(book);
        }
        var spec = spec(source); var region = spec.excelRegions().getFirst();
        spec = new TemplateModels.Spec(spec.fields(), spec.locations(), Map.of(), Map.of(), List.of(new TemplateModels.ExcelRegion(region.sheet(), region.field(), region.startCell(), region.direction(), region.column(), "shift", "credit_amounts")));
        Map<String, Object> empty = new LinkedHashMap<>(); empty.put("amount", null);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(source, "excel", spec, Map.of("credit", List.of(Map.of("amount", 10), empty, Map.of("amount", 30))), Map.of())))) {
            assertThat(book.getName("credit_amounts").getRefersToFormula()).contains("$A$2:$A$4");
            assertThat(book.getSheetAt(0).getRow(2).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(book.getSheetAt(1).getRow(0).getCell(0).getNumericCellValue()).isEqualTo(40);
            assertThat(book.getSheetAt(0).getTables().getFirst().getArea().formatAsString()).isEqualTo("A1:A5");
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("合计");
        }
    }

    @Test void emptyOptionalListsKeepValidBlankRangesAndCalculatedTableColumnsUseActualRows() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("明细"); var summary = book.createSheet("汇总");
            cell(sheet, 0, 0).setCellValue("amount"); cell(sheet, 0, 1).setCellValue("derived"); cell(sheet, 1, 0).setCellValue("{{items[].amount}}");
            var table = sheet.createTable(new AreaReference("A1:B2", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007)); table.setName("tbl_items"); table.setDisplayName("tbl_items");
            table.getCTTable().getTableColumns().getTableColumnArray(1).addNewCalculatedColumnFormula().setStringValue("[@amount]*2");
            cell(summary, 0, 0).setCellFormula("SUM(tbl_items[amount])"); cell(summary, 1, 0).setCellFormula("SUM(tbl_items[derived])"); source = bytes(book);
        }
        try (var book = render(source, Map.of("items", List.of(Map.of("amount", 2), Map.of("amount", 5))))) {
            assertThat(book.getSheetAt(0).getRow(2).getCell(1).getCellFormula()).isEqualTo("[@amount]*2");
            assertThat(book.getSheetAt(1).getRow(1).getCell(0).getNumericCellValue()).isEqualTo(14);
            var preview = TemplateWorkbookPreview.read(bytes(book));
            assertThat(preview.getFirst().rows().get(2).cells().get(1).text()).isEqualTo("10");
            assertThat(preview.get(1).rows().get(1).cells().getFirst().text()).isEqualTo("14");
        }
        try (var book = render(source, Map.of("items", List.of()))) {
            assertThat(book.getName("data_items_amount").getRefersToFormula()).endsWith("$A$2:$A$2");
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(book.getSheetAt(1).getRow(0).getCell(0).getNumericCellValue()).isZero();
        }
    }
    @Test void fixedGrowthRejectsCollisionWithoutOverwritingContent() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("固定"); cell(sheet, 1, 0).setCellValue("{{items[].amount}}"); cell(sheet, 2, 0).setCellValue("说明"); source = bytes(book);
        }
        var original = spec(source); var region = original.excelRegions().getFirst();
        var fixed = new TemplateModels.Spec(original.fields(), original.locations(), Map.of(), Map.of(), List.of(new TemplateModels.ExcelRegion(region.sheet(), region.field(), region.startCell(), region.direction(), region.column(), "fixed", null)));
        assertThatThrownBy(() -> renderer.render(source, "excel", fixed, Map.of("items", List.of(Map.of("amount", 1), Map.of("amount", 2))), Map.of())).hasMessageContaining("覆盖已有内容");
    }

    @Test void blankTotalRowsStillReserveSpaceAndBlankGridStylesRepeat() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("明细"); cell(sheet, 0, 0).setCellValue("kind"); cell(sheet, 0, 1).setCellValue("spacer"); cell(sheet, 0, 2).setCellValue("amount");
            cell(sheet, 1, 0).setCellValue("{{items[].kind}}"); cell(sheet, 1, 2).setCellValue("{{items[].amount}}");
            var grid = book.createCellStyle(); grid.setBorderBottom(BorderStyle.THIN); grid.setFillForegroundColor(IndexedColors.YELLOW.getIndex()); grid.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cell(sheet, 1, 1).setCellStyle(grid);
            var table = sheet.createTable(new AreaReference("A1:C3", org.apache.poi.ss.SpreadsheetVersion.EXCEL2007)); table.setName("tbl_rows"); table.setDisplayName("tbl_rows"); table.getCTTable().setTotalsRowCount(1);
            cell(sheet, 3, 0).setCellValue("下一块标题"); source = bytes(book);
        }
        try (var book = render(source, Map.of("items", List.of(Map.of("kind", "A", "amount", 1), Map.of("kind", "B", "amount", 2), Map.of("kind", "C", "amount", 3))))) {
            assertThat(book.getSheetAt(0).getRow(5).getCell(0).getStringCellValue()).isEqualTo("下一块标题");
            assertThat(book.getSheetAt(0).getRow(3).getCell(1).getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
            assertThat(book.getSheetAt(0).getRow(3).getCell(1).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.YELLOW.getIndex());
            assertThat(book.getSheetAt(0).getTables().getFirst().getArea().formatAsString()).isEqualTo("A1:C5");
        }
    }

    @Test void formulaErrorsAreNotSilentlySavedWithOldCaches() throws Exception {
        for (String formula : List.of("1/0", "#REF!", "A2+1", "UNKNOWN_FUNCTION(1)")) {
            byte[] source;
            try (var book = new XSSFWorkbook()) {
                var sheet = book.createSheet("错误"); cell(sheet, 0, 0).setCellValue("{{name}}"); cell(sheet, 1, 0).setCellFormula(formula); source = bytes(book);
            }
            var spec = renderer.inspect(source, "excel");
            assertThatThrownBy(() -> renderer.render(source, "excel", spec, Map.of("name", "测试"), Map.of())).hasMessageContaining("错误").hasMessageContaining("A2");
        }
    }
}
