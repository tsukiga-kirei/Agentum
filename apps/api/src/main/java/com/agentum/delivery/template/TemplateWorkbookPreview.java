package com.agentum.delivery.template;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** 新模板预览独立提供受控样式数据，不修改原有 Excel 内容预览。 */
public final class TemplateWorkbookPreview {
    public record Cell(String text, int column, int rowSpan, int colSpan, Map<String, Object> style, String formula) {}
    public record Row(double height, List<Cell> cells) {}
    public record Sheet(String name, List<Double> widths, List<Row> rows, boolean truncated) {}
    private TemplateWorkbookPreview() {}
    public static List<Sheet> read(byte[] bytes) {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<Sheet> sheets = new ArrayList<>(); DataFormatter formatter = new DataFormatter(Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (var rawSheet : book) {
                XSSFSheet sheet = (XSSFSheet) rawSheet;
                // 以真实内容界定预览，不把整片仅格式化过的空白网格撑进页面。
                int columns = 0, contentRows = 0;
                for (var row : sheet) for (var cell : row) if (cell.getCellType() == CellType.FORMULA || !formatter.formatCellValue(cell).isBlank()) {
                    columns = Math.max(columns, cell.getColumnIndex() + 1); contentRows = Math.max(contentRows, row.getRowNum() + 1);
                    for (var merged : sheet.getMergedRegions()) if (merged.isInRange(cell)) { columns = Math.max(columns, merged.getLastColumn() + 1); contentRows = Math.max(contentRows, merged.getLastRow() + 1); }
                }
                int maxColumns = Math.min(40, Math.max(1, columns)), maxRows = Math.min(200, Math.max(1, contentRows));
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
                            text = formatter.formatCellValue(cell);
                            var style = cell.getCellStyle(); var font = style.getFont();
                            css.put("fontFamily", font.getFontName()); css.put("fontSize", font.getFontHeightInPoints() + "pt");
                            css.put("fontWeight", font.getBold() ? "bold" : "normal"); css.put("fontStyle", font.getItalic() ? "italic" : "normal");
                            putColor(css, "color", font.getXSSFColor()); if (style.getFillPattern() != FillPatternType.NO_FILL) putColor(css, "backgroundColor", style.getFillForegroundXSSFColor());
                            css.put("textAlign", switch (style.getAlignment()) { case RIGHT -> "right"; case CENTER -> "center"; case JUSTIFY -> "justify"; default -> cell.getCellType() == CellType.NUMERIC ? "right" : "left"; });
                            css.put("verticalAlign", switch (style.getVerticalAlignment()) { case TOP -> "top"; case CENTER -> "middle"; default -> "bottom"; });
                            css.put("whiteSpace", style.getWrapText() ? "pre-wrap" : "pre");
                            for (var side : List.of("Top", "Bottom", "Left", "Right")) {
                                BorderStyle border = switch (side) { case "Top" -> style.getBorderTop(); case "Bottom" -> style.getBorderBottom(); case "Left" -> style.getBorderLeft(); default -> style.getBorderRight(); };
                                XSSFColor borderColor = switch (side) { case "Top" -> style.getTopBorderXSSFColor(); case "Bottom" -> style.getBottomBorderXSSFColor(); case "Left" -> style.getLeftBorderXSSFColor(); default -> style.getRightBorderXSSFColor(); };
                                String borderHex = borderColor != null && borderColor.getRGB() != null ? "#" + HexFormat.of().formatHex(borderColor.getRGB()) : "#667085";
                                css.put("border" + side, border == BorderStyle.NONE ? "none" : border == BorderStyle.DOUBLE ? "3px double " + borderHex : (border == BorderStyle.THICK ? "3px" : border == BorderStyle.MEDIUM ? "2px" : "1px") + (border == BorderStyle.DASHED ? " dashed " : border == BorderStyle.DOTTED ? " dotted " : " solid ") + borderHex);
                            }
                        }
                        cells.add(new Cell(text, c, rowSpan, colSpan, css, cell != null && cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null));
                    }
                    rows.add(new Row(row == null ? 15 : row.getHeightInPoints(), cells));
                }
                sheets.add(new Sheet(sheet.getSheetName(), widths, rows, columns > maxColumns || contentRows > maxRows));
            }
            return sheets;
        } catch (Exception ex) { throw StructuredContent.error("模板样式预览读取失败，请下载文件检查"); }
    }
    private static void putColor(Map<String, Object> css, String key, XSSFColor color) {
        if (color == null || color.getRGB() == null) return;
        css.put(key, "#" + HexFormat.of().formatHex(color.getRGB()));
    }
}
