package com.agentum.delivery.template;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.xssf.usermodel.*;

/** 所有明细及引用范围完成后再计算缓存；无法确认计算结果时不输出携带旧缓存的交付文件。 */
final class ExcelFormulaCalculation {
    private ExcelFormulaCalculation() {}
    private static final Pattern ROW_REFERENCE = Pattern.compile("(?:([\\p{L}_][\\p{L}\\p{N}_.]*))?\\[(?:@\\[([^\\]]+)\\]|@([^\\]]+)|\\[#This Row\\],\\[([^\\]]+)\\])\\]", Pattern.CASE_INSENSITIVE);
    static void setFormula(XSSFCell cell, String expression) {
        cell.setBlank();
        if (cell.getCTCell().isSetT()) cell.getCTCell().unsetT();
        cell.getCTCell().addNewF().setStringValue(expression);
    }
    private static String forEvaluation(XSSFCell cell, XSSFWorkbook book) {
        String expression = cell.getCellFormula();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expression.length();) {
            if (expression.charAt(i) == '"') {
                int end = i + 1;
                while (end < expression.length()) if (expression.charAt(end++) == '"') {
                    if (end < expression.length() && expression.charAt(end) == '"') end++; else break;
                }
                out.append(expression, i, end); i = end; continue;
            }
            var matcher = ROW_REFERENCE.matcher(expression).region(i, expression.length());
            if (matcher.lookingAt() && (i == 0 || !Character.isLetterOrDigit(expression.charAt(i - 1)) && expression.charAt(i - 1) != '_')) {
                XSSFTable table = null;
                for (var sheet : book) for (var candidate : ((XSSFSheet) sheet).getTables()) {
                    if (matcher.group(1) != null ? matcher.group(1).equalsIgnoreCase(candidate.getName()) : candidate.contains(new CellReference(cell.getSheet().getSheetName(), cell.getRowIndex(), cell.getColumnIndex(), false, false))) table = candidate;
                }
                String name = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
                int column = -1;
                if (table != null) for (int c = 0; c < table.getColumnCount(); c++) if (name.equalsIgnoreCase(table.getCTTable().getTableColumns().getTableColumnArray(c).getName())) column = table.getStartColIndex() + c;
                if (table == null || column < 0) throw StructuredContent.error("无法识别表格计算列：" + cell.getSheet().getSheetName() + "!" + cell.getAddress());
                out.append(new CellReference(table.getSheetName(), cell.getRowIndex(), column, false, false).formatAsString());
                i = matcher.end(); continue;
            }
            out.append(expression.charAt(i++));
        }
        return out.toString();
    }
    static void calculate(XSSFWorkbook book) {
        Map<XSSFCell, String> expressions = new LinkedHashMap<>();
        // POI 5.2 对 [@列] 的支持不完整；仅计算时换成对应单元格，输出仍保留 Excel 原生结构化公式。
        for (var sheet : book) for (var row : sheet) for (var raw : row) if (raw.getCellType() == CellType.FORMULA) {
            var cell = (XSSFCell) raw; String source = cell.getCellFormula(); String normalized = forEvaluation(cell, book);
            if (!source.equals(normalized)) { expressions.put(cell, source); cell.getCTCell().getF().setStringValue(normalized); }
        }
        var evaluator = book.getCreationHelper().createFormulaEvaluator();
        evaluator.clearAllCachedResultValues();
        for (var sheet : book) for (var row : sheet) for (var cell : row) {
            if (cell.getCellType() != CellType.FORMULA && cell.getCellType() != CellType.ERROR) continue;
            String position = "Sheet「" + sheet.getSheetName() + "」!" + cell.getAddress();
            try {
                var type = cell.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(cell) : CellType.ERROR;
                if (type == CellType.ERROR) {
                    var error = FormulaError.forInt(cell.getErrorCellValue());
                    throw StructuredContent.error("公式计算失败：" + position + "，" + (error == FormulaError.CIRCULAR_REF ? "存在循环引用" : error.getString()));
                }
            } catch (com.agentum.shared.api.ApiException ex) { throw ex; }
            catch (RuntimeException ex) {
                throw StructuredContent.error("公式无法计算：" + position + "。请检查引用，或改用受支持的函数；外部工作簿依赖需要先转为本工作簿数据");
            }
        }
        expressions.forEach((cell, formula) -> cell.getCTCell().getF().setStringValue(formula));
        book.setForceFormulaRecalculation(true);
    }
}
