package com.agentum.delivery.template;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** 新模板预览独立提供受控样式数据，不修改原有 Excel 内容预览。 */
public final class TemplateWorkbookPreview {
    public record Cell(String text, int column, int rowSpan, int colSpan, Map<String, Object> style) {}
    public record Row(double height, List<Cell> cells) {}
    public record Sheet(String name, List<Double> widths, List<Row> rows, boolean truncated) {}
    private TemplateWorkbookPreview() {}
    public static List<Sheet> read(byte[] bytes) {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<Sheet> sheets = new ArrayList<>(); DataFormatter formatter = new DataFormatter(Locale.CHINA);
            for (var rawSheet : book) {
                XSSFSheet sheet = (XSSFSheet) rawSheet;
                int columns = 0;
                for (var row : sheet) columns = Math.max(columns, row.getLastCellNum());
                int maxColumns = Math.min(40, columns), maxRows = Math.min(200, sheet.getLastRowNum() + 1);
                List<Double> widths = new ArrayList<>(); for (int c = 0; c < maxColumns; c++) widths.add((double) sheet.getColumnWidthInPixels(c));
                List<Row> rows = new ArrayList<>();
                for (int r = 0; r < maxRows; r++) {
                    var row = sheet.getRow(r); List<Cell> cells = new ArrayList<>();
                    for (int c = 0; c < maxColumns; c++) {
                        int rowSpan = 1, colSpan = 1; boolean covered = false;
                        for (var merged : sheet.getMergedRegions()) if (merged.isInRange(r, c)) {
                            covered = merged.getFirstRow() != r || merged.getFirstColumn() != c;
                            rowSpan = Math.min(maxRows - r, merged.getLastRow() - r + 1); colSpan = Math.min(maxColumns - c, merged.getLastColumn() - c + 1); break;
                        }
                        if (covered) continue;
                        var cell = row == null ? null : row.getCell(c);
                        Map<String, Object> css = new LinkedHashMap<>(); String text = "";
                        if (cell != null) {
                            text = cell.getCellType() == CellType.FORMULA ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell);
                            var style = cell.getCellStyle(); var font = style.getFont();
                            css.put("fontFamily", font.getFontName()); css.put("fontSize", font.getFontHeightInPoints() + "pt");
                            css.put("fontWeight", font.getBold() ? "bold" : "normal"); css.put("fontStyle", font.getItalic() ? "italic" : "normal");
                            putColor(css, "color", font.getXSSFColor()); putColor(css, "backgroundColor", style.getFillForegroundXSSFColor());
                            css.put("textAlign", switch (style.getAlignment()) { case RIGHT -> "right"; case CENTER -> "center"; case JUSTIFY -> "justify"; default -> cell.getCellType() == CellType.NUMERIC ? "right" : "left"; });
                            css.put("verticalAlign", switch (style.getVerticalAlignment()) { case TOP -> "top"; case CENTER -> "middle"; default -> "bottom"; });
                            css.put("whiteSpace", style.getWrapText() ? "pre-wrap" : "pre");
                            for (var side : List.of("Top", "Bottom", "Left", "Right")) {
                                BorderStyle border = switch (side) { case "Top" -> style.getBorderTop(); case "Bottom" -> style.getBorderBottom(); case "Left" -> style.getBorderLeft(); default -> style.getBorderRight(); };
                                css.put("border" + side, border == BorderStyle.NONE ? "1px solid #e5e7eb" : "1px solid #667085");
                            }
                        }
                        cells.add(new Cell(text, c, rowSpan, colSpan, css));
                    }
                    rows.add(new Row(row == null ? 15 : row.getHeightInPoints(), cells));
                }
                sheets.add(new Sheet(sheet.getSheetName(), widths, rows, columns > maxColumns || sheet.getLastRowNum() + 1 > maxRows));
            }
            return sheets;
        } catch (Exception ex) { throw StructuredContent.error("模板样式预览读取失败，请下载文件检查"); }
    }
    private static void putColor(Map<String, Object> css, String key, XSSFColor color) {
        if (color == null || color.getRGB() == null) return;
        css.put(key, "#" + HexFormat.of().formatHex(color.getRGB()));
    }
}
