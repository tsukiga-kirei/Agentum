package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;

import com.agentum.delivery.application.MarkdownDocxRenderer;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TemplateFormatGroupsTest {
    private final TemplateFileRenderer renderer = new TemplateFileRenderer(new MarkdownDocxRenderer());
    private TemplateModels.Field field(String key, String type) {
        return new TemplateModels.Field(key, key, type, false, 20000, 100000, "", List.of());
    }
    private TemplateModels.FormatGroup group(String id, String preset, List<String> fields, String format) {
        return new TemplateModels.FormatGroup(id, id, preset, fields, Map.of("numberFormat", format));
    }
    private TemplateModels.Spec spec(List<TemplateModels.FormatGroup> groups) {
        var table = new TemplateModels.Field("items", "业务", "table", true, 20000, 100000, "",
            List.of(field("code", "text"), field("amount", "number"), field("rate", "number")));
        return new TemplateModels.Spec(List.of(table), List.of(), Map.of(), Map.of(), List.of(), groups);
    }

    @Test void rejectsDuplicateUnknownContainerAndIncompatibleFieldsWithoutSilentlyOverriding() {
        TemplateFormatGroups.validate(spec(List.of(group("amount", "amount", List.of("items.amount"), "#,##0.00"))));
        assertThatThrownBy(() -> TemplateFormatGroups.validate(spec(List.of(group("amount", "amount", List.of("items.amount"), "0.00"), group("rate", "rate", List.of("items.amount"), "0.000000")))))
            .hasMessageContaining("重复归属");
        for (String path : List.of("items", "items.unknown", "unknown.amount"))
            assertThatThrownBy(() -> TemplateFormatGroups.validate(spec(List.of(group("unknown", "amount", List.of(path), "0.00")))))
                .hasMessageContaining("字段不存在或是明细容器");
        assertThatThrownBy(() -> TemplateFormatGroups.validate(spec(List.of(group("text", "amount", List.of("items.code"), "0.00")))))
            .hasMessageContaining("字段类型不一致");
        assertThatThrownBy(() -> TemplateFormatGroups.validate(spec(List.of(group("bad", "unknown", List.of(), "0")))))
            .hasMessageContaining("预设无效");
        TemplateFormatGroups.validate(spec(List.of(group("empty", "amount", List.of(), "0.00"))));
    }

    @Test void repeatsFieldStyleAcrossSheetsPreservesBlankRowsAndRecalculatesSummaryAfterGrowth() throws Exception {
        byte[] original;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("明细");
            sheet.createRow(0).createCell(0).setCellValue("编号"); sheet.getRow(0).createCell(1).setCellValue("金额"); sheet.getRow(0).createCell(2).setCellValue("汇率");
            sheet.createRow(1).createCell(0).setCellValue("{{items[].code}}"); sheet.getRow(1).createCell(1).setCellValue("{{items[].amount}}"); sheet.getRow(1).createCell(2).setCellValue("{{items[].rate}}");
            var repeat = book.createSheet("重复"); repeat.createRow(0).createCell(0).setCellValue("同一字段"); repeat.createRow(1).createCell(0).setCellValue("{{items[].amount}}");
            book.createSheet("汇总").createRow(0).createCell(0).setCellFormula("SUM('明细'!B2:B2)");
            book.write(out); original = out.toByteArray();
        }
        var base = renderer.inspect(original, "excel");
        var locations = base.locations().stream().map(location -> location.sheet().equals("重复")
            ? new TemplateModels.Location(location.id(), location.field(), location.label(), location.sheet(), Map.<String, Object>of("numberFormat", "0.0000")) : location).toList();
        var definition = new TemplateModels.Spec(spec(List.of()).fields(), locations, Map.of("numberFormat", "0"), Map.of("明细", Map.of("numberFormat", "0.0")), base.excelRegions(),
            List.of(group("amount", "amount", List.of("items.amount"), "#,##0.00"), group("rate", "rate", List.of("items.rate"), "0.000000"), group("text", "text", List.of("items.code"), "@")));
        Map<String, Object> blank = new LinkedHashMap<>(); blank.put("code", "0002"); blank.put("amount", null); blank.put("rate", null);
        var data = Map.<String, Object>of("items", List.of(Map.of("code", "0001", "amount", new BigDecimal("10.12505"), "rate", new BigDecimal("6.7138")), blank, Map.of("code", "0003", "amount", new BigDecimal("20.5"), "rate", BigDecimal.ONE)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(original, "excel", definition, data, Map.of())))) {
            var detail = book.getSheet("明细");
            assertThat(detail.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(detail.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(detail.getRow(1).getCell(1).getNumericCellValue()).isCloseTo(10.12505, within(0.0000001));
            assertThat(detail.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(detail.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(20.5);
            assertThat(detail.getRow(1).getCell(0).getStringCellValue()).isEqualTo("0001");
            assertThat(detail.getRow(1).getCell(2).getCellStyle().getDataFormatString()).isEqualTo("0.000000");
            assertThat(book.getSheet("重复").getRow(3).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("0.0000");
            assertThat(book.getSheet("汇总").getRow(0).getCell(0).getNumericCellValue()).isCloseTo(30.62505, within(0.0000001));
        }
        String position = locations.stream().filter(location -> location.sheet().equals("重复")).findFirst().orElseThrow().id();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(original, "excel", definition, data, Map.of(position, Map.of("numberFormat", "0.0")))))) {
            assertThat(book.getSheet("重复").getRow(3).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("0.0");
        }
    }
}
