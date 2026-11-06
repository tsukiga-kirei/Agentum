package com.agentum.delivery.template;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/** 模板内容契约与位置样式分开保存，同一数据可以在不同位置采用不同排版。 */
public final class TemplateModels {
    private TemplateModels() {}

    public record Field(String key, String label, String type, boolean required, Integer maxLength,
                        Integer maxItems, String description, List<Field> columns, List<String> enumValues) {
        public Field(String key, String label, String type, boolean required, Integer maxLength, Integer maxItems, String description, List<Field> columns) {
            this(key, label, type, required, maxLength, maxItems, description, columns, List.of());
        }
    }
    public record Location(String id, String field, String label, String sheet, Map<String, Object> style) {}
    public record Spec(List<Field> fields, List<Location> locations, Map<String, Object> defaultStyle,
                       Map<String, Map<String, Object>> sheetStyles, List<ExcelRegion> excelRegions) {
        public Spec(List<Field> fields, List<Location> locations, Map<String, Object> defaultStyle, Map<String, Map<String, Object>> sheetStyles) {
            this(fields, locations, defaultStyle, sheetStyles, List.of());
        }
        public Spec { excelRegions = excelRegions == null ? List.of() : List.copyOf(excelRegions); }
    }
    public record ExcelRegion(String sheet, String field, String startCell, String direction, String column, String growth, String rangeName) {
        public ExcelRegion(String sheet, String field, String startCell) { this(sheet, field, startCell, "vertical", null); }
        public ExcelRegion(String sheet, String field, String startCell, String direction) { this(sheet, field, startCell, direction, null); }
        public ExcelRegion(String sheet, String field, String startCell, String direction, String column) { this(sheet, field, startCell, direction, column, "shift", null); }
        public ExcelRegion {
            direction = direction == null ? "vertical" : direction;
            growth = growth == null ? "shift" : growth;
        }
        public boolean horizontal() { return "horizontal".equals(direction); }
    }
    public record Source(String sourceNodeId, String variable, String path) {}
    public record Binding(String field, String sourceNodeId, String variable, String path,
                          boolean constant, Object value, Map<String, String> columnMappings) {}
    public record Row(UUID id, UUID templateId, String name, String fileName, String kind, int version,
                      String status, String readScope, UUID createdBy, Instant updatedAt, Spec spec) {}
    public record Save(String name, String readScope, Spec spec) {}
    public record Preview(UUID capabilityId, List<Binding> bindings, Map<String, Object> values,
                          Map<String, Map<String, Object>> locationStyles, String fileName) {}
}
