package com.agentum.delivery.template;

import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.CellReference;

/** 引用调整保留原公式文本、表格结构化引用及字符串常量；布局移动与公式复制分别处理。 */
final class ExcelFormulaReferences {
    record Point(int row, int column) {}
    record Insert(String sheet, boolean horizontal, int at, int count) {}
    record Detail(String sheet, int row, int column, boolean horizontal, int count) {}
    private static final int LAST_ROW = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
    private static final int LAST_COLUMN = SpreadsheetVersion.EXCEL2007.getLastColumnIndex();
    private static final String ADDRESS = "(?:\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]*|\\$?[A-Za-z]{1,3}|\\$?[1-9][0-9]*)";
    private static final Pattern REFERENCE = Pattern.compile("(?:(?:'((?:[^']|'')+)'|([\\p{L}_][\\p{L}\\p{N}_.]*))!)?(" + ADDRESS + ")(?::(" + ADDRESS + "))?");
    private final List<Insert> inserts;
    private final List<Detail> details;
    private final Map<String, Point> relocated;
    ExcelFormulaReferences(List<Insert> inserts, List<Detail> details) { this(inserts, details, Map.of()); }
    ExcelFormulaReferences(List<Insert> inserts, List<Detail> details, Map<String, Point> relocated) { this.inserts = inserts; this.details = details; Map<String, Point> normalized = new HashMap<>(); relocated.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value)); this.relocated = Map.copyOf(normalized); }

    Point move(String sheet, int row, int column) {
        var destination = relocated.get((sheet + "!" + row + ":" + column).toLowerCase(Locale.ROOT));
        if (destination != null) return destination;
        for (var insert : inserts) if (insert.sheet().equalsIgnoreCase(sheet)) {
            if (insert.horizontal() && column >= insert.at()) column += insert.count();
            if (!insert.horizontal() && row >= insert.at()) row += insert.count();
        }
        return new Point(row, column);
    }

    String rewrite(String formula, String contextSheet, boolean recordFormula, int copyRows, int copyColumns) {
        StringBuilder out = new StringBuilder();
        int brackets = 0;
        for (int i = 0; i < formula.length();) {
            char character = formula.charAt(i);
            if (character == '"') {
                int end = i + 1;
                while (end < formula.length()) {
                    if (formula.charAt(end++) == '"') {
                        if (end < formula.length() && formula.charAt(end) == '"') end++;
                        else break;
                    }
                }
                out.append(formula, i, end); i = end; continue;
            }
            if (character == '[') brackets++;
            if (brackets > 0) {
                out.append(character); if (character == ']') brackets--; i++; continue;
            }
            Matcher match = REFERENCE.matcher(formula).region(i, formula.length());
            if ((i == 0 || !identifier(formula.charAt(i - 1))) && match.lookingAt()
                && (match.end() == formula.length() || !identifier(formula.charAt(match.end())) && formula.charAt(match.end()) != '(')) {
                String first = match.group(3), last = match.group(4);
                if (last != null || cellAddress(first)) {
                    String sheet = match.group(1) != null ? match.group(1).replace("''", "'") : match.group(2) != null ? match.group(2) : contextSheet;
                    String prefix = formula.substring(i, match.start(3));
                    out.append(prefix).append(adjust(first, last, sheet, recordFormula, copyRows, copyColumns));
                    i = match.end(); continue;
                }
            }
            out.append(character); i++;
        }
        return out.toString();
    }
    private String adjust(String first, String last, String sheet, boolean recordFormula, int copyRows, int copyColumns) {
        boolean cells = cellAddress(first), columns = !cells && first.matches("\\$?[A-Za-z]+"), rows = !cells && !columns;
        if (last != null && (cells != cellAddress(last) || columns && !last.matches("\\$?[A-Za-z]+"))) return first + ":" + last;
        var a = parse(first, cells, columns); var b = last == null ? a : parse(last, cells, columns);
        var movedA = move(sheet, a.row(), a.column()); var movedB = move(sheet, b.row(), b.column());
        int ar = a.row() == LAST_ROW ? LAST_ROW : movedA.row(), br = b.row() == LAST_ROW ? LAST_ROW : movedB.row();
        int ac = a.column() == LAST_COLUMN ? LAST_COLUMN : movedA.column(), bc = b.column() == LAST_COLUMN ? LAST_COLUMN : movedB.column();
        // 区间恰好结束于明细样板时扩展到实际数据末尾；记录内的逐行公式只复制引用，不变成整列聚合。
        if (last != null && !recordFormula) for (var detail : details) if (detail.sheet().equalsIgnoreCase(sheet)) {
            var start = move(sheet, detail.row(), detail.column());
            if (!detail.horizontal() && !columns && b.row() == detail.row() && a.row() <= detail.row() && (rows || a.column() <= detail.column() && b.column() >= detail.column())) br = Math.max(br, start.row() + detail.count() - 1);
            if (detail.horizontal() && !rows && b.column() == detail.column() && a.column() <= detail.column() && (columns || a.row() <= detail.row() && b.row() >= detail.row())) bc = Math.max(bc, start.column() + detail.count() - 1);
        }
        String newA = format(first, ar, ac, cells, columns, copyRows, copyColumns);
        return last == null ? newA : newA + ":" + format(last, br, bc, cells, columns, copyRows, copyColumns);
    }
    private Point parse(String address, boolean cells, boolean columns) {
        if (cells) { var cell = new CellReference(address); return new Point(cell.getRow(), cell.getCol()); }
        return columns ? new Point(0, CellReference.convertColStringToIndex(address.replace("$", ""))) : new Point(Integer.parseInt(address.replace("$", "")) - 1, 0);
    }
    private String format(String original, int row, int column, boolean cells, boolean columns, int copyRows, int copyColumns) {
        boolean absoluteColumn = original.startsWith("$");
        boolean absoluteRow = cells ? original.matches(".*\\$[0-9]+$") : original.startsWith("$");
        if ((cells || columns) && !absoluteColumn) column += copyColumns;
        if ((cells || !columns) && !absoluteRow) row += copyRows;
        if (row < 0 || row > LAST_ROW || column < 0 || column > LAST_COLUMN) throw StructuredContent.error("公式引用超出 Excel 行列范围");
        String col = (absoluteColumn ? "$" : "") + CellReference.convertNumToColString(column);
        String r = (absoluteRow ? "$" : "") + (row + 1);
        return cells ? col + r : columns ? col : r;
    }
    private boolean cellAddress(String value) { return value.matches("\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]*"); }
    private boolean identifier(char character) { return Character.isLetterOrDigit(character) || character == '_' || character == '.' || character == '$'; }
}
