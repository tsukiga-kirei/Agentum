package com.agentum.delivery.application;

import com.agentum.delivery.template.StructuredContent;
import com.agentum.mcp.application.McpResultPolicy;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;

/** 独立区域按记录方向填充，不插入整行或整列，避免不同明细之间互相移动。 */
public final class ExcelDataRegionWriter {
    private ExcelDataRegionWriter() {}
    public record Column(String field, String label, String type, String format) {}
    public record Region(String startCell, boolean header, List<Column> columns, List<Map<String, Object>> rows, String direction) {
        public Region(String startCell, boolean header, List<Column> columns, List<Map<String, Object>> rows) {
            this(startCell, header, columns, rows, "vertical");
        }
        public Region { direction = direction == null ? "vertical" : direction; }
        public boolean horizontal() { return "horizontal".equals(direction); }
    }
    public static void write(Sheet sheet, List<Region> regions) {
        write(sheet, regions, true);
    }
    public static void write(Sheet sheet, List<Region> regions, boolean template) {
        Set<String> occupied = new HashSet<>();
        for (Region region : regions) {
            if (region.startCell() == null || !region.startCell().matches("[A-Z]{1,3}[1-9][0-9]{0,5}")) throw StructuredContent.error("区域开始位置无效");
            if (!Set.of("vertical", "horizontal").contains(region.direction())) throw StructuredContent.error("区域扩展方向必须为纵向或横向");
            CellReference start = new CellReference(region.startCell());
            int count = Math.max(1, region.rows().size()) + (region.header() ? 1 : 0);
            int height = region.horizontal() ? region.columns().size() : count;
            int width = region.horizontal() ? count : region.columns().size();
            if (region.columns().isEmpty() || region.columns().size() > 100 || (long) start.getRow() + height > 100000 || (long) start.getCol() + width > 16384)
                throw StructuredContent.error("数据区域超出行列限制");
            for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
                String address = (start.getRow() + r) + ":" + (start.getCol() + c);
                if (!occupied.add(address)) throw StructuredContent.error("同一 Sheet 的数据区域发生重叠");
                var row = sheet.getRow(start.getRow() + r);
                Cell cell = row == null ? null : row.getCell(start.getCol() + c);
                // 横向使用样板列，纵向使用样板行；先校验全部目标位置，不能覆盖已有正文、公式或另一块明细。
                int recordOffset = region.horizontal() ? c : r;
                if ((!template || recordOffset > (region.header() ? 1 : 0)) && cell != null && cell.getCellType() != CellType.BLANK && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()))
                    throw StructuredContent.error("数据区域将覆盖已有内容：" + cell.getAddress());
                for (var merged : sheet.getMergedRegions()) if (merged.isInRange(start.getRow() + r, start.getCol() + c)) throw StructuredContent.error("数据区域内暂不支持合并单元格");
            }
        }
        Map<String, CellStyle> formats = new HashMap<>();
        for (Region region : regions) {
            CellReference start = new CellReference(region.startCell());
            int offset = region.header() ? 1 : 0;
            List<CellStyle> styles = new ArrayList<>();
            for (int c = 0; c < region.columns().size(); c++) {
                Row sample = sheet.getRow(start.getRow() + (region.horizontal() ? c : offset));
                Cell cell = sample == null ? null : sample.getCell(start.getCol() + (region.horizontal() ? offset : c));
                styles.add(cell == null ? null : cell.getCellStyle());
            }
            if (region.header()) for (int c = 0; c < region.columns().size(); c++)
                cell(sheet, start.getRow() + (region.horizontal() ? c : 0), start.getCol() + (region.horizontal() ? 0 : c)).setCellValue(region.columns().get(c).label());
            for (int r = 0; r < Math.max(1, region.rows().size()); r++) for (int c = 0; c < region.columns().size(); c++) {
                Column column = region.columns().get(c);
                Cell target = cell(sheet, start.getRow() + (region.horizontal() ? c : offset + r), start.getCol() + (region.horizontal() ? offset + r : c));
                CellStyle base = styles.get(c);
                if (base != null) target.setCellStyle(base);
                String format = column.format();
                if ((format == null || format.isBlank()) && "date".equals(column.type())) format = "yyyy-mm-dd";
                if (format != null && !format.isBlank()) {
                    String key = (base == null ? 0 : base.getIndex()) + ":" + format;
                    final String pattern = format;
                    target.setCellStyle(formats.computeIfAbsent(key, ignored -> { var style = sheet.getWorkbook().createCellStyle(); if (base != null) style.cloneStyleFrom(base); style.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat(pattern)); return style; }));
                }
                Object value = region.rows().isEmpty() ? null : com.agentum.delivery.template.DeliveryTemplateService.path(region.rows().get(r), column.field());
                if (value == null) { target.setBlank(); continue; }
                try {
                    switch (Objects.toString(column.type(), "text")) {
                        case "number" -> { double n = new java.math.BigDecimal(value.toString()).doubleValue(); if (!Double.isFinite(n)) throw new IllegalArgumentException(); target.setCellValue(n); }
                        case "date" -> target.setCellValue(java.time.LocalDate.parse(value.toString()));
                        case "boolean" -> { if (!(value instanceof Boolean b)) throw new IllegalArgumentException(); target.setCellValue(b); }
                        default -> target.setCellValue(value.toString());
                    }
                } catch (RuntimeException ex) { throw StructuredContent.error("区域字段类型不匹配：" + column.label() + "，位置 " + target.getAddress()); }
            }
        }
    }
    private static Cell cell(Sheet sheet, int r, int c) {
        Row row = sheet.getRow(r); if (row == null) row = sheet.createRow(r);
        Cell cell = row.getCell(c); return cell == null ? row.createCell(c) : cell;
    }
}
