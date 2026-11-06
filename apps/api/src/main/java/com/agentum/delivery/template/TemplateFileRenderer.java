package com.agentum.delivery.template;

import com.agentum.delivery.application.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

/** 仅文件模板模式使用；在副本中替换，绝不重建或覆盖上传原件。 */
@Component
public class TemplateFileRenderer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TemplateFileRenderer.class);
    static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-z][a-z0-9_]*)(?:\\[\\]\\.([a-z][a-z0-9_]*))?\\s*}}");
    private final MarkdownDocxRenderer markdown;
    public TemplateFileRenderer(MarkdownDocxRenderer markdown) { this.markdown = markdown; }

    public TemplateModels.Spec inspect(byte[] bytes, String kind) {
        Map<String, TemplateModels.Field> fields = new LinkedHashMap<>();
        List<TemplateModels.Location> locations = new ArrayList<>();
        try {
            if ("word".equals(kind)) {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    rejectMacros(doc.getPackage());
                    for (var box : doc.getDocument().selectPath("declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'; .//w:txbxContent")) {
                        if (box.xmlText().replaceAll("<[^>]+>", "").contains("{{")) throw StructuredContent.error("暂不支持文本框内的填写标记，请放到正文或表格中");
                    }
                    scanBody(doc, "正文", fields, locations);
                    for (int i = 0; i < doc.getHeaderList().size(); i++) scanBody(doc.getHeaderList().get(i), "页眉" + i, fields, locations);
                    for (int i = 0; i < doc.getFooterList().size(); i++) scanBody(doc.getFooterList().get(i), "页脚" + i, fields, locations);
                }
            } else {
                try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    rejectMacros(book.getPackage());
                    if (book.getNumberOfSheets() > 30) throw StructuredContent.error("模板最多支持 30 个 Sheet");
                    for (var sheet : book) for (var row : sheet) for (var cell : row) {
                        if (cell.getCellType() == CellType.FORMULA && cell.getCellFormula().contains("{{")) throw StructuredContent.error("填写标记不能放入 Excel 公式中");
                        if (cell.getCellType() == CellType.STRING) scan(cell.getStringCellValue(), sheet.getSheetName() + "!" + cell.getAddress(), sheet.getSheetName(), fields, locations);
                    }
                }
            }
        } catch (com.agentum.shared.api.ApiException ex) { throw ex; }
        catch (Exception ex) { throw StructuredContent.error("无法读取模板，请上传未加密且完整的 docx 或 xlsx 文件"); }
        if (fields.isEmpty()) throw StructuredContent.error("模板中没有可填写位置，请先在原文件中添加 {{company_name}} 或 {{items[].name}} 等标记，再重新上传");
        StructuredContent.validateFields(new ArrayList<>(fields.values()));
        var definitions = new ArrayList<>(fields.values());
        return new TemplateModels.Spec(definitions, locations, Map.of(), Map.of(), "excel".equals(kind) ? inferExcelRegions(definitions, locations) : List.of());
    }

    private List<TemplateModels.ExcelRegion> inferExcelRegions(List<TemplateModels.Field> fields, List<TemplateModels.Location> locations) {
        List<TemplateModels.ExcelRegion> result = new ArrayList<>();
        for (var definition : fields) {
            if (!"table".equals(definition.type())) continue;
            for (var location : locations) {
                if (!definition.key().equals(location.field()) || location.sheet().isBlank()) continue;
                String column = location.id().substring(location.id().lastIndexOf('.') + 1);
                String address = location.id().substring(location.id().lastIndexOf('!') + 1, location.id().lastIndexOf(':'));
                var anchor = new org.apache.poi.ss.util.CellReference(address);
                var peers = locations.stream().filter(it -> it.sheet().equals(location.sheet()) && it.field().equals(definition.key()) && !it.id().equals(location.id())).toList();
                // 每个标记都是独立填写位置；同列竖排的不同字段默认向右扩展，其余向下，允许逐位置调整。
                boolean horizontal = peers.stream().anyMatch(it -> {
                    var cell = new org.apache.poi.ss.util.CellReference(it.id().substring(it.id().lastIndexOf('!') + 1, it.id().lastIndexOf(':')));
                    return cell.getCol() == anchor.getCol() && cell.getRow() != anchor.getRow() && !it.id().endsWith("." + column);
                });
                result.add(new TemplateModels.ExcelRegion(location.sheet(), definition.key(), address, horizontal ? "horizontal" : "vertical", column));
            }
        }
        return result;
    }

    public TemplateModels.Spec resolveExcelRegions(TemplateModels.Spec spec) {
        Map<String, TemplateModels.ExcelRegion> regions = new LinkedHashMap<>();
        for (var region : inferExcelRegions(spec.fields(), spec.locations())) regions.put(regionKey(region), region);
        Set<String> explicit = new HashSet<>();
        for (var region : spec.excelRegions()) {
            if (!Set.of("vertical", "horizontal").contains(region.direction())) throw StructuredContent.error("扩展方向必须为纵向或横向");
            // 每个位置独立配置方向，数组只绑定一次；同一字段跨 Sheet、重复出现也不丢失位置。
            var targets = region.column() != null ? List.of(region) : regions.values().stream().filter(it -> it.sheet().equals(region.sheet()) && it.field().equals(region.field())).map(it -> new TemplateModels.ExcelRegion(it.sheet(), it.field(), it.startCell(), region.direction(), it.column(), region.growth(), region.rangeName())).toList();
            if (targets.isEmpty()) throw StructuredContent.error("明细填写位置不存在");
            for (var target : targets) {
                String key = regionKey(target);
                if (!regions.containsKey(key)) throw StructuredContent.error("明细填写位置与原文件标记不一致");
                if (!explicit.add(key)) throw StructuredContent.error("同一明细位置不能重复配置");
                regions.put(key, target);
            }
        }
        Map<String, Integer> occurrences = new HashMap<>();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<TemplateModels.ExcelRegion> resolved = new ArrayList<>();
        for (var region : regions.values()) {
            if (!Set.of("shift", "fixed").contains(region.growth())) throw StructuredContent.error("扩展空间设置无效");
            String base = "data_" + region.field() + "_" + region.column();
            int sequence = occurrences.merge(base, 1, Integer::sum);
            String name = region.rangeName() == null || region.rangeName().isBlank() ? base + (sequence == 1 ? "" : "_" + sequence) : region.rangeName();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,254}") || name.matches("(?i)[A-Z]{1,3}[1-9][0-9]*") || Set.of("R", "C").contains(name.toUpperCase(Locale.ROOT)) || !names.add(name)) throw StructuredContent.error("公式引用名称无效或重复：" + name);
            resolved.add(new TemplateModels.ExcelRegion(region.sheet(), region.field(), region.startCell(), region.direction(), region.column(), region.growth(), name));
        }
        return new TemplateModels.Spec(spec.fields(), spec.locations(), spec.defaultStyle(), spec.sheetStyles(), resolved);
    }
    private String regionKey(TemplateModels.ExcelRegion region) { return region.sheet() + "\0" + region.field() + "\0" + region.column() + "\0" + region.startCell(); }

    private void scanBody(IBody body, String prefix, Map<String, TemplateModels.Field> fields, List<TemplateModels.Location> locations) {
        int p = 0, t = 0;
        for (var element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph para) {
                if (!(body instanceof XWPFTableCell) && rowKey(para.getText()) != null) throw StructuredContent.error("明细标记必须放在表格样板行内");
                scan(para.getText(), prefix + "/段落" + p++, "", fields, locations);
            }
            if (element instanceof XWPFTable table) {
                if (body instanceof XWPFTableCell && table.getText().contains("{{")) throw StructuredContent.error("第一版暂不支持嵌套表格中的填写标记");
                int r = 0;
                for (var row : table.getRows()) {
                    int c = 0;
                    for (var cell : row.getTableCells()) scanBody(cell, prefix + "/表格" + t + "/行" + r + "/列" + c++, fields, locations);
                    r++;
                }
                t++;
            }
        }
    }
    private void rejectMacros(org.apache.poi.openxml4j.opc.OPCPackage pack) throws org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        if (pack.getParts().stream().anyMatch(part -> part.getPartName().getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin") || part.getContentType().toLowerCase(Locale.ROOT).contains("macroenabled"))) throw StructuredContent.error("不支持含宏的模板，请另存为无宏的 docx / xlsx 文件");
    }
    private void scan(String text, String position, String sheet, Map<String, TemplateModels.Field> fields, List<TemplateModels.Location> locations) {
        if (TOKEN.matcher(text).replaceAll("").contains("{{")) throw StructuredContent.error("填写标记格式不正确：" + position);
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1), column = matcher.group(2);
            var old = fields.get(key);
            if (column == null) {
                if (old != null && "table".equals(old.type())) throw StructuredContent.error("同一字段不能同时用作普通文字和重复表格：" + key);
                fields.putIfAbsent(key, field(key, "text", List.of()));
            } else {
                if (old != null && !"table".equals(old.type())) throw StructuredContent.error("重复区域字段冲突：" + key);
                List<TemplateModels.Field> columns = new ArrayList<>(old == null ? List.of() : old.columns());
                if (columns.stream().noneMatch(it -> it.key().equals(column))) columns.add(field(column, "text", List.of()));
                fields.put(key, field(key, "table", columns));
            }
            String id = position + ":" + key + (column == null ? "" : "." + column);
            if (locations.stream().noneMatch(it -> it.id().equals(id))) locations.add(new TemplateModels.Location(id, key, position + " · " + (column == null ? key : column), sheet, Map.of()));
        }
    }
    private TemplateModels.Field field(String key, String type, List<TemplateModels.Field> columns) {
        return new TemplateModels.Field(key, key, type, true, 20000, 200, "", columns);
    }

    public byte[] render(byte[] bytes, String kind, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Map<String, Object>> overrides) {
        StructuredContent.validate(data, spec.fields());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if ("word".equals(kind)) {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    renderBody(doc, "正文", spec, data, overrides);
                    for (int i = 0; i < doc.getHeaderList().size(); i++) renderBody(doc.getHeaderList().get(i), "页眉" + i, spec, data, overrides);
                    for (int i = 0; i < doc.getFooterList().size(); i++) renderBody(doc.getFooterList().get(i), "页脚" + i, spec, data, overrides);
                    doc.write(output);
                }
            } else {
                try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    Map<String, XSSFCellStyle> styleCache = new HashMap<>();
                    var configuredRegions = resolveExcelRegions(spec).excelRegions();
                    var tables = spec.fields().stream().filter(it -> "table".equals(it.type())).map(TemplateModels.Field::key).collect(java.util.stream.Collectors.toSet());
                    // 标量和样板样式使用原件位置；布局移动后不再次扫描业务内容中的大括号。
                    for (var sheet : book) for (var row : sheet) renderExcelRow((XSSFRow) row, row.getRowNum(), spec, data, Map.of(), overrides, styleCache, tables);
                    for (var region : configuredRegions) {
                        var sample = new org.apache.poi.ss.util.CellReference(region.startCell());
                        var cell = book.getSheet(region.sheet()).getRow(sample.getRow()).getCell(sample.getCol());
                        applyCellStyle(cell, style(spec, region.sheet() + "!" + region.startCell(), region.field() + "." + region.column(), region.sheet(), overrides), styleCache);
                    }
                    var layout = new ExcelTemplateLayout(book, configuredRegions, data);
                    var movedRegions = layout.prepare();
                    var movedSpec = layout.relocate(spec, movedRegions);
                    var movedOverrides = layout.relocateOverrides(spec, overrides);
                    for (var sheet : book) {
                        var regions = movedRegions.stream().filter(region -> region.sheet().equals(sheet.getSheetName())).toList();
                        renderIndependentRegions((XSSFSheet) sheet, movedSpec, data, movedOverrides, styleCache, regions);
                        fitDetailRows((XSSFSheet) sheet, movedSpec, data, movedOverrides, regions);
                    }
                    ExcelFormulaCalculation.calculate(book);
                    book.write(output);
                }
            }
            return output.toByteArray();
        } catch (com.agentum.shared.api.ApiException ex) {
            log.warn("文件模板生成被拒绝 kind={} errorCode={} tenantId={} userId={} requestId={}", kind, ex.getCode(), org.slf4j.MDC.get("tenantId"), org.slf4j.MDC.get("userId"), com.agentum.shared.api.RequestIds.current());
            throw ex;
        } catch (Exception ex) {
            log.error("文件模板生成异常 kind={} exceptionType={} tenantId={} userId={} requestId={}", kind, ex.getClass().getSimpleName(), org.slf4j.MDC.get("tenantId"), org.slf4j.MDC.get("userId"), com.agentum.shared.api.RequestIds.current());
            throw StructuredContent.error("模板填充失败，请检查填写位置、内容结构和格式配置");
        }
    }

    private Map<String, Object> style(TemplateModels.Spec spec, String position, String field, String sheet, Map<String, Map<String, Object>> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (spec.defaultStyle() != null) result.putAll(spec.defaultStyle());
        if (spec.sheetStyles() != null && spec.sheetStyles().get(sheet) != null) result.putAll(spec.sheetStyles().get(sheet));
        String id = position + ":" + field;
        spec.locations().stream().filter(it -> it.id().equals(id)).findFirst().ifPresent(it -> { if (it.style() != null) result.putAll(it.style()); });
        if (overrides != null && overrides.get(id) != null) result.putAll(overrides.get(id));
        return result;
    }
    private TemplateModels.Field field(TemplateModels.Spec spec, String name) { return spec.fields().stream().filter(it -> it.key().equals(name)).findFirst().orElseThrow(); }
    private String text(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) return String.join("\n", list.stream().map(this::text).toList());
        return value.toString();
    }
    private String replace(String source, Map<String, Object> data, Map<String, Object> row) {
        Matcher matcher = TOKEN.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = matcher.group(2) == null ? data.get(matcher.group(1)) : row.get(matcher.group(2));
            matcher.appendReplacement(result, Matcher.quoteReplacement(text(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> data, String key) { return data.get(key) instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    private String rowKey(String text) {
        Matcher matcher = TOKEN.matcher(text);
        String key = null;
        while (matcher.find()) if (matcher.group(2) != null) {
            if (key != null && !key.equals(matcher.group(1))) throw StructuredContent.error("同一样板行只能绑定一个明细列表");
            key = matcher.group(1);
        }
        return key;
    }

    private void renderBody(IBody body, String prefix, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Map<String, Object>> overrides) throws IOException {
        int p = 0, t = 0;
        for (var element : new ArrayList<>(body.getBodyElements())) {
            if (element instanceof XWPFParagraph para) renderParagraph(para, prefix + "/段落" + p++, spec, data, Map.of(), overrides);
            if (element instanceof XWPFTable table) {
                int originalIndex = 0, offset = 0;
                for (var row : new ArrayList<>(table.getRows())) {
                    String key = rowKey(row.getTableCells().stream().map(XWPFTableCell::getText).reduce("", (a,b) -> a + b));
                    int index = originalIndex + offset;
                    if (key == null) renderWordRow(row, prefix + "/表格" + t + "/行" + originalIndex, spec, data, Map.of(), overrides);
                    else {
                        // 跨行合并、嵌套重复区域必须先拒绝，不能生成结构损坏的正式文件。
                        if (row.getCtRow().xmlText().contains("vMerge") || row.getTableCells().stream().anyMatch(c -> !c.getTables().isEmpty())) throw StructuredContent.error("重复样板行暂不支持跨行合并或嵌套表格");
                        CTRow sample = (CTRow) row.getCtRow().copy();
                        var items = rows(data, key);
                        if (items.isEmpty()) {
                            Map<String, Object> blank = new LinkedHashMap<>();
                            for (var column : field(spec, key).columns()) blank.put(column.key(), null);
                            items = List.of(blank);
                        }
                        for (int i = 0; i < items.size(); i++) {
                            XWPFTableRow copy = new XWPFTableRow((CTRow) sample.copy(), table);
                            renderWordRow(copy, prefix + "/表格" + t + "/行" + originalIndex, spec, data, items.get(i), overrides);
                            table.addRow(copy, index + i);
                        }
                        table.removeRow(index + items.size());
                        offset += items.size() - 1;
                    }
                    originalIndex++;
                }
                t++;
            }
        }
    }
    private void renderWordRow(XWPFTableRow row, String prefix, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Object> item, Map<String, Map<String, Object>> overrides) throws IOException {
        int c = 0;
        for (var cell : row.getTableCells()) {
            int p = 0;
            for (var para : new ArrayList<>(cell.getParagraphs())) {
                String position = prefix + "/列" + c + "/段落" + p++;
                Matcher marker = TOKEN.matcher(para.getText());
                if (marker.find()) {
                    var format = style(spec, position, marker.group(1) + (marker.group(2) == null ? "" : "." + marker.group(2)), "", overrides);
                    applyWordCellStyle(cell, format);
                }
                renderParagraph(para, position, spec, data, item, overrides);
            }
            c++;
        }
    }
    private void renderParagraph(XWPFParagraph para, String position, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Object> row, Map<String, Map<String, Object>> overrides) throws IOException {
        String source = para.getText();
        Matcher matcher = TOKEN.matcher(source);
        if (!matcher.find()) return;
        String key = matcher.group(1), column = matcher.group(2);
        var definition = field(spec, key);
        Map<String, Object> format = style(spec, position, key + (column == null ? "" : "." + column), "", overrides);
        if (Set.of("paragraphs", "markdown").contains(definition.type()) && column == null) {
            if (!source.strip().equals(matcher.group())) throw StructuredContent.error("多段正文或 Markdown 标记必须独占段落：" + key);
            if ("markdown".equals(definition.type())) {
                renderMarkdown(para, text(data.get(key)), format);
            } else {
                Object raw = data.get(key);
                List<?> parts = raw instanceof List<?> list ? list : List.of("");
                if (parts.isEmpty()) parts = List.of("");
                CTP sample = (CTP) para.getCTP().copy();
                setParagraph(para, text(parts.get(0)), format);
                var cursor = para.getCTP().newCursor();
                cursor.toEndToken(); cursor.toNextToken();
                for (int i = 1; i < parts.size(); i++) {
                    XWPFParagraph next = para.getBody().insertNewParagraph(cursor);
                    next.getCTP().set(sample);
                    next = new XWPFParagraph(next.getCTP(), para.getBody());
                    setParagraph(next, text(parts.get(i)), format);
                    cursor.dispose(); cursor = next.getCTP().newCursor(); cursor.toEndToken(); cursor.toNextToken();
                }
                cursor.dispose();
            }
            return;
        }
        if (column != null && row.isEmpty()) throw StructuredContent.error("明细标记必须放在表格样板行中");
        // 以段落文字偏移定位标记；只改标记覆盖的 Run，保留前后固定文字各自的字体。
        List<XWPFRun> runs = new ArrayList<>(para.getRuns());
        String joined = runs.stream().map(XWPFRun::text).reduce("", String::concat);
        if (!joined.equals(source)) throw StructuredContent.error("填写标记不能位于超链接、域或不支持的文字对象中");
        List<int[]> spans = new ArrayList<>();
        matcher = TOKEN.matcher(source);
        while (matcher.find()) spans.add(new int[]{matcher.start(), matcher.end()});
        for (int s = spans.size() - 1; s >= 0; s--) {
            int start = spans.get(s)[0], end = spans.get(s)[1], offset = 0;
            runs = new ArrayList<>(para.getRuns());
            int first = -1, last = -1, localStart = 0, localEnd = 0;
            for (int i = 0; i < runs.size(); i++) {
                int finish = offset + runs.get(i).text().length();
                if (first < 0 && finish > start) { first = i; localStart = start - offset; }
                if (finish >= end) { last = i; localEnd = end - offset; break; }
                offset = finish;
            }
            if (first < 0 || last < first) throw StructuredContent.error("填写标记的文字范围无法定位");
            XWPFRun firstRun = runs.get(first), lastRun = runs.get(last);
            String before = firstRun.text().substring(0, localStart), after = lastRun.text().substring(localEnd);
            CTRPr runStyle = firstRun.getCTR().isSetRPr() ? (CTRPr) firstRun.getCTR().getRPr().copy() : null;
            String token = source.substring(start, end);
            Matcher match = TOKEN.matcher(token); match.find();
            String tokenKey = match.group(1) + (match.group(2) == null ? "" : "." + match.group(2));
            var localFormat = style(spec, position, tokenKey, "", overrides);
            setRunText(firstRun, before);
            if (last == first) {
                XWPFRun suffix = para.insertNewRun(first + 1);
                if (runStyle != null) suffix.getCTR().setRPr((CTRPr) runStyle.copy());
                setRunText(suffix, after);
            } else {
                setRunText(lastRun, after);
                for (int i = last - 1; i > first; i--) para.removeRun(i);
            }
            XWPFRun inserted = para.insertNewRun(first + 1);
            if (runStyle != null) inserted.getCTR().setRPr(runStyle);
            setRunText(inserted, replace(token, data, row));
            applyRunStyle(inserted, localFormat);
            applyParagraphLayout(para, localFormat);
        }
    }

    private void setParagraph(XWPFParagraph para, String value, Map<String, Object> style) {
        CTRPr sample = para.getRuns().isEmpty() || !para.getRuns().get(0).getCTR().isSetRPr() ? null : (CTRPr) para.getRuns().get(0).getCTR().getRPr().copy();
        while (!para.getRuns().isEmpty()) para.removeRun(0);
        var run = para.createRun();
        if (sample != null) run.getCTR().setRPr(sample);
        setRunText(run, value);
        applyParagraphStyle(para, style);
    }
    private void setRunText(XWPFRun run, String value) {
        while (run.getCTR().sizeOfTArray() > 0) run.getCTR().removeT(0);
        while (run.getCTR().sizeOfBrArray() > 0) run.getCTR().removeBr(0);
        String[] lines = value.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) { if (i > 0) run.addBreak(); run.setText(lines[i]); }
    }
    private void renderMarkdown(XWPFParagraph target, String value, Map<String, Object> style) throws IOException {
        if (value.isBlank()) { setParagraph(target, "", style); return; }
        byte[] generated = markdown.render(value, DocumentDeliveryStyle.from(Map.of()));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(generated))) {
            var cursor = target.getCTP().newCursor();
            for (var element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph source) {
                    var p = target.getBody().insertNewParagraph(cursor);
                    p.getCTP().set(source.getCTP());
                    p = new XWPFParagraph(p.getCTP(), target.getBody());
                    if (target.getCTP().isSetPPr()) p.getCTP().setPPr((CTPPr) target.getCTP().getPPr().copy());
                    for (var run : p.getRuns()) {
                        boolean bold = run.isBold(), italic = run.isItalic();
                        if (!target.getRuns().isEmpty() && target.getRuns().get(0).getCTR().isSetRPr()) run.getCTR().setRPr((CTRPr) target.getRuns().get(0).getCTR().getRPr().copy());
                        if (bold) run.setBold(true); if (italic) run.setItalic(true);
                    }
                    applyParagraphStyle(p, style);
                    cursor.dispose(); cursor = p.getCTP().newCursor(); cursor.toEndToken(); cursor.toNextToken();
                } else if (element instanceof XWPFTable source) {
                    var table = target.getBody().insertNewTbl(cursor);
                    table.getCTTbl().set(source.getCTTbl());
                    // 重新包装 XML，避免 POI 缓存仍指向插入时的空表格。
                    var wrapped = new XWPFTable(table.getCTTbl(), target.getBody());
                    for (var row : wrapped.getRows()) for (var cell : row.getTableCells()) for (var p : cell.getParagraphs()) applyParagraphStyle(p, style);
                    cursor.dispose(); cursor = table.getCTTbl().newCursor(); cursor.toEndToken(); cursor.toNextToken();
                }
            }
            cursor.dispose();
        }
        // 渲染块已经插入原标记之前，移除样板段，避免多出一个带段距的空段落。
        try (var cursor = target.getCTP().newCursor()) { cursor.removeXml(); }
    }
    private void applyParagraphStyle(XWPFParagraph p, Map<String, Object> style) {
        applyParagraphLayout(p, style);
        for (var run : p.getRuns()) applyRunStyle(run, style);
    }
    private void applyParagraphLayout(XWPFParagraph p, Map<String, Object> style) {
        if (style.containsKey("alignment")) p.setAlignment(ParagraphAlignment.valueOf(style.get("alignment").toString().toUpperCase(Locale.ROOT)));
        if (style.containsKey("firstLineChars")) {
            var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            var ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
            if (ind.isSetHanging()) ind.unsetHanging();
            if (ind.isSetHangingChars()) ind.unsetHangingChars();
            ind.setFirstLineChars(BigInteger.valueOf(Math.round(number(style, "firstLineChars") * 100)));
            // 同时写入磅值回退，浏览器预览对字符单位支持不一致；Office 以字符单位为准。
            ind.setFirstLine(BigInteger.valueOf(Math.round(number(style, "firstLineChars") * number(style, "fontSize", 12) * 20)));
        }
        if (style.containsKey("leftIndentCm")) p.setIndentationLeft((int) (number(style, "leftIndentCm") * 567));
        if (style.containsKey("spacingBefore")) p.setSpacingBefore((int) (number(style, "spacingBefore") * 20));
        if (style.containsKey("spacingAfter")) p.setSpacingAfter((int) (number(style, "spacingAfter") * 20));
        if (style.containsKey("lineSpacing")) p.setSpacingBetween(number(style, "lineSpacing"));
    }
    private void applyRunStyle(XWPFRun run, Map<String, Object> style) {
        if (style.containsKey("fontFamily")) {
            String font = style.get("fontFamily").toString();
            run.setFontFamily(font); run.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
        }
        if (style.containsKey("fontSize")) run.setFontSize(number(style, "fontSize"));
        if (style.containsKey("bold")) run.setBold(Boolean.TRUE.equals(style.get("bold")));
        if (style.containsKey("italic")) run.setItalic(Boolean.TRUE.equals(style.get("italic")));
        if (style.containsKey("color")) run.setColor(color(style.get("color")));
        if (style.containsKey("backgroundColor")) {
            var pr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
            (pr.sizeOfShdArray() > 0 ? pr.getShdArray(0) : pr.addNewShd()).setFill(color(style.get("backgroundColor")));
        }
    }
    private void applyWordCellStyle(XWPFTableCell cell, Map<String, Object> style) {
        var pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        if (style.containsKey("backgroundColor")) (pr.isSetShd() ? pr.getShd() : pr.addNewShd()).setFill(color(style.get("backgroundColor")));
        if (style.containsKey("verticalAlignment")) cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.valueOf(style.get("verticalAlignment").toString().toUpperCase(Locale.ROOT)));
        if (style.containsKey("cellPaddingPt")) {
            var margin = pr.isSetTcMar() ? pr.getTcMar() : pr.addNewTcMar();
            var width = BigInteger.valueOf((long) (number(style, "cellPaddingPt") * 20));
            for (var value : List.of(margin.isSetTop() ? margin.getTop() : margin.addNewTop(), margin.isSetBottom() ? margin.getBottom() : margin.addNewBottom(), margin.isSetLeft() ? margin.getLeft() : margin.addNewLeft(), margin.isSetRight() ? margin.getRight() : margin.addNewRight())) { value.setType(STTblWidth.DXA); value.setW(width); }
        }
        if (style.containsKey("border")) {
            var borders = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
            for (var border : List.of(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), borders.isSetRight() ? borders.getRight() : borders.addNewRight())) { border.setVal(Boolean.TRUE.equals(style.get("border")) ? STBorder.SINGLE : STBorder.NIL); border.setSz(BigInteger.valueOf(4)); }
        }
    }

    private void renderIndependentRegions(XSSFSheet sheet, TemplateModels.Spec spec, Map<String, Object> data,
        Map<String, Map<String, Object>> overrides, Map<String, XSSFCellStyle> styles, List<TemplateModels.ExcelRegion> regions) {
        List<ExcelDataRegionWriter.Region> writes = new ArrayList<>();
        for (var region : regions) {
            var definition = field(spec, region.field());
            var column = definition.columns().stream().filter(it -> it.key().equals(region.column())).findFirst().orElseThrow(() -> StructuredContent.error("明细字段不存在"));
            var start = new org.apache.poi.ss.util.CellReference(region.startCell());
            var row = sheet.getRow(start.getRow());
            var cell = row.getCell(start.getCol());
            applyCellStyle(cell, style(spec, sheet.getSheetName() + "!" + cell.getAddress(), region.field() + "." + column.key(), sheet.getSheetName(), overrides), styles);
            var items = rows(data, region.field());
            String content = cell.getStringCellValue();
            var marker = TOKEN.matcher(content);
            boolean standalone = marker.find() && content.strip().equals(marker.group());
            if (!standalone) {
                // 包含固定前后缀的单元格按整条记录插值，不丢失原件中的业务文字。
                List<Map<String, Object>> formatted = new ArrayList<>();
                for (var item : items) formatted.add(Map.of(column.key(), replace(content, data, item)));
                if (items.isEmpty()) formatted.add(Map.of(column.key(), replace(content, data, Map.of())));
                items = formatted;
            }
            writes.add(new ExcelDataRegionWriter.Region(region.startCell(), false,
                List.of(new ExcelDataRegionWriter.Column(column.key(), column.label(), standalone ? column.type() : "text", null)), items, region.direction()));
        }
        ExcelDataRegionWriter.write(sheet, writes);
    }

    private void fitDetailRows(XSSFSheet sheet, TemplateModels.Spec spec, Map<String, Object> data,
        Map<String, Map<String, Object>> overrides, List<TemplateModels.ExcelRegion> regions) {
        Map<Integer, Float> heights = new HashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        for (var region : regions) {
            var start = new org.apache.poi.ss.util.CellReference(region.startCell());
            var format = style(spec, sheet.getSheetName() + "!" + region.startCell(), region.field() + "." + region.column(), sheet.getSheetName(), overrides);
            String mode = Objects.toString(format.getOrDefault("rowHeightMode", format.containsKey("rowHeight") ? "fixed" : "auto"));
            for (int i = 0; i < Math.max(1, rows(data, region.field()).size()); i++) {
                int index = start.getRow() + (region.horizontal() ? 0 : i);
                var row = sheet.getRow(index);
                if (row == null) continue;
                if ("inherit".equals(mode)) {
                    var sample = sheet.getRow(start.getRow());
                    heights.merge(index, sample.getHeightInPoints(), Math::max);
                    continue;
                }
                if ("fixed".equals(mode) && format.containsKey("rowHeight")) {
                    heights.merge(index, (float) number(format, "rowHeight"), Math::max);
                    continue;
                }
                var cell = row.getCell(start.getCol() + (region.horizontal() ? i : 0));
                if (cell == null) continue;
                var cellStyle = cell.getCellStyle();
                double font = cellStyle.getFont().getFontHeightInPoints();
                String value = formatter.formatCellValue(cell);
                double width = Math.max(10, sheet.getColumnWidthInPixels(cell.getColumnIndex()) - 12);
                int lines = 0;
                for (String line : value.split("\n", -1)) {
                    double characters = line.codePoints().mapToDouble(c -> c > 255 ? 1 : .55).sum();
                    lines += cellStyle.getWrapText() ? Math.max(1, (int) Math.ceil(characters * font * 1.333 / width)) : 1;
                }
                heights.merge(index, (float) Math.min(400, Math.max(20, lines * font * 1.35 + 5)), Math::max);
            }
        }
        heights.forEach((index, height) -> sheet.getRow(index).setHeightInPoints(height));
    }

    private void renderExcelRow(XSSFRow row, int originalRow, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Object> item, Map<String, Map<String, Object>> overrides, Map<String, XSSFCellStyle> styleCache) {
        renderExcelRow(row, originalRow, spec, data, item, overrides, styleCache, Set.of());
    }
    private void renderExcelRow(XSSFRow row, int originalRow, TemplateModels.Spec spec, Map<String, Object> data, Map<String, Object> item, Map<String, Map<String, Object>> overrides, Map<String, XSSFCellStyle> styleCache, Set<String> excluded) {
        for (var raw : row) {
            XSSFCell cell = (XSSFCell) raw;
            if (cell.getCellType() != CellType.STRING) continue;
            String source = cell.getStringCellValue();
            Matcher matcher = TOKEN.matcher(source);
            if (!matcher.find()) continue;
            String key = matcher.group(1), column = matcher.group(2);
            if (excluded.contains(key)) continue;
            var definition = field(spec, key);
            if (column != null) definition = definition.columns().stream().filter(it -> it.key().equals(column)).findFirst().orElseThrow();
            String address = new org.apache.poi.ss.util.CellReference(originalRow, cell.getColumnIndex()).formatAsString();
            var format = style(spec, row.getSheet().getSheetName() + "!" + address, key + (column == null ? "" : "." + column), row.getSheet().getSheetName(), overrides);
            Object value = column == null ? data.get(key) : item.get(column);
            if (source.strip().equals(matcher.group())) {
                if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else if (value instanceof Boolean bool) cell.setCellValue(bool);
                else if ("date".equals(definition.type())) {
                    if (value == null || value.toString().isBlank()) cell.setBlank(); else cell.setCellValue(java.time.LocalDate.parse(value.toString()));
                } else {
                    if ("markdown".equals(definition.type())) throw StructuredContent.error("Excel 单元格请使用文字或段落组，Markdown 表格应绑定明细区域");
                    cell.setCellValue(text(value));
                }
            } else {
                if (value instanceof List<?> || value instanceof Map<?, ?>) throw StructuredContent.error("复合内容必须独占单元格：" + key);
                cell.setCellValue(replace(source, data, item));
            }
            if ("date".equals(definition.type()) && cell.getCellStyle().getDataFormat() == 0 && !format.containsKey("numberFormat")) format.put("numberFormat", "yyyy-mm-dd");
            applyCellStyle(cell, format, styleCache);
        }
    }
    private void applyCellStyle(XSSFCell cell, Map<String, Object> format, Map<String, XSSFCellStyle> styleCache) {
        if (format.isEmpty()) return;
        XSSFWorkbook book = cell.getSheet().getWorkbook();
        String cacheKey = cell.getCellStyle().getIndex() + ":" + format.toString();
        if (format.containsKey("rowHeight") && (!format.containsKey("rowHeightMode") || "fixed".equals(format.get("rowHeightMode")))) cell.getRow().setHeightInPoints((float) number(format, "rowHeight"));
        if (format.containsKey("columnWidth")) cell.getSheet().setColumnWidth(cell.getColumnIndex(), (int) (number(format, "columnWidth") * 256));
        if (styleCache.containsKey(cacheKey)) { cell.setCellStyle(styleCache.get(cacheKey)); return; }
        XSSFCellStyle style = book.createCellStyle(); style.cloneStyleFrom(cell.getCellStyle());
        XSSFFont font = book.createFont(); font.getCTFont().set(style.getFont().getCTFont());
        if (format.containsKey("fontFamily")) font.setFontName(format.get("fontFamily").toString());
        if (format.containsKey("fontSize")) font.setFontHeight(number(format, "fontSize"));
        if (format.containsKey("bold")) font.setBold(Boolean.TRUE.equals(format.get("bold")));
        if (format.containsKey("italic")) font.setItalic(Boolean.TRUE.equals(format.get("italic")));
        if (format.containsKey("color")) font.setColor(new XSSFColor(hex(format.get("color")), null));
        style.setFont(font);
        if (format.containsKey("backgroundColor")) { style.setFillForegroundColor(new XSSFColor(hex(format.get("backgroundColor")), null)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); }
        if (format.containsKey("alignment")) style.setAlignment("both".equals(format.get("alignment")) ? HorizontalAlignment.JUSTIFY : HorizontalAlignment.valueOf(format.get("alignment").toString().toUpperCase(Locale.ROOT)));
        if (format.containsKey("wrapText")) style.setWrapText(Boolean.TRUE.equals(format.get("wrapText")));
        if (format.containsKey("verticalAlignment")) style.setVerticalAlignment(VerticalAlignment.valueOf(format.get("verticalAlignment").toString().toUpperCase(Locale.ROOT)));
        if (format.containsKey("indent")) style.setIndention((short) number(format, "indent"));
        if (format.containsKey("numberFormat")) style.setDataFormat(book.createDataFormat().getFormat(format.get("numberFormat").toString()));
        if (format.containsKey("border")) { var border = Boolean.TRUE.equals(format.get("border")) ? BorderStyle.THIN : BorderStyle.NONE; style.setBorderBottom(border); style.setBorderTop(border); style.setBorderLeft(border); style.setBorderRight(border); }
        if (format.containsKey("rowHeight") && (!format.containsKey("rowHeightMode") || "fixed".equals(format.get("rowHeightMode")))) cell.getRow().setHeightInPoints((float) number(format, "rowHeight"));
        if (format.containsKey("columnWidth")) cell.getSheet().setColumnWidth(cell.getColumnIndex(), (int) (number(format, "columnWidth") * 256));
        cell.setCellStyle(style);
        styleCache.put(cacheKey, style);
    }
    private static double number(Map<String, Object> data, String key) { return Double.parseDouble(data.get(key).toString()); }
    private static double number(Map<String, Object> data, String key, double fallback) { return data.containsKey(key) ? number(data, key) : fallback; }
    private static String color(Object value) { return value.toString().replace("#", ""); }
    private static byte[] hex(Object value) { return java.util.HexFormat.of().parseHex(color(value)); }
}
