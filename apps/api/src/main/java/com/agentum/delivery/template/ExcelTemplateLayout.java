package com.agentum.delivery.template;

import java.util.*;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;

/** 先完成整本布局及引用规划，再填写明细；同行不同列表共享增长空间，后续表头、小计与说明整体移动。 */
final class ExcelTemplateLayout {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExcelTemplateLayout.class);
    record Formula(String sheet, int row, int column, String text, boolean record) {}
    private record Named(XSSFName name, String sheet, String text) {}
    private record Table(XSSFTable table, String sheet, int firstRow, int lastRow, int firstColumn, int lastColumn,
                         int headers, int totals, String field) {}
    private record Sample(String field, Saved cell, boolean horizontal, boolean styleOnly) {}
    private record Saved(String sheet, int row, int column, org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell value) {}
    private record Filter(XSSFSheet sheet, CellRangeAddress range) {}
    private record Band(String sheet, int line, boolean horizontal, int count) {}
    private final XSSFWorkbook book;
    private final List<TemplateModels.ExcelRegion> original;
    private final Map<String, Object> data;
    private final List<ExcelFormulaReferences.Insert> inserts = new ArrayList<>();
    private final List<Formula> formulas = new ArrayList<>();
    private final List<Named> names = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();
    private ExcelFormulaReferences finalReferences;
    private final List<Sample> samples = new ArrayList<>();
    private final List<Saved> totals = new ArrayList<>();
    private final Map<String, ExcelFormulaReferences.Point> relocated = new HashMap<>();
    private final List<Filter> filters = new ArrayList<>();
    private final Map<String, Set<String>> recordFormulas = new HashMap<>();

    ExcelTemplateLayout(XSSFWorkbook book, List<TemplateModels.ExcelRegion> regions, Map<String, Object> data) {
        this.book = book; this.original = regions; this.data = data;
    }
    List<TemplateModels.ExcelRegion> prepare() {
        inspectTables();
        discoverCalculatedCells();
        discoverBlankStyles();
        for (var raw : book) {
            var sheet = (XSSFSheet) raw;
            if (sheet.getCTWorksheet().isSetAutoFilter() && sheet.getCTWorksheet().getAutoFilter().getRef() != null)
                filters.add(new Filter(sheet, CellRangeAddress.valueOf(sheet.getCTWorksheet().getAutoFilter().getRef())));
            for (var row : sheet) for (var cell : row) if (cell.getCellType() == CellType.FORMULA) {
                if (cell.isPartOfArrayFormulaGroup()) throw StructuredContent.error("暂不支持数组公式，请改为普通公式：" + sheet.getSheetName() + "!" + cell.getAddress());
                validateReferenceSyntax(cell.getCellFormula(), sheet.getSheetName() + "!" + cell.getAddress());
                formulas.add(new Formula(sheet.getSheetName(), cell.getRowIndex(), cell.getColumnIndex(), cell.getCellFormula(), isRecordFormula(sheet.getSheetName(), cell.getAddress().formatAsString())));
            }
        }
        for (var name : book.getAllNames()) if (name.getRefersToFormula() != null && !name.getRefersToFormula().isBlank()) {
            validateReferenceSyntax(name.getRefersToFormula(), "定义名称 " + name.getNameName());
            names.add(new Named(name, name.getSheetIndex() >= 0 ? book.getSheetName(name.getSheetIndex()) : book.getSheetName(0), name.getRefersToFormula()));
        }
        // 公式由本规划器统一恢复；移动时用占位文字保持其占用位置，避免 POI 对未限定表名的 [@列] 再次解析。
        for (var formula : formulas) { var cell = cell(formula.sheet(), formula.row(), formula.column()); cell.setBlank(); cell.setCellValue("__AGENTUM_FORMULA_POSITION__"); }
        // 自下向上、自右向左分配空间，保存的插入操作用于整个工作簿及所有字段位置统一换算。
        for (boolean horizontal : List.of(false, true)) {
            Map<String, Band> bands = new LinkedHashMap<>();
            for (var region : original) if (region.horizontal() == horizontal && !"fixed".equals(region.growth())) {
                var position = point(region); int line = horizontal ? position.column() : position.row();
                String key = region.sheet() + "\0" + line;
                var previous = bands.get(key);
                if (previous == null || previous.count() < count(region.field())) bands.put(key, new Band(region.sheet(), line, horizontal, count(region.field())));
            }
            var ordered = bands.values().stream().sorted(Comparator.comparing(Band::sheet).thenComparing(Comparator.comparingInt(Band::line).reversed())).toList();
            for (var band : ordered) reserve(band);
        }
        relocateTotals();
        finalReferences = references();
        var references = finalReferences;
        List<TemplateModels.ExcelRegion> moved = original.stream().map(region -> {
            var anchor = point(region); var target = references.move(region.sheet(), anchor.row(), anchor.column());
            return new TemplateModels.ExcelRegion(region.sheet(), region.field(), address(target), region.direction(), region.column(), region.growth(), region.rangeName());
        }).toList();
        updateTables(references);
        for (var named : names) named.name().setRefersToFormula(references.rewrite(named.text(), named.sheet(), false, 0, 0));
        for (var filter : filters) {
            String adjusted = references.rewrite(filter.range().formatAsString(), filter.sheet().getSheetName(), false, 0, 0);
            var members = original.stream().filter(region -> region.sheet().equals(filter.sheet().getSheetName()) && !region.horizontal() && point(region).row() == filter.range().getFirstRow() + 1 && point(region).column() >= filter.range().getFirstColumn() && point(region).column() <= filter.range().getLastColumn()).toList();
            if (!members.isEmpty()) {
                var first = references.move(filter.sheet().getSheetName(), filter.range().getFirstRow(), filter.range().getFirstColumn());
                var last = references.move(filter.sheet().getSheetName(), filter.range().getFirstRow() + 1, filter.range().getLastColumn());
                int records = members.stream().mapToInt(region -> count(region.field())).max().orElse(1);
                adjusted = new CellRangeAddress(first.row(), last.row() + records - 1, first.column(), last.column()).formatAsString();
            }
            filter.sheet().getCTWorksheet().getAutoFilter().setRef(adjusted);
        }
        // 定义名称始终指向本次实际记录，不靠 COUNTA 推断条数，记录中的空值不会缩短范围。
        for (var region : moved) {
            var anchor = point(region);
            var end = new ExcelFormulaReferences.Point(anchor.row() + (region.horizontal() ? 0 : count(region.field()) - 1), anchor.column() + (region.horizontal() ? count(region.field()) - 1 : 0));
            var name = book.getName(region.rangeName());
            if (name == null) { name = book.createName(); name.setNameName(region.rangeName()); }
            if (name.getSheetIndex() >= 0) throw StructuredContent.error("明细公式引用名称需使用工作簿范围：" + region.rangeName());
            name.setRefersToFormula(new CellReference(region.sheet(), anchor.row(), anchor.column(), true, true).formatAsString() + ":" + new CellReference(end.row(), end.column(), true, true).formatAsString());
        }
        copyTableConstants(references);
        restoreAndCopyFormulas(references);
        log.info("Excel 模板布局规划完成 sheetCount={} positionCount={} insertedRows={} insertedColumns={} tenantId={} userId={} requestId={}", book.getNumberOfSheets(), moved.size(), inserts.stream().filter(it -> !it.horizontal()).mapToInt(ExcelFormulaReferences.Insert::count).sum(), inserts.stream().filter(ExcelFormulaReferences.Insert::horizontal).mapToInt(ExcelFormulaReferences.Insert::count).sum(), org.slf4j.MDC.get("tenantId"), org.slf4j.MDC.get("userId"), com.agentum.shared.api.RequestIds.current());
        return moved;
    }
    private void inspectTables() {
        for (var raw : book) for (var table : ((XSSFSheet) raw).getTables()) {
            var start = table.getStartCellReference(); var end = table.getEndCellReference();
            var members = original.stream().filter(region -> region.sheet().equals(raw.getSheetName()) && table.contains(new CellReference(region.sheet(), point(region).row(), point(region).column(), false, false))).toList();
            String field = "";
            if (!members.isEmpty()) {
                if (members.stream().anyMatch(TemplateModels.ExcelRegion::horizontal)) throw StructuredContent.error("Excel 表格中的记录只能纵向扩展：" + raw.getSheetName() + " / " + table.getName());
                field = members.getFirst().field(); final String key = field;
                if (members.stream().anyMatch(region -> !region.field().equals(key) || point(region).row() != start.getRow() + table.getHeaderRowCount()))
                    throw StructuredContent.error("每个 Excel 表格需在首个数据行绑定一份明细列表：" + table.getName());
                // 已声明为数据区的预留行不当作固定正文；保留样式，清空旧示例和预先填下来的公式，随后按实际条数重建。
                for (int r = start.getRow() + table.getHeaderRowCount() + 1; r <= end.getRow() - table.getTotalsRowCount(); r++) {
                    var reserved = ((XSSFSheet) raw).getRow(r);
                    if (reserved != null) for (int c = start.getCol(); c <= end.getCol(); c++) if (reserved.getCell(c) != null) reserved.getCell(c).setBlank();
                }
                for (int r = end.getRow() - table.getTotalsRowCount() + 1; r <= end.getRow(); r++) {
                    for (int c = start.getCol(); c <= end.getCol(); c++) {
                        var total = cell(raw.getSheetName(), r, c);
                        totals.add(new Saved(raw.getSheetName(), r, c, (org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell) total.getCTCell().copy()));
                    }
                }
                // 表格计算列有时只保存在表定义中，将其落到样板单元格后统一复制及计算。
                for (int c = start.getCol(); c <= end.getCol(); c++) {
                    var definition = table.getCTTable().getTableColumns().getTableColumnArray(c - start.getCol());
                    var row = ((XSSFSheet) raw).getRow(start.getRow() + table.getHeaderRowCount());
                    if (row == null) row = ((XSSFSheet) raw).createRow(start.getRow() + table.getHeaderRowCount());
                    var cell = row.getCell(c); if (cell == null) cell = row.createCell(c);
                    if (cell.getCellType() == CellType.BLANK && definition.isSetCalculatedColumnFormula()) ExcelFormulaCalculation.setFormula(cell, definition.getCalculatedColumnFormula().getStringValue());
                    if (cell.getCellType() != CellType.FORMULA && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("{{")))
                        samples.add(new Sample(field, new Saved(raw.getSheetName(), cell.getRowIndex(), c, (org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell) cell.getCTCell().copy()), false, false));
                }
            }
            tables.add(new Table(table, raw.getSheetName(), start.getRow(), end.getRow(), start.getCol(), end.getCol(), table.getHeaderRowCount(), table.getTotalsRowCount(), field));
        }
    }
    private void discoverCalculatedCells() {
        // 表格范围中的公式归属明确；普通区域只接续样板字段相邻的公式，不跨空列或另一份列表猜测。
        for (var region : original) {
            var sheet = book.getSheet(region.sheet()); var anchor = point(region);
            for (var table : tables) if (table.sheet().equals(region.sheet()) && table.field().equals(region.field()) && table.table().contains(new CellReference(region.sheet(), point(region).row(), point(region).column(), false, false))) {
                for (int c = table.firstColumn(); c <= table.lastColumn(); c++) registerFormula(sheet, anchor.row(), c, region);
            }
            for (int step : List.of(-1, 1)) {
                int r = anchor.row(), c = anchor.column();
                while (true) {
                    if (region.horizontal()) r += step; else c += step;
                    if (r < 0 || c < 0 || c >= 16384 || r >= 100000) break;
                    var row = sheet.getRow(r); var cell = row == null ? null : row.getCell(c);
                    if (cell == null || cell.getCellType() == CellType.BLANK) break;
                    if (cell.getCellType() == CellType.FORMULA) { registerFormula(sheet, r, c, region); continue; }
                    if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("{{" + region.field() + "[].")) continue;
                    break;
                }
            }
        }
    }
    private void discoverBlankStyles() {
        Set<String> seen = new HashSet<>();
        for (var region : original) {
            var anchor = point(region);
            var peers = original.stream().filter(it -> it.sheet().equals(region.sheet()) && it.field().equals(region.field()) && it.horizontal() == region.horizontal() && (region.horizontal() ? point(it).column() == anchor.column() : point(it).row() == anchor.row())).toList();
            int first = peers.stream().mapToInt(it -> region.horizontal() ? point(it).row() : point(it).column()).min().orElse(0);
            int last = peers.stream().mapToInt(it -> region.horizontal() ? point(it).row() : point(it).column()).max().orElse(0);
            for (int position = first; position <= last; position++) {
                int r = region.horizontal() ? position : anchor.row(), c = region.horizontal() ? anchor.column() : position;
                var row = book.getSheet(region.sheet()).getRow(r); var cell = row == null ? null : row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.BLANK && cell.getCTCell().isSetS() && seen.add(region.sheet() + "!" + r + ":" + c + ":" + region.horizontal()))
                    samples.add(new Sample(region.field(), new Saved(region.sheet(), r, c, (org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell) cell.getCTCell().copy()), region.horizontal(), true));
            }
        }
    }
    private void registerFormula(XSSFSheet sheet, int row, int column, TemplateModels.ExcelRegion region) {
        var source = sheet.getRow(row); var cell = source == null ? null : source.getCell(column);
        if (cell != null && cell.getCellType() == CellType.FORMULA) recordFormulas.computeIfAbsent(sheet.getSheetName() + "!" + cell.getAddress(), ignored -> new LinkedHashSet<>()).add(key(region));
    }
    private boolean isRecordFormula(String sheet, String address) { return recordFormulas.containsKey(sheet + "!" + address); }
    private void reserve(Band band) {
        if (band.count() <= 1) return;
        var sheet = book.getSheet(band.sheet());
        var line = references().move(band.sheet(), band.horizontal() ? 0 : band.line(), band.horizontal() ? band.line() : 0);
        int anchor = band.horizontal() ? line.column() : line.row();
        int obstruction = Integer.MAX_VALUE;
        for (var row : sheet) for (var cell : row) {
            int value = band.horizontal() ? cell.getColumnIndex() : cell.getRowIndex();
            if (value > anchor && meaningful(cell)) obstruction = Math.min(obstruction, value);
        }
        if (!band.horizontal()) for (var table : tables) if (table.sheet().equals(band.sheet()) && !table.field().isEmpty() && table.totals() > 0) {
            int total = references().move(table.sheet(), table.lastRow() - table.totals() + 1, table.firstColumn()).row();
            if (total > anchor) obstruction = Math.min(obstruction, total);
        }
        for (var merged : sheet.getMergedRegions()) {
            int first = band.horizontal() ? merged.getFirstColumn() : merged.getFirstRow();
            int last = band.horizontal() ? merged.getLastColumn() : merged.getLastRow();
            if (first <= anchor && last > anchor) throw StructuredContent.error("明细样板不能跨记录方向合并：" + band.sheet() + "!" + merged.formatAsString());
        }
        if (obstruction == Integer.MAX_VALUE || obstruction >= anchor + band.count()) return;
        int count = anchor + band.count() - obstruction;
        int last = band.horizontal() ? lastColumn(sheet) : sheet.getLastRowNum();
        int limit = band.horizontal() ? 16384 : SpreadsheetVersion.EXCEL2007.getMaxRows();
        if ((long) last + count >= limit) throw StructuredContent.error("扩展后的模板超出行列限制：" + band.sheet());
        for (var merged : sheet.getMergedRegions()) {
            int first = band.horizontal() ? merged.getFirstColumn() : merged.getFirstRow();
            int end = band.horizontal() ? merged.getLastColumn() : merged.getLastRow();
            if (first < obstruction && end >= obstruction) throw StructuredContent.error("插入位置穿过合并区域：" + band.sheet() + "!" + merged.formatAsString());
        }
        if (band.horizontal()) {
            for (var table : tables) if (table.sheet().equals(band.sheet())) {
                int first = references().move(table.sheet(), table.firstRow(), table.firstColumn()).column();
                int end = references().move(table.sheet(), table.lastRow(), table.lastColumn()).column();
                if (first < obstruction && end >= obstruction) throw StructuredContent.error("横向扩展会切开已有 Excel 表格，请调整字段位置：" + band.sheet() + " / " + table.table().getName());
            }
            var widths = new HashMap<Integer, Integer>(); var hidden = new HashMap<Integer, Boolean>();
            for (int c = obstruction; c <= last; c++) { widths.put(c, sheet.getColumnWidth(c)); hidden.put(c, sheet.isColumnHidden(c)); }
            sheet.shiftColumns(obstruction, last, count);
            for (int c = last; c >= obstruction; c--) { sheet.setColumnWidth(c + count, widths.get(c)); sheet.setColumnHidden(c + count, hidden.get(c)); }
            for (int c = obstruction; c < obstruction + count; c++) sheet.setColumnWidth(c, sheet.getColumnWidth(anchor));
        } else sheet.shiftRows(obstruction, last, count, true, false);
        inserts.add(new ExcelFormulaReferences.Insert(band.sheet(), band.horizontal(), obstruction, count));
    }
    private void relocateTotals() {
        var base = references();
        // 同行并列列表共享插入空间，但各表格的合计紧接自己的实际记录，不能把短表格的合计留在长表格末尾。
        for (var saved : totals) {
            var old = base.move(saved.sheet(), saved.row(), saved.column());
            cell(saved.sheet(), old.row(), old.column()).setBlank();
        }
        for (var table : tables) if (!table.field().isEmpty() && table.totals() > 0) {
            var start = base.move(table.sheet(), table.firstRow(), table.firstColumn());
            for (var saved : totals) if (saved.sheet().equals(table.sheet()) && saved.column() >= table.firstColumn() && saved.column() <= table.lastColumn() && saved.row() > table.lastRow() - table.totals() && saved.row() <= table.lastRow()) {
                int r = start.row() + table.headers() + count(table.field()) + saved.row() - (table.lastRow() - table.totals() + 1);
                int c = base.move(saved.sheet(), saved.row(), saved.column()).column();
                var target = cell(saved.sheet(), r, c);
                if (meaningful(target)) throw StructuredContent.error("表格合计将覆盖已有内容：" + saved.sheet() + "!" + target.getAddress());
                target.getCTCell().set(saved.value()); target.getCTCell().setR(target.getAddress().formatAsString());
                relocated.put(saved.sheet() + "!" + saved.row() + ":" + saved.column(), new ExcelFormulaReferences.Point(r, c));
            }
        }
    }
    private void updateTables(ExcelFormulaReferences references) {
        for (var table : tables) {
            var start = references.move(table.sheet(), table.firstRow(), table.firstColumn());
            var end = references.move(table.sheet(), table.lastRow(), table.lastColumn());
            if (!table.field().isEmpty()) end = new ExcelFormulaReferences.Point(start.row() + table.headers() + count(table.field()) + table.totals() - 1, end.column());
            var area = new AreaReference(new CellReference(start.row(), start.column()), new CellReference(end.row(), end.column()), SpreadsheetVersion.EXCEL2007);
            table.table().setArea(area);
            if (table.table().getCTTable().isSetAutoFilter()) table.table().getCTTable().getAutoFilter().setRef(new CellRangeAddress(start.row(), end.row() - table.totals(), start.column(), end.column()).formatAsString());
        }
    }
    private void copyTableConstants(ExcelFormulaReferences references) {
        for (var sample : samples) {
            var source = sample.cell(); var start = references.move(source.sheet(), source.row(), source.column());
            for (int i = 1; i < count(sample.field()); i++) {
                var target = cell(source.sheet(), start.row() + (sample.horizontal() ? 0 : i), start.column() + (sample.horizontal() ? i : 0));
                if (meaningful(target)) {
                    if (sample.styleOnly()) continue;
                    throw StructuredContent.error("表格数据区将覆盖已有内容：" + source.sheet() + "!" + target.getAddress());
                }
                target.getCTCell().set(source.value()); target.getCTCell().setR(target.getAddress().formatAsString());
            }
        }
    }
    private void validateReferenceSyntax(String expression, String position) {
        // 3D 引用把多个 Sheet 合成一个地址，无法表达各明细不同的实际末行；明确拒绝，避免悄悄漏算。
        String outsideText = expression.replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"");
        if (outsideText.matches("(?s).*(?:'[^']*:[^']*'|[A-Za-z_][A-Za-z0-9_.]*:[A-Za-z_][A-Za-z0-9_.]*)!.*"))
            throw StructuredContent.error("暂不支持多个 Sheet 的三维引用，请分别引用或使用明细命名范围：" + position);
    }
    private void restoreAndCopyFormulas(ExcelFormulaReferences references) {
        Set<String> occupied = new HashSet<>();
        for (var formula : formulas) {
            var target = references.move(formula.sheet(), formula.row(), formula.column());
            ExcelFormulaCalculation.setFormula(cell(formula.sheet(), target.row(), target.column()), references.rewrite(formula.text(), formula.sheet(), formula.record(), 0, 0));
            occupied.add(formula.sheet() + "!" + address(target));
        }
        for (var formula : formulas) if (formula.record()) {
            var source = references.move(formula.sheet(), formula.row(), formula.column());
            var sample = cell(formula.sheet(), source.row(), source.column());
            for (var region : original) if (recordFormulas.get(formula.sheet() + "!" + new CellReference(formula.row(), formula.column()).formatAsString()).contains(key(region))) {
                for (int i = 1; i < count(region.field()); i++) {
                    int r = source.row() + (region.horizontal() ? 0 : i), c = source.column() + (region.horizontal() ? i : 0);
                    String id = formula.sheet() + "!" + new CellReference(r, c).formatAsString();
                    String expression = references.rewrite(formula.text(), formula.sheet(), true, region.horizontal() ? 0 : i, region.horizontal() ? i : 0);
                    var row = book.getSheet(formula.sheet()).getRow(r); var existing = row == null ? null : row.getCell(c);
                    if (occupied.contains(id)) {
                        if (existing == null || existing.getCellType() != CellType.FORMULA || !existing.getCellFormula().equals(expression)) throw StructuredContent.error("计算列与其他内容冲突：" + id);
                        continue;
                    }
                    if (existing != null && meaningful(existing)) throw StructuredContent.error("计算列将覆盖已有内容：" + id);
                    var copy = cell(formula.sheet(), r, c); copy.setCellStyle(sample.getCellStyle()); ExcelFormulaCalculation.setFormula(copy, expression); occupied.add(id);
                }
            }
        }
    }
    TemplateModels.Spec relocate(TemplateModels.Spec spec, List<TemplateModels.ExcelRegion> moved) {
        var locations = spec.locations().stream().map(location -> location.sheet().isBlank() ? location : new TemplateModels.Location(moveLocationId(location.id(), location.sheet()), location.field(), location.label(), location.sheet(), location.style())).toList();
        return new TemplateModels.Spec(spec.fields(), locations, spec.defaultStyle(), spec.sheetStyles(), moved, spec.formatGroups());
    }
    Map<String, Map<String, Object>> relocateOverrides(TemplateModels.Spec spec, Map<String, Map<String, Object>> overrides) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var location : spec.locations()) if (overrides.containsKey(location.id())) result.put(moveLocationId(location.id(), location.sheet()), overrides.get(location.id()));
        return result;
    }
    private String moveLocationId(String id, String sheet) {
        String suffix = id.substring(id.lastIndexOf(':'));
        var point = new CellReference(id.substring(id.lastIndexOf('!') + 1, id.lastIndexOf(':')));
        return sheet + "!" + address(references().move(sheet, point.getRow(), point.getCol())) + suffix;
    }
    private ExcelFormulaReferences references() {
        if (finalReferences != null) return finalReferences;
        List<ExcelFormulaReferences.Detail> details = new ArrayList<>();
        for (var region : original) {
            var point = point(region); details.add(new ExcelFormulaReferences.Detail(region.sheet(), point.row(), point.column(), region.horizontal(), count(region.field())));
            for (var formula : formulas) if (formula.record() && recordFormulas.get(formula.sheet() + "!" + new CellReference(formula.row(), formula.column()).formatAsString()).contains(key(region)))
                details.add(new ExcelFormulaReferences.Detail(formula.sheet(), formula.row(), formula.column(), region.horizontal(), count(region.field())));
        }
        return new ExcelFormulaReferences(inserts, details, relocated);
    }
    private int count(String field) { return data.get(field) instanceof List<?> list ? Math.max(1, list.size()) : 1; }
    private ExcelFormulaReferences.Point point(TemplateModels.ExcelRegion region) { var reference = new CellReference(region.startCell()); return new ExcelFormulaReferences.Point(reference.getRow(), reference.getCol()); }
    private String address(ExcelFormulaReferences.Point point) { return new CellReference(point.row(), point.column()).formatAsString(); }
    private String key(TemplateModels.ExcelRegion region) { return region.sheet() + "!" + region.startCell() + ":" + region.field() + "." + region.column(); }
    private boolean meaningful(Cell cell) { return cell.getCellType() != CellType.BLANK && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()); }
    private int lastColumn(XSSFSheet sheet) { int last = 0; for (var row : sheet) last = Math.max(last, row.getLastCellNum() - 1); return last; }
    private XSSFCell cell(String sheet, int row, int column) { var target = book.getSheet(sheet).getRow(row); if (target == null) target = book.getSheet(sheet).createRow(row); var cell = target.getCell(column); return cell == null ? target.createCell(column) : cell; }
}
