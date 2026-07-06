package com.agentum.delivery.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 面向 AI 输出的宽容 Excel 渲染器。
 *
 * <p>Excel 交付的业务边界和 Word 不同：模型可能输出 Markdown 表格、列表、键值块或普通文本。
 * 渲染器会尽量识别结构并套用列/行/单元格规则；识别或类型转换失败时回退为纯文本，避免模型轻微格式漂移导致正式交付中断。</p>
 */
@Component
public class ExcelWorkbookRenderer {

    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile("^\\s*\\d+[.)、]\\s+");
    private static final Pattern BULLET_LIST_PREFIX = Pattern.compile("^\\s*[-*+]\\s+");
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)$");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy.M.d"),
        DateTimeFormatter.ofPattern("yyyy年M月d日")
    );
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExcelWorkbookRenderResult render(List<ExcelSheetRenderSpec> sheets) {
        List<ExcelSheetRenderSpec> effectiveSheets = sheets == null || sheets.isEmpty()
            ? List.of(new ExcelSheetRenderSpec("交付结果", "暂无内容。", "A1", "text", Map.of(), List.of(), List.of(), List.of()))
            : sheets;
        List<String> warnings = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Set<String> usedSheetNames = new LinkedHashSet<>();
            for (int index = 0; index < effectiveSheets.size(); index++) {
                ExcelSheetRenderSpec spec = effectiveSheets.get(index);
                String sheetName = uniqueSheetName(sanitizeSheetName(spec.name(), index + 1), usedSheetNames);
                usedSheetNames.add(sheetName);
                renderSheet(workbook, spec, sheetName, warnings);
            }
            workbook.write(output);
            return new ExcelWorkbookRenderResult(output.toByteArray(), warnings, effectiveSheets.size());
        } catch (IOException exception) {
            throw new IllegalStateException("XLSX 文件生成失败", exception);
        }
    }

    private void renderSheet(XSSFWorkbook workbook, ExcelSheetRenderSpec spec, String sheetName, List<String> warnings) {
        var sheet = workbook.createSheet(sheetName);
        Position start = parseCellRef(spec.startCell(), warnings, sheetName);
        int rowIndex = start.row();
        List<ContentBlock> blocks = parseBlocks(spec.body(), warnings, sheetName);
        boolean autoFilterSet = false;
        int maxColumn = 0;
        for (ContentBlock block : blocks) {
            switch (block.type()) {
                case BLANK -> rowIndex++;
                case TEXT -> {
                    Row row = getOrCreateRow(sheet, rowIndex++);
                    writeTextCell(workbook, row, start.column(), block.text(), spec, null, null);
                    maxColumn = Math.max(maxColumn, start.column());
                }
                case HEADING -> {
                    Row row = getOrCreateRow(sheet, rowIndex++);
                    Map<String, Object> headingStyle = new LinkedHashMap<>();
                    headingStyle.put("bold", true);
                    headingStyle.put("fontSize", block.headingLevel() <= 1 ? 14 : 12);
                    writeTextCell(workbook, row, start.column(), block.text(), spec, headingStyle, null);
                    maxColumn = Math.max(maxColumn, start.column());
                }
                case TABLE -> {
                    TableRenderOutcome outcome = renderTable(workbook, sheet, rowIndex, start.column(), block, spec, !autoFilterSet);
                    rowIndex = outcome.nextRow();
                    maxColumn = Math.max(maxColumn, outcome.maxColumn());
                    autoFilterSet = autoFilterSet || outcome.autoFilterSet();
                }
            }
        }
        for (Map<String, Object> rule : spec.columnRules()) {
            int width = intValue(rule.get("width"), -1);
            if (width > 0) {
                String match = stringValue(rule.get("match"));
                if (match.isBlank()) {
                    continue;
                }
                // 没有稳定表头时不按猜测列序设置宽度，避免把动态文本区域拉坏。
            }
        }
        for (int column = 0; column <= Math.min(maxColumn, 30); column++) {
            sheet.autoSizeColumn(column);
            int currentWidth = sheet.getColumnWidth(column);
            sheet.setColumnWidth(column, Math.min(Math.max(currentWidth, 10 * 256), 40 * 256));
        }
    }

    private TableRenderOutcome renderTable(
        XSSFWorkbook workbook,
        org.apache.poi.ss.usermodel.Sheet sheet,
        int startRow,
        int startColumn,
        ContentBlock block,
        ExcelSheetRenderSpec spec,
        boolean allowAutoFilter
    ) {
        List<List<String>> rows = block.rows();
        if (rows.isEmpty()) {
            return new TableRenderOutcome(startRow, startColumn, false);
        }
        List<String> headers = block.hasHeader() ? rows.getFirst() : List.of();
        int maxColumn = startColumn;
        for (int rowOffset = 0; rowOffset < rows.size(); rowOffset++) {
            List<String> values = rows.get(rowOffset);
            Row row = getOrCreateRow(sheet, startRow + rowOffset);
            boolean header = block.hasHeader() && rowOffset == 0;
            boolean last = rowOffset == rows.size() - 1;
            for (int columnOffset = 0; columnOffset < values.size(); columnOffset++) {
                int columnIndex = startColumn + columnOffset;
                Map<String, Object> columnRule = header ? Map.of() : matchColumnRule(headers, columnOffset, spec.columnRules());
                Map<String, Object> rowRule = matchRowRule(values, rowOffset, header, last, spec.rowRules());
                Map<String, Object> cellRule = matchCellRule(startRow + rowOffset, columnIndex, spec.cellRules());
                String type = firstNonBlank(stringValue(cellRule.get("type")), stringValue(columnRule.get("type")), spec.defaultCellType(), "text");
                String format = firstNonBlank(stringValue(cellRule.get("format")), stringValue(columnRule.get("format")));
                Map<String, Object> style = mergeStyle(spec.tableStyle(), columnRule, rowRule, cellRule);
                if (header) {
                    style = mergeStyle(style, Map.of("bold", true, "backgroundColor", "grey"));
                }
                writeTypedCell(workbook, row, columnIndex, values.get(columnOffset), type, format, style, booleanValue(cellRule.get("allowFormula")));
                int width = intValue(columnRule.get("width"), -1);
                if (width > 0) {
                    sheet.setColumnWidth(columnIndex, Math.min(width, 80) * 256);
                }
                maxColumn = Math.max(maxColumn, columnIndex);
            }
        }
        boolean autoFilterSet = false;
        if (block.hasHeader() && booleanValue(spec.tableStyle().get("freezeHeader"))) {
            sheet.createFreezePane(0, startRow + 1);
        }
        if (allowAutoFilter && block.hasHeader() && booleanValue(spec.tableStyle().get("autoFilter"))) {
            sheet.setAutoFilter(new CellRangeAddress(startRow, startRow + rows.size() - 1, startColumn, maxColumn));
            autoFilterSet = true;
        }
        return new TableRenderOutcome(startRow + rows.size() + 1, maxColumn, autoFilterSet);
    }

    private List<ContentBlock> parseBlocks(String content, List<String> warnings, String sheetName) {
        String normalized = content == null || content.isBlank() ? "暂无内容。" : content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = List.of(normalized.split("\n", -1));
        List<ContentBlock> blocks = new ArrayList<>();
        for (int index = 0; index < lines.size();) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                blocks.add(ContentBlock.blank());
                index++;
                continue;
            }
            if (trimmed.startsWith("```")) {
                FencedBlock fenced = readFencedBlock(lines, index);
                blocks.add(parseFencedBlock(fenced, warnings, sheetName));
                index = fenced.nextIndex();
                continue;
            }
            if (isMarkdownTableStart(lines, index)) {
                List<List<String>> tableRows = new ArrayList<>();
                tableRows.add(splitPipeRow(lines.get(index)));
                index += 2;
                while (index < lines.size() && looksLikePipeRow(lines.get(index))) {
                    tableRows.add(splitPipeRow(lines.get(index)));
                    index++;
                }
                blocks.add(ContentBlock.table(tableRows, true));
                continue;
            }
            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0) {
                blocks.add(ContentBlock.heading(headingLevel, trimmed.substring(headingLevel).trim()));
                index++;
                continue;
            }
            if (isListLine(trimmed)) {
                List<String> items = new ArrayList<>();
                while (index < lines.size() && isListLine(lines.get(index).trim())) {
                    items.add(stripListPrefix(lines.get(index).trim()));
                    index++;
                }
                blocks.add(parseListItems(items));
                continue;
            }
            if (looksLikeKeyValueLine(trimmed)) {
                List<String> kvLines = new ArrayList<>();
                while (index < lines.size() && looksLikeKeyValueLine(lines.get(index).trim())) {
                    kvLines.add(lines.get(index).trim());
                    index++;
                }
                if (kvLines.size() >= 2) {
                    blocks.add(parseKeyValueLines(kvLines));
                    continue;
                }
                blocks.add(ContentBlock.text(kvLines.getFirst()));
                continue;
            }
            List<String> paragraph = new ArrayList<>();
            while (index < lines.size() && canJoinPlainLine(lines, index)) {
                paragraph.add(lines.get(index).trim());
                index++;
            }
            blocks.add(ContentBlock.text(String.join(" ", paragraph)));
        }
        return blocks;
    }

    private ContentBlock parseFencedBlock(FencedBlock fenced, List<String> warnings, String sheetName) {
        String language = fenced.language().toLowerCase(Locale.ROOT);
        String body = String.join("\n", fenced.lines());
        try {
            if ("csv".equals(language)) {
                return ContentBlock.table(parseDelimitedRows(body, ','), true);
            }
            if ("tsv".equals(language)) {
                return ContentBlock.table(parseDelimitedRows(body, '\t'), true);
            }
            if ("json".equals(language)) {
                return parseJsonBlock(body);
            }
        } catch (RuntimeException exception) {
            warnings.add("Sheet「" + sheetName + "」中的 " + language + " 代码块无法结构化解析，已按文本写入。");
        }
        return ContentBlock.text(body);
    }

    private ContentBlock parseJsonBlock(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isArray()) {
                if (root.isEmpty()) {
                    return ContentBlock.table(List.of(List.of("内容")), true);
                }
                JsonNode first = root.get(0);
                if (first != null && first.isObject()) {
                    Set<String> headers = new LinkedHashSet<>();
                    root.forEach(item -> item.fieldNames().forEachRemaining(headers::add));
                    List<List<String>> rows = new ArrayList<>();
                    rows.add(new ArrayList<>(headers));
                    root.forEach(item -> {
                        List<String> row = new ArrayList<>();
                        headers.forEach(header -> row.add(jsonText(item.get(header))));
                        rows.add(row);
                    });
                    return ContentBlock.table(rows, true);
                }
                if (first != null && first.isArray()) {
                    List<List<String>> rows = new ArrayList<>();
                    root.forEach(item -> {
                        List<String> row = new ArrayList<>();
                        item.forEach(cell -> row.add(jsonText(cell)));
                        rows.add(row);
                    });
                    return ContentBlock.table(rows, false);
                }
            }
            if (root.isObject()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("字段", "内容"));
                root.fieldNames().forEachRemaining(field -> rows.add(List.of(field, jsonText(root.get(field)))));
                return ContentBlock.table(rows, true);
            }
            return ContentBlock.text(jsonText(root));
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON 解析失败", exception);
        }
    }

    private ContentBlock parseListItems(List<String> items) {
        List<Map<String, String>> parsedItems = items.stream().map(this::parseKeyValuePairs).toList();
        Set<String> headers = new LinkedHashSet<>();
        parsedItems.forEach(item -> headers.addAll(item.keySet()));
        if (!headers.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(headers));
            parsedItems.forEach(item -> {
                List<String> row = new ArrayList<>();
                headers.forEach(header -> row.add(item.getOrDefault(header, "")));
                rows.add(row);
            });
            return ContentBlock.table(rows, true);
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("内容"));
        items.forEach(item -> rows.add(List.of(item)));
        return ContentBlock.table(rows, true);
    }

    private ContentBlock parseKeyValueLines(List<String> lines) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("字段", "内容"));
        for (String line : lines) {
            int splitIndex = firstColonIndex(line);
            rows.add(List.of(line.substring(0, splitIndex).trim(), line.substring(splitIndex + 1).trim()));
        }
        return ContentBlock.table(rows, true);
    }

    private Map<String, String> parseKeyValuePairs(String item) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : item.split("[,，;；]")) {
            String segment = part.trim();
            int splitIndex = firstColonIndex(segment);
            if (splitIndex > 0 && splitIndex < segment.length() - 1) {
                result.put(segment.substring(0, splitIndex).trim(), segment.substring(splitIndex + 1).trim());
            }
        }
        return result;
    }

    private void writeTextCell(
        XSSFWorkbook workbook,
        Row row,
        int columnIndex,
        String value,
        ExcelSheetRenderSpec spec,
        Map<String, Object> stylePatch,
        Map<String, Object> cellRule
    ) {
        Map<String, Object> exactCellRule = cellRule == null ? matchCellRule(row.getRowNum(), columnIndex, spec.cellRules()) : cellRule;
        Map<String, Object> style = mergeStyle(stylePatch == null ? Map.of() : stylePatch, exactCellRule);
        String type = firstNonBlank(stringValue(exactCellRule.get("type")), spec.defaultCellType(), "text");
        String format = stringValue(exactCellRule.get("format"));
        writeTypedCell(workbook, row, columnIndex, value, type, format, style, booleanValue(exactCellRule.get("allowFormula")));
    }

    private void writeTypedCell(
        XSSFWorkbook workbook,
        Row row,
        int columnIndex,
        String value,
        String type,
        String format,
        Map<String, Object> styleConfig,
        boolean allowFormula
    ) {
        Cell cell = row.createCell(columnIndex);
        String text = value == null ? "" : value.trim();
        String normalizedType = type == null ? "text" : type.trim().toLowerCase(Locale.ROOT);
        boolean converted = switch (normalizedType) {
            case "number", "currency", "percent", "percentage" -> writeNumberCell(cell, text, normalizedType);
            case "date" -> writeDateCell(cell, text);
            case "datetime" -> writeDateTimeCell(cell, text);
            case "boolean", "bool" -> writeBooleanCell(cell, text);
            case "formula" -> writeFormulaCell(cell, text, allowFormula);
            default -> false;
        };
        if (!converted) {
            cell.setCellValue(safeText(text));
            normalizedType = "text";
        }
        cell.setCellStyle(createStyle(workbook, styleConfig, defaultFormat(normalizedType, format)));
    }

    private boolean writeNumberCell(Cell cell, String text, String type) {
        if (text.isBlank()) {
            cell.setBlank();
            return true;
        }
        String normalized = text.replace(",", "").replace("，", "").replace("¥", "").replace("￥", "").trim();
        boolean percent = "percent".equals(type) || "percentage".equals(type) || normalized.endsWith("%");
        normalized = normalized.replace("%", "");
        try {
            double number = Double.parseDouble(normalized);
            cell.setCellValue(percent ? number / 100.0 : number);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean writeDateCell(Cell cell, String text) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                cell.setCellValue(LocalDate.parse(text, formatter));
                return true;
            } catch (DateTimeParseException ignored) {
            }
        }
        return false;
    }

    private boolean writeDateTimeCell(Cell cell, String text) {
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                cell.setCellValue(LocalDateTime.parse(text, formatter));
                return true;
            } catch (DateTimeParseException ignored) {
            }
        }
        return false;
    }

    private boolean writeBooleanCell(Cell cell, String text) {
        if (List.of("true", "是", "yes", "y", "1").contains(text.toLowerCase(Locale.ROOT))) {
            cell.setCellValue(true);
            return true;
        }
        if (List.of("false", "否", "no", "n", "0").contains(text.toLowerCase(Locale.ROOT))) {
            cell.setCellValue(false);
            return true;
        }
        return false;
    }

    private boolean writeFormulaCell(Cell cell, String text, boolean allowFormula) {
        if (!allowFormula || text.isBlank()) {
            return false;
        }
        cell.setCellFormula(text.startsWith("=") ? text.substring(1) : text);
        return true;
    }

    private CellStyle createStyle(XSSFWorkbook workbook, Map<String, Object> styleConfig, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(readAlignment(styleConfig.get("horizontalAlignment")));
        if (!format.isBlank()) {
            DataFormat dataFormat = workbook.createDataFormat();
            style.setDataFormat(dataFormat.getFormat(format));
        }
        Font font = workbook.createFont();
        font.setBold(booleanValue(styleConfig.get("bold")));
        font.setItalic(booleanValue(styleConfig.get("italic")));
        int fontSize = intValue(styleConfig.get("fontSize"), 0);
        if (fontSize > 0) {
            font.setFontHeightInPoints((short) Math.min(fontSize, 72));
        }
        style.setFont(font);
        String background = stringValue(styleConfig.get("backgroundColor")).toLowerCase(Locale.ROOT);
        if (!background.isBlank()) {
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setFillForegroundColor(switch (background) {
                case "yellow" -> IndexedColors.LIGHT_YELLOW.getIndex();
                case "green" -> IndexedColors.LIGHT_GREEN.getIndex();
                case "red" -> IndexedColors.ROSE.getIndex();
                case "blue" -> IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex();
                default -> IndexedColors.GREY_25_PERCENT.getIndex();
            });
        }
        return style;
    }

    private HorizontalAlignment readAlignment(Object value) {
        return switch (stringValue(value).toLowerCase(Locale.ROOT)) {
            case "center" -> HorizontalAlignment.CENTER;
            case "right" -> HorizontalAlignment.RIGHT;
            default -> HorizontalAlignment.LEFT;
        };
    }

    private Map<String, Object> matchColumnRule(List<String> headers, int columnOffset, List<Map<String, Object>> rules) {
        if (headers.isEmpty() || columnOffset >= headers.size()) {
            return Map.of();
        }
        String header = headers.get(columnOffset);
        for (Map<String, Object> rule : rules) {
            String match = stringValue(rule.get("match"));
            if (!match.isBlank() && header.contains(match)) {
                return rule;
            }
        }
        return Map.of();
    }

    private Map<String, Object> matchRowRule(List<String> values, int rowOffset, boolean header, boolean last, List<Map<String, Object>> rules) {
        for (Map<String, Object> rule : rules) {
            String target = stringValue(rule.get("target"));
            if ("header".equals(target) && header) {
                return rule;
            }
            if ("last".equals(target) && last) {
                return rule;
            }
            if ("index".equals(target) && intValue(rule.get("index"), -1) == rowOffset + 1) {
                return rule;
            }
            if ("contains".equals(target)) {
                String text = stringValue(rule.get("text"));
                if (!text.isBlank() && values.stream().anyMatch(value -> value.contains(text))) {
                    return rule;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> matchCellRule(int rowIndex, int columnIndex, List<Map<String, Object>> rules) {
        String currentRef = cellRef(rowIndex, columnIndex);
        for (Map<String, Object> rule : rules) {
            if (currentRef.equalsIgnoreCase(stringValue(rule.get("cell")))) {
                return rule;
            }
        }
        return Map.of();
    }

    private Map<String, Object> mergeStyle(Map<String, Object>... maps) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> map : maps) {
            if (map != null) {
                merged.putAll(map);
            }
        }
        return merged;
    }

    private FencedBlock readFencedBlock(List<String> lines, int startIndex) {
        String marker = lines.get(startIndex).trim();
        String language = marker.length() > 3 ? marker.substring(3).trim() : "";
        List<String> body = new ArrayList<>();
        int index = startIndex + 1;
        while (index < lines.size() && !lines.get(index).trim().startsWith("```")) {
            body.add(lines.get(index));
            index++;
        }
        if (index < lines.size()) {
            index++;
        }
        return new FencedBlock(language, body, index);
    }

    private List<List<String>> parseDelimitedRows(String body, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : body.split("\\n")) {
            if (!line.trim().isBlank()) {
                rows.add(parseDelimitedLine(line, delimiter));
            }
        }
        return rows;
    }

    private List<String> parseDelimitedLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private boolean isMarkdownTableStart(List<String> lines, int index) {
        return index + 1 < lines.size()
            && looksLikePipeRow(lines.get(index))
            && looksLikePipeRow(lines.get(index + 1))
            && splitPipeRow(lines.get(index + 1)).stream().allMatch(cell -> TABLE_SEPARATOR_CELL.matcher(cell.trim()).matches());
    }

    private boolean looksLikePipeRow(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.contains("|") && trimmed.indexOf('|') != trimmed.lastIndexOf('|');
    }

    private List<String> splitPipeRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private boolean canJoinPlainLine(List<String> lines, int index) {
        String trimmed = lines.get(index).trim();
        return !trimmed.isBlank()
            && !trimmed.startsWith("```")
            && headingLevel(trimmed) == 0
            && !isMarkdownTableStart(lines, index)
            && !isListLine(trimmed)
            && !looksLikeKeyValueLine(trimmed);
    }

    private int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return count > 0 && count <= 6 && count < line.length() && Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    private boolean isListLine(String line) {
        return BULLET_LIST_PREFIX.matcher(line).find() || ORDERED_LIST_PREFIX.matcher(line).find();
    }

    private String stripListPrefix(String line) {
        return ORDERED_LIST_PREFIX.matcher(BULLET_LIST_PREFIX.matcher(line).replaceFirst("")).replaceFirst("").trim();
    }

    private boolean looksLikeKeyValueLine(String line) {
        int splitIndex = firstColonIndex(line);
        return splitIndex > 0 && splitIndex < line.length() - 1;
    }

    private int firstColonIndex(String text) {
        int cn = text.indexOf('：');
        int en = text.indexOf(':');
        if (cn < 0) {
            return en;
        }
        if (en < 0) {
            return cn;
        }
        return Math.min(cn, en);
    }

    private Row getOrCreateRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private Position parseCellRef(String cellRef, List<String> warnings, String sheetName) {
        Matcher matcher = CELL_REF_PATTERN.matcher(firstNonBlank(cellRef, "A1").trim());
        if (!matcher.matches()) {
            warnings.add("Sheet「" + sheetName + "」起始单元格不合法，已回退到 A1。");
            return new Position(0, 0);
        }
        return new Position(Integer.parseInt(matcher.group(2)) - 1, columnIndex(matcher.group(1)));
    }

    private int columnIndex(String letters) {
        int index = 0;
        for (char ch : letters.toUpperCase(Locale.ROOT).toCharArray()) {
            index = index * 26 + (ch - 'A' + 1);
        }
        return Math.max(0, index - 1);
    }

    private String cellRef(int rowIndex, int columnIndex) {
        int value = columnIndex + 1;
        StringBuilder letters = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            letters.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return letters + String.valueOf(rowIndex + 1);
    }

    private String sanitizeSheetName(String name, int index) {
        String value = name == null || name.isBlank() ? "Sheet" + index : name.trim();
        value = value.replaceAll("[\\\\/?*\\[\\]:\\p{Cntrl}]", "-").trim();
        if (value.isBlank()) {
            value = "Sheet" + index;
        }
        return value.length() > 31 ? value.substring(0, 31) : value;
    }

    private String uniqueSheetName(String baseName, Set<String> used) {
        if (!used.contains(baseName)) {
            return baseName;
        }
        for (int index = 2; index < 100; index++) {
            String suffix = "-" + index;
            String candidate = baseName.length() + suffix.length() > 31
                ? baseName.substring(0, 31 - suffix.length()) + suffix
                : baseName + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return baseName.substring(0, Math.min(26, baseName.length())) + "-" + System.nanoTime();
    }

    private String defaultFormat(String type, String configuredFormat) {
        if (configuredFormat != null && !configuredFormat.isBlank()) {
            return configuredFormat;
        }
        return switch (type) {
            case "number" -> "#,##0.00";
            case "currency" -> "¥#,##0.00";
            case "percent", "percentage" -> "0.00%";
            case "date" -> "yyyy-mm-dd";
            case "datetime" -> "yyyy-mm-dd hh:mm";
            default -> "";
        };
    }

    private String safeText(String text) {
        if (text.isBlank()) {
            return "";
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + text : text;
    }

    private String jsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "是".equals(text);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public record ExcelWorkbookRenderResult(byte[] bytes, List<String> warnings, int sheetCount) {
    }

    public record ExcelSheetRenderSpec(
        String name,
        String body,
        String startCell,
        String defaultCellType,
        Map<String, Object> tableStyle,
        List<Map<String, Object>> columnRules,
        List<Map<String, Object>> rowRules,
        List<Map<String, Object>> cellRules
    ) {
    }

    private record Position(int row, int column) {
    }

    private record FencedBlock(String language, List<String> lines, int nextIndex) {
    }

    private record TableRenderOutcome(int nextRow, int maxColumn, boolean autoFilterSet) {
    }

    private enum BlockType {
        BLANK,
        TEXT,
        HEADING,
        TABLE
    }

    private record ContentBlock(BlockType type, String text, int headingLevel, List<List<String>> rows, boolean hasHeader) {
        static ContentBlock blank() {
            return new ContentBlock(BlockType.BLANK, "", 0, List.of(), false);
        }

        static ContentBlock text(String text) {
            return new ContentBlock(BlockType.TEXT, text, 0, List.of(), false);
        }

        static ContentBlock heading(int level, String text) {
            return new ContentBlock(BlockType.HEADING, text, level, List.of(), false);
        }

        static ContentBlock table(List<List<String>> rows, boolean hasHeader) {
            return new ContentBlock(BlockType.TABLE, "", 0, normalizeRows(rows), hasHeader);
        }

        private static List<List<String>> normalizeRows(List<List<String>> rows) {
            int maxSize = rows.stream().mapToInt(List::size).max().orElse(0);
            List<List<String>> result = new ArrayList<>();
            for (List<String> row : rows) {
                List<String> normalized = new ArrayList<>(row);
                while (normalized.size() < maxSize) {
                    normalized.add("");
                }
                result.add(normalized);
            }
            return result;
        }
    }
}
