package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;
import com.agentum.delivery.application.MarkdownDocxRenderer;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateFileRendererTest {
    private final TemplateFileRenderer renderer = new TemplateFileRenderer(new MarkdownDocxRenderer());

    @Test void automaticallyRecognizesSideBySideListsWithoutManualRegionConfiguration() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("投资业务");
            sheet.createRow(0).createCell(0).setCellValue("债券"); sheet.getRow(0).createCell(7).setCellValue("基金");
            sheet.createRow(1).createCell(0).setCellValue("{{bonds[].name}}"); sheet.getRow(1).createCell(7).setCellValue("{{funds[].name}}");
            book.write(out); source = out.toByteArray();
        }
        var detected = renderer.inspect(source, "excel");
        assertThat(detected.excelRegions()).extracting(TemplateModels.ExcelRegion::startCell).containsExactly("A2", "H2");
        // 已上传但尚未配置区域的草稿同样能直接生成，不能继续要求用户经历发布失败再手动修复。
        var unconfigured = new TemplateModels.Spec(detected.fields(), detected.locations(), Map.of(), Map.of());
        var duplicate = new TemplateModels.Spec(detected.fields(), detected.locations(), Map.of(), Map.of(), List.of(detected.excelRegions().getFirst(), detected.excelRegions().getFirst()));
        assertThatThrownBy(() -> renderer.resolveExcelRegions(duplicate)).hasMessageContaining("重复配置");
        byte[] bytes = renderer.render(source, "excel", unconfigured, Map.of("bonds", List.of(Map.of("name", "债券1")), "funds", List.of(Map.of("name", "基金1"), Map.of("name", "基金2"))), Map.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getRow(2).getCell(7).getStringCellValue()).isEqualTo("基金2");
            assertThat(book.getSheetAt(0).getRow(2).getCell(0)).isNull();
        }
    }

    @Test void recognizesSampleColumnAndExpandsRecordsHorizontally() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("横向");
            sheet.createRow(0).createCell(1).setCellValue("{{items[].name}}");
            sheet.createRow(1).createCell(1).setCellValue("{{items[].amount}}");
            book.write(out); source = out.toByteArray();
        }
        var spec = renderer.inspect(source, "excel");
        assertThat(spec.excelRegions()).containsExactly(new TemplateModels.ExcelRegion("横向", "items", "B1", "horizontal", "name"), new TemplateModels.ExcelRegion("横向", "items", "B2", "horizontal", "amount"));
        var data = Map.<String, Object>of("items", List.of(Map.of("name", "甲", "amount", "10"), Map.of("name", "乙", "amount", "20")));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(source, "excel", spec, data, Map.of())))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(2).getStringCellValue()).isEqualTo("乙");
            assertThat(book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("20");
        }
        var wrongDirection = new TemplateModels.Spec(spec.fields(), spec.locations(), Map.of(), Map.of(), List.of(new TemplateModels.ExcelRegion("横向", "items", "B1", "vertical", "name", "fixed", null), new TemplateModels.ExcelRegion("横向", "items", "B2", "vertical", "amount", "fixed", null)));
        assertThatThrownBy(() -> renderer.render(source, "excel", wrongDirection, data, Map.of())).hasMessageContaining("覆盖已有内容");
    }

    @ParameterizedTest
    @ValueSource(strings = {"word", "excel"})
    void rejectsFilesWithoutMarkersAndAcceptsTheSuggestedMarkerAfterCorrection(String kind) throws Exception {
        // 普通文件不能作为可填模板；用户按提示补充标记后，应能重新上传并识别字段。
        byte[] source = templateWithText(kind, "公司名称：");
        assertThatThrownBy(() -> renderer.inspect(source, kind))
            .isInstanceOf(com.agentum.shared.api.ApiException.class)
            .hasMessageContaining("{{company_name}}")
            .hasMessageContaining("重新上传");

        var corrected = renderer.inspect(templateWithText(kind, "公司名称：{{company_name}}"), kind);
        assertThat(corrected.fields()).extracting(TemplateModels.Field::key).containsExactly("company_name");
    }

    private byte[] templateWithText(String kind, String text) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
            if ("word".equals(kind)) {
                try (var document = new XWPFDocument()) {
                    document.createParagraph().createRun().setText(text);
                    document.write(output);
                }
            } else {
                try (var workbook = new XSSFWorkbook()) {
                    workbook.createSheet("概览").createRow(0).createCell(0).setCellValue(text);
                    workbook.write(output);
                }
            }
            return output.toByteArray();
        }
    }

    @Test void fillsSplitRunsPreservesFixedTextAndAppliesLocalStyles() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var p = doc.createParagraph(); p.setIndentationFirstLine(480);
            p.createRun().setText("固定文字 "); p.createRun().setText("{{risk_"); p.createRun().setText("summary}}");
            var suffix = p.createRun(); suffix.setBold(true); suffix.setText(" 固定结尾"); doc.write(output); source = output.toByteArray();
        }
        var spec = renderer.inspect(source, "word");
        var style = Map.<String, Object>of("color", "FF0000", "fontSize", 14);
        byte[] result = renderer.render(source, "word", spec, Map.of("risk_summary", "风险结论"), Map.of(spec.locations().getFirst().id(), style));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            var p = doc.getParagraphs().getFirst(); assertThat(p.getText()).isEqualTo("固定文字 风险结论 固定结尾");
            assertThat(p.getIndentationFirstLine()).isEqualTo(480);
            assertThat(p.getRuns().getFirst().getColor()).isNull();
            assertThat(p.getRuns().stream().filter(run -> run.text().equals("风险结论")).findFirst().orElseThrow().getColor()).isEqualTo("FF0000");
            assertThat(p.getRuns().getLast().isBold()).isTrue();
        }
    }
    @Test void repeatsParagraphsWithIndentationAndKeepsSoftBreaksInsideParagraph() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var p = doc.createParagraph(); p.setIndentationFirstLine(480); p.createRun().setText("{{risk_summary}}"); doc.write(out); source = out.toByteArray();
        }
        var original = renderer.inspect(source, "word");
        var field = new TemplateModels.Field("risk_summary", "风险", "paragraphs", true, 2000, 10, "", List.of());
        var spec = new TemplateModels.Spec(List.of(field), original.locations(), Map.of(), Map.of());
        byte[] result = renderer.render(source, "word", spec, Map.of("risk_summary", List.of("第一段\n段内换行", "第二段")), Map.of());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getParagraphs()).hasSize(2);
            assertThat(doc.getParagraphs()).allSatisfy(p -> { assertThat(p.getIndentationFirstLine()).isEqualTo(480); assertThat(p.getText()).doesNotContain("{{"); });
            assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("第一段\n段内换行");
            assertThat(doc.getParagraphs().getLast().getText()).isEqualTo("第二段");
        }
    }
    @Test void repeatsWordRowsAndKeepsCellShading() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var table = doc.createTable(2, 1); table.getRow(0).getCell(0).setText("风险类型");
            var cell = table.getRow(1).getCell(0); cell.setText("{{risks[].name}}"); cell.getCTTc().addNewTcPr().addNewShd().setFill("FFF000");
            doc.write(out); source = out.toByteArray();
        }
        var spec = renderer.inspect(source, "word");
        byte[] result = renderer.render(source, "word", spec, Map.of("risks", List.of(Map.of("name", "风险 A"), Map.of("name", "风险 B"))), Map.of());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getTables().getFirst().getRows()).hasSize(3);
            assertThat(doc.getTables().getFirst().getRow(2).getCell(0).getText()).isEqualTo("风险 B");
            assertThat(doc.getTables().getFirst().getRow(2).getCell(0).getCTTc().xmlText()).contains("FFF000");
        }
    }
    @Test void keepsRecordIndexesAcrossSheetsDirectionsRepeatedFieldsAndMissingCells() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var a = book.createSheet("纵向"); a.createRow(1).createCell(0).setCellValue("{{items[].name}}"); a.getRow(1).createCell(3).setCellValue("{{items[].name}}");
            a.getRow(1).setHeightInPoints(80);
            var b = book.createSheet("横向"); b.createRow(4).createCell(2).setCellValue("{{items[].amount}}");
            var style = book.createCellStyle(); style.setWrapText(true); a.getRow(1).getCell(0).setCellStyle(style);
            book.write(out); source = out.toByteArray();
        }
        var base = renderer.inspect(source, "excel");
        assertThat(base.excelRegions()).hasSize(3);
        var field = new TemplateModels.Field("items", "明细", "table", true, null, 20, "", List.of(
            new TemplateModels.Field("name", "名称", "text", false, 100, null, "", List.of()),
            new TemplateModels.Field("amount", "金额", "number", false, null, null, "", List.of())));
        var regions = base.excelRegions().stream().map(it -> new TemplateModels.ExcelRegion(it.sheet(), it.field(), it.startCell(), it.column().equals("amount") ? "horizontal" : "vertical", it.column())).toList();
        var definition = new TemplateModels.Spec(List.of(field), base.locations(), Map.of(), Map.of(), regions);
        Map<String, Object> first = new LinkedHashMap<>(); first.put("name", "甲"); first.put("amount", null);
        var values = Map.<String, Object>of("items", List.of(first, Map.of("amount", 20), Map.of("name", "丙", "amount", 30)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(source, "excel", definition, values, Map.of())))) {
            var a = book.getSheet("纵向"); var b = book.getSheet("横向");
            assertThat(a.getRow(1).getCell(0).getStringCellValue()).isEqualTo("甲");
            assertThat(a.getRow(2).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(a.getRow(3).getCell(0).getStringCellValue()).isEqualTo("丙");
            assertThat(a.getRow(3).getCell(3).getStringCellValue()).isEqualTo("丙");
            assertThat(b.getRow(4).getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(b.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(20);
            assertThat(b.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(30);
            assertThat(a.getRow(1).getHeightInPoints()).isLessThan(40);
        }
        var fixed = new TemplateModels.Spec(List.of(field), base.locations(), Map.of("rowHeightMode", "fixed", "rowHeight", 28), Map.of(), regions);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(source, "excel", fixed, values, Map.of())))) {
            assertThat(book.getSheet("纵向").getRow(3).getHeightInPoints()).isEqualTo(28);
        }
    }

    @Test void previewTrimsFormattedBlankExtentAndKeepsBorderlessCellsAndFormulaValues() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("预览"); sheet.createRow(0).createCell(0).setCellValue(10); sheet.getRow(0).createCell(1).setCellFormula("A1*2");
            sheet.createRow(800).createCell(30).setCellStyle(book.createCellStyle());
            ExcelFormulaCalculation.calculate(book);
            book.write(out); source = out.toByteArray();
        }
        var layout = TemplateWorkbookPreview.read(source).getFirst();
        assertThat(layout.rows()).hasSize(1); assertThat(layout.widths()).hasSize(2); assertThat(layout.truncated()).isFalse();
        assertThat(layout.rows().getFirst().cells().getFirst().style()).containsEntry("borderTop", "none");
        assertThat(layout.rows().getFirst().cells().get(1).text()).isEqualTo("20");
    }

    @Test void fillsTypedExcelExpandsRowsAndUsesDifferentSheetStyles() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var a = book.createSheet("概览"); a.createRow(0).createCell(0).setCellValue("{{amount}}");
            var b = book.createSheet("明细"); b.createRow(0).createCell(0).setCellValue("{{risks[].name}}"); b.createRow(1).createCell(0).setCellValue("原合计区域");
            book.write(out); source = out.toByteArray();
        }
        var original = renderer.inspect(source, "excel");
        var amount = new TemplateModels.Field("amount", "金额", "number", true, null, null, "", List.of());
        var risks = original.fields().stream().filter(f -> f.key().equals("risks")).findFirst().orElseThrow();
        var spec = new TemplateModels.Spec(List.of(amount, risks), original.locations(), Map.of(), Map.of("概览", Map.of("backgroundColor", "FF0000", "numberFormat", "#,##0.00"), "明细", Map.of("backgroundColor", "00FF00")));
        byte[] result = renderer.render(source, "excel", spec, Map.of("amount", 1234.5, "risks", List.of(Map.of("name", "A"), Map.of("name", "B"))), Map.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            var a = book.getSheet("概览").getRow(0).getCell(0);
            assertThat(a.getCellType()).isEqualTo(CellType.NUMERIC); assertThat(a.getNumericCellValue()).isEqualTo(1234.5);
            assertThat(a.getCellStyle().getFillForegroundXSSFColor().getARGBHex()).endsWith("FF0000");
            assertThat(book.getSheet("明细").getRow(1).getCell(0).getStringCellValue()).isEqualTo("B");
            assertThat(book.getSheet("明细").getRow(2).getCell(0).getStringCellValue()).isEqualTo("原合计区域");
            assertThat(book.getSheet("明细").getRow(1).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).endsWith("00FF00");
        }
    }
    @Test void formulaLookingTextStaysTextAndTemplateBytesStayUntouched() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) { book.createSheet().createRow(0).createCell(0).setCellValue("{{name}}"); book.write(out); source = out.toByteArray(); }
        byte[] before = source.clone();
        byte[] result = renderer.render(source, "excel", renderer.inspect(source, "excel"), Map.of("name", "=HYPERLINK(\"bad\")"), Map.of());
        assertThat(source).isEqualTo(before);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(result))) { assertThat(book.getSheetAt(0).getRow(0).getCell(0).getCellType()).isEqualTo(CellType.STRING); }
    }
    @Test void markdownRendersEmphasisAndTableWithConfiguredFont() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) { doc.createParagraph().createRun().setText("{{body}}"); doc.write(out); source = out.toByteArray(); }
        var base = renderer.inspect(source, "word");
        var spec = new TemplateModels.Spec(List.of(new TemplateModels.Field("body", "正文", "markdown", true, 2000, null, "", List.of())), base.locations(), Map.of("fontSize", 14), Map.of());
        byte[] result = renderer.render(source, "word", spec, Map.of("body", "**重要**内容\n\n| 类型 | 等级 |\n| --- | --- |\n| 经营 | 中 |"), Map.of());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getParagraphs().stream().flatMap(p -> p.getRuns().stream()).anyMatch(r -> r.isBold() && r.text().contains("重要"))).isTrue();
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getParagraphs().stream().flatMap(p -> p.getRuns().stream())).allSatisfy(run -> assertThat(run.getFontSizeAsDouble()).isEqualTo(14d));
        }
    }
    @Test void expandsSimpleAggregateAndPrintAreaAlongWithTheDetailRows() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("明细"); sheet.createRow(0).createCell(0).setCellValue("{{items[].amount}}"); sheet.createRow(1).createCell(0).setCellFormula("SUM(A1:A1)"); book.setPrintArea(0, 0, 0, 0, 1); book.write(out); source = out.toByteArray();
        }
        var base = renderer.inspect(source, "excel");
        var column = new TemplateModels.Field("amount", "金额", "number", true, null, null, "", List.of());
        var field = new TemplateModels.Field("items", "明细", "table", true, null, 20, "", List.of(column));
        var spec = new TemplateModels.Spec(List.of(field), base.locations(), Map.of(), Map.of());
        var result = renderer.render(source, "excel", spec, Map.of("items", List.of(Map.of("amount", 10), Map.of("amount", 20))), Map.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertThat(book.getSheetAt(0).getRow(2).getCell(0).getCellFormula()).isEqualTo("SUM(A1:A2)");
            assertThat(book.getCreationHelper().createFormulaEvaluator().evaluate(book.getSheetAt(0).getRow(2).getCell(0)).getNumberValue()).isEqualTo(30);
        }
    }
    @Test void optionalEmptyMarkdownAndDetailListStayBlankWithoutInventedContent() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("{{body}}"); doc.createTable(1, 1).getRow(0).getCell(0).setText("{{items[].name}}"); doc.write(out); source = out.toByteArray();
        }
        var base = renderer.inspect(source, "word");
        var body = new TemplateModels.Field("body", "说明", "markdown", false, 100, null, "", List.of());
        var items = new TemplateModels.Field("items", "明细", "table", false, null, 10, "", List.of(new TemplateModels.Field("name", "名称", "text", true, 100, null, "", List.of())));
        var spec = new TemplateModels.Spec(List.of(body, items), base.locations(), Map.of(), Map.of());
        var result = renderer.render(source, "word", spec, Map.of("body", "", "items", List.of()), Map.of());
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getParagraphs().getFirst().getText()).isEmpty();
            assertThat(doc.getTables().getFirst().getRows()).hasSize(1);
            assertThat(doc.getTables().getFirst().getRow(0).getCell(0).getText()).isEmpty();
        }
    }
    @Test void fillsTwoExplicitRegionsOnSameRowWithoutShiftingOtherList() throws Exception {
        byte[] source;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("投资"); var row = sheet.createRow(2);
            row.createCell(0).setCellValue("{{bonds[].name}}"); row.createCell(7).setCellValue("{{funds[].name}}");
            book.write(out); source = out.toByteArray();
        }
        var original = renderer.inspect(source, "excel");
        var spec = new TemplateModels.Spec(original.fields(), original.locations(), Map.of(), Map.of(), List.of(
            new TemplateModels.ExcelRegion("投资", "bonds", "A3"), new TemplateModels.ExcelRegion("投资", "funds", "H3")));
        byte[] result = renderer.render(source, "excel", spec, Map.of("bonds", List.of(Map.of("name", "债券1"), Map.of("name", "债券2")), "funds", List.of(Map.of("name", "基金"))), Map.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("债券2");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("基金");
            assertThat(sheet.getRow(3).getCell(7)).isNull();
        }
    }

}
