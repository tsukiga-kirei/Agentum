package com.agentum.delivery.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Word 文档交付的样式快照。
 *
 * <p>这些值会随交付节点配置进入发布版本，运行时按快照生成文件，避免系统默认样式变化影响已发布流程。</p>
 *
 * <p>覆盖优先级：全局代码默认 &lt; 系统能力 defaultStyle &lt; 节点全局样式 &lt; 多级标题规则（按级别） &lt; 逐段个性化规则。
 * 个性化规则只作用于非表格段落，匹配时显式段号优先于特殊选择器，同类后添加者覆盖前者。</p>
 */
public record DocumentDeliveryStyle(
    String chineseFont,
    String latinFont,
    String numberFont,
    int bodyFontSize,
    String bodyAlignment,
    int heading1FontSize,
    int heading2FontSize,
    int heading3FontSize,
    int heading4FontSize,
    int heading5FontSize,
    String heading1ChineseFont,
    String heading1LatinFont,
    String heading1NumberFont,
    String heading2ChineseFont,
    String heading2LatinFont,
    String heading2NumberFont,
    String heading3ChineseFont,
    String heading3LatinFont,
    String heading3NumberFont,
    String heading4ChineseFont,
    String heading4LatinFont,
    String heading4NumberFont,
    String heading5ChineseFont,
    String heading5LatinFont,
    String heading5NumberFont,
    boolean heading1Bold,
    boolean heading2Bold,
    boolean heading3Bold,
    boolean heading4Bold,
    boolean heading5Bold,
    String tableChineseFont,
    String tableLatinFont,
    String tableNumberFont,
    int tableFontSize,
    String tableCellAlignment,
    String tableCellVerticalAlignment,
    double tableCellPaddingVerticalPt,
    boolean tableHeaderBold,
    boolean tableBorders,
    double tableBorderWidthPt,
    String tableLineSpacingMode,
    double tableLineSpacing,
    int tableLineSpacingPt,
    String lineSpacingMode,
    double lineSpacing,
    int lineSpacingPt,
    String firstLineIndentMode,
    double firstLineIndentChars,
    double firstLineIndentCm,
    String orderedListIndentMode,
    double orderedListLeftIndentChars,
    double orderedListHangingIndentChars,
    String unorderedListIndentMode,
    double unorderedListLeftIndentChars,
    double unorderedListHangingIndentChars,
    String paragraphSpacingUnit,
    double paragraphSpacingBefore,
    double paragraphSpacingAfter,
    double marginTopCm,
    double marginBottomCm,
    double marginLeftCm,
    double marginRightCm,
    boolean titleCentered,
    boolean headingFirstLineIndent,
    List<ParagraphRule> paragraphRules
) {

    private static final Set<String> PARAGRAPH_ALIGNMENTS = Set.of("left", "center", "right", "both");
    private static final Set<String> TABLE_ALIGNMENTS = Set.of("left", "center", "right", "both");
    private static final Set<String> VERTICAL_ALIGNMENTS = Set.of("top", "center", "bottom");
    private static final Set<String> SPACING_UNITS = Set.of("line", "pt", "cm", "mm");
    private static final Set<String> RULE_TARGET_TYPES = Set.of("index", "first", "second", "third", "last", "secondLast");
    private static final Set<String> RULE_INDENT_MODES = Set.of("", "none", "chars", "cm");
    private static final Set<String> LIST_INDENT_MODES = Set.of("body", "none", "hanging");

    /**
     * 逐段个性化规则。空字符串字段表示继承全局样式，仅显式设置的字段参与覆盖。
     */
    public record ParagraphRule(
        String targetType,
        int targetIndex,
        String alignment,
        String firstLineIndentMode,
        double firstLineIndentChars,
        double firstLineIndentCm,
        String chineseFont,
        String latinFont,
        String numberFont,
        int fontSize,
        String spacingUnit,
        double spacingBefore,
        double spacingAfter,
        int blankLinesBefore,
        int blankLinesAfter
    ) {

        /** 判断该规则是否命中给定段落序号（1 基，total 为非表格段落总数）。 */
        public boolean matches(int index, int total) {
            return switch (targetType) {
                case "first" -> index == 1;
                case "second" -> index == 2;
                case "third" -> index == 3;
                case "last" -> index == total;
                case "secondLast" -> index == total - 1;
                default -> index == targetIndex;
            };
        }

        /** 显式段号优先级高于特殊选择器，便于命中冲突时选择更具体的规则。 */
        public int priority() {
            return "index".equals(targetType) ? 2 : 1;
        }
    }

    public static DocumentDeliveryStyle defaults() {
        return new DocumentDeliveryStyle(
            "宋体",
            "Times New Roman",
            "Times New Roman",
            12,
            "left",
            16,
            14,
            13,
            0,
            0,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            true,
            true,
            true,
            true,
            true,
            "",
            "",
            "",
            0,
            "left",
            "center",
            1.5,
            false,
            true,
            0.5,
            "multiple",
            1.0,
            12,
            "multiple",
            1.5,
            18,
            "chars",
            2,
            0.75,
            "body",
            3,
            1.5,
            "body",
            3,
            1.5,
            "pt",
            0,
            6,
            2.54,
            2.54,
            3.18,
            3.18,
            false,
            false,
            List.of()
        );
    }

    public static DocumentDeliveryStyle from(Map<String, Object> raw) {
        DocumentDeliveryStyle defaults = defaults();
        Map<String, Object> source = raw == null ? Map.of() : raw;
        return new DocumentDeliveryStyle(
            readString(source, "chineseFont", defaults.chineseFont()),
            readString(source, "latinFont", defaults.latinFont()),
            readString(source, "numberFont", defaults.numberFont()),
            readInt(source, "bodyFontSize", defaults.bodyFontSize(), 8, 48),
            readAlignment(source, "bodyAlignment", PARAGRAPH_ALIGNMENTS, defaults.bodyAlignment()),
            readInt(source, "heading1FontSize", defaults.heading1FontSize(), 8, 72),
            readInt(source, "heading2FontSize", defaults.heading2FontSize(), 8, 72),
            readInt(source, "heading3FontSize", defaults.heading3FontSize(), 8, 72),
            readInt(source, "heading4FontSize", defaults.heading4FontSize(), 0, 72),
            readInt(source, "heading5FontSize", defaults.heading5FontSize(), 0, 72),
            readOptionalString(source, "heading1ChineseFont"),
            readOptionalString(source, "heading1LatinFont"),
            readOptionalString(source, "heading1NumberFont"),
            readOptionalString(source, "heading2ChineseFont"),
            readOptionalString(source, "heading2LatinFont"),
            readOptionalString(source, "heading2NumberFont"),
            readOptionalString(source, "heading3ChineseFont"),
            readOptionalString(source, "heading3LatinFont"),
            readOptionalString(source, "heading3NumberFont"),
            readOptionalString(source, "heading4ChineseFont"),
            readOptionalString(source, "heading4LatinFont"),
            readOptionalString(source, "heading4NumberFont"),
            readOptionalString(source, "heading5ChineseFont"),
            readOptionalString(source, "heading5LatinFont"),
            readOptionalString(source, "heading5NumberFont"),
            readBoolean(source, "heading1Bold", defaults.heading1Bold()),
            readBoolean(source, "heading2Bold", defaults.heading2Bold()),
            readBoolean(source, "heading3Bold", defaults.heading3Bold()),
            readBoolean(source, "heading4Bold", defaults.heading4Bold()),
            readBoolean(source, "heading5Bold", defaults.heading5Bold()),
            readOptionalString(source, "tableChineseFont"),
            readOptionalString(source, "tableLatinFont"),
            readOptionalString(source, "tableNumberFont"),
            readInt(source, "tableFontSize", defaults.tableFontSize(), 0, 72),
            readAlignment(source, "tableCellAlignment", TABLE_ALIGNMENTS, defaults.tableCellAlignment()),
            readAlignment(source, "tableCellVerticalAlignment", VERTICAL_ALIGNMENTS, defaults.tableCellVerticalAlignment()),
            readDouble(source, "tableCellPaddingVerticalPt", defaults.tableCellPaddingVerticalPt(), 0.0, 20.0),
            readBoolean(source, "tableHeaderBold", defaults.tableHeaderBold()),
            readBoolean(source, "tableBorders", defaults.tableBorders()),
            readDouble(source, "tableBorderWidthPt", defaults.tableBorderWidthPt(), 0.25, 6.0),
            readSpacingMode(source, "tableLineSpacingMode", defaults.tableLineSpacingMode()),
            readDouble(source, "tableLineSpacing", defaults.tableLineSpacing(), 1.0, 3.0),
            readInt(source, "tableLineSpacingPt", defaults.tableLineSpacingPt(), 6, 72),
            readSpacingMode(source, "lineSpacingMode", defaults.lineSpacingMode()),
            readDouble(source, "lineSpacing", defaults.lineSpacing(), 1.0, 3.0),
            readInt(source, "lineSpacingPt", defaults.lineSpacingPt(), 6, 72),
            readFirstLineIndentMode(source, defaults.firstLineIndentMode()),
            readDouble(source, "firstLineIndentChars", defaults.firstLineIndentChars(), 0.0, 6.0),
            readDouble(source, "firstLineIndentCm", defaults.firstLineIndentCm(), 0.0, 10.0),
            readListIndentMode(source, "orderedListIndentMode", defaults.orderedListIndentMode()),
            readDouble(source, "orderedListLeftIndentChars", defaults.orderedListLeftIndentChars(), 0.0, 12.0),
            readDouble(source, "orderedListHangingIndentChars", defaults.orderedListHangingIndentChars(), 0.0, 12.0),
            readListIndentMode(source, "unorderedListIndentMode", defaults.unorderedListIndentMode()),
            readDouble(source, "unorderedListLeftIndentChars", defaults.unorderedListLeftIndentChars(), 0.0, 12.0),
            readDouble(source, "unorderedListHangingIndentChars", defaults.unorderedListHangingIndentChars(), 0.0, 12.0),
            readSpacingUnit(source, "paragraphSpacingUnit", defaults.paragraphSpacingUnit()),
            readDouble(source, "paragraphSpacingBefore", defaults.paragraphSpacingBefore(), 0.0, 200.0),
            readDouble(source, "paragraphSpacingAfter", defaults.paragraphSpacingAfter(), 0.0, 200.0),
            readDouble(source, "marginTopCm", defaults.marginTopCm(), 0.5, 6.0),
            readDouble(source, "marginBottomCm", defaults.marginBottomCm(), 0.5, 6.0),
            readDouble(source, "marginLeftCm", defaults.marginLeftCm(), 0.5, 6.0),
            readDouble(source, "marginRightCm", defaults.marginRightCm(), 0.5, 6.0),
            readBoolean(source, "titleCentered", defaults.titleCentered()),
            readBoolean(source, "headingFirstLineIndent", defaults.headingFirstLineIndent()),
            readParagraphRules(source.get("paragraphRules"))
        );
    }

    private static List<ParagraphRule> readParagraphRules(Object raw) {
        if (!(raw instanceof Iterable<?> items)) {
            return List.of();
        }
        List<ParagraphRule> rules = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    map.put(key.toString(), value);
                }
            });
            String targetType = readString(map, "targetType", "index");
            if (!RULE_TARGET_TYPES.contains(targetType)) {
                targetType = "index";
            }
            String indentMode = readOptionalString(map, "firstLineIndentMode");
            if (!RULE_INDENT_MODES.contains(indentMode)) {
                indentMode = "";
            }
            rules.add(new ParagraphRule(
                targetType,
                readInt(map, "targetIndex", 1, 1, 9999),
                readAlignment(map, "alignment", PARAGRAPH_ALIGNMENTS, ""),
                indentMode,
                readDouble(map, "firstLineIndentChars", 2, 0.0, 6.0),
                readDouble(map, "firstLineIndentCm", 0.75, 0.0, 10.0),
                readOptionalString(map, "chineseFont"),
                readOptionalString(map, "latinFont"),
                readOptionalString(map, "numberFont"),
                readInt(map, "fontSize", 0, 0, 72),
                readSpacingUnit(map, "spacingUnit", ""),
                readDouble(map, "spacingBefore", 0, 0.0, 200.0),
                readDouble(map, "spacingAfter", 0, 0.0, 200.0),
                readInt(map, "blankLinesBefore", 0, 0, 20),
                readInt(map, "blankLinesAfter", 0, 0, 20)
            ));
        }
        return List.copyOf(rules);
    }

    public int headingFontSize(int level) {
        int safeLevel = Math.max(1, Math.min(5, level));
        int configured = switch (safeLevel) {
            case 1 -> heading1FontSize;
            case 2 -> heading2FontSize;
            case 3 -> heading3FontSize;
            case 4 -> heading4FontSize;
            default -> heading5FontSize;
        };
        // 四、五级标题字号默认继承三级标题。
        if (safeLevel >= 4 && configured <= 0) {
            return heading3FontSize;
        }
        return configured;
    }

    public boolean headingBold(int level) {
        return switch (Math.max(1, Math.min(5, level))) {
            case 1 -> heading1Bold;
            case 2 -> heading2Bold;
            case 3 -> heading3Bold;
            case 4 -> heading4Bold;
            default -> heading5Bold;
        };
    }

    public String headingChineseFont(int level) {
        int safeLevel = Math.max(1, Math.min(5, level));
        String configured = switch (safeLevel) {
            case 1 -> heading1ChineseFont;
            case 2 -> heading2ChineseFont;
            case 3 -> heading3ChineseFont;
            case 4 -> heading4ChineseFont;
            default -> heading5ChineseFont;
        };
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        // 四、五级标题字体默认继承三级标题；三级及以上继承正文。
        return safeLevel >= 4 ? headingChineseFont(3) : chineseFont;
    }

    public String headingLatinFont(int level) {
        int safeLevel = Math.max(1, Math.min(5, level));
        String configured = switch (safeLevel) {
            case 1 -> heading1LatinFont;
            case 2 -> heading2LatinFont;
            case 3 -> heading3LatinFont;
            case 4 -> heading4LatinFont;
            default -> heading5LatinFont;
        };
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return safeLevel >= 4 ? headingLatinFont(3) : latinFont;
    }

    public String headingNumberFont(int level) {
        int safeLevel = Math.max(1, Math.min(5, level));
        String configured = switch (safeLevel) {
            case 1 -> heading1NumberFont;
            case 2 -> heading2NumberFont;
            case 3 -> heading3NumberFont;
            case 4 -> heading4NumberFont;
            default -> heading5NumberFont;
        };
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return safeLevel >= 4 ? headingNumberFont(3) : numberFont;
    }

    public String tableResolvedChineseFont() {
        return tableChineseFont == null || tableChineseFont.isBlank() ? chineseFont : tableChineseFont;
    }

    public String tableResolvedLatinFont() {
        return tableLatinFont == null || tableLatinFont.isBlank() ? latinFont : tableLatinFont;
    }

    public String tableResolvedNumberFont() {
        return tableNumberFont == null || tableNumberFont.isBlank() ? numberFont : tableNumberFont;
    }

    public int tableResolvedFontSize() {
        return tableFontSize <= 0 ? bodyFontSize : tableFontSize;
    }

    public String resolvedLineSpacingRule() {
        return "exact".equalsIgnoreCase(lineSpacingMode) ? "exact" : "auto";
    }

    public int resolvedLineTwips() {
        if ("exact".equalsIgnoreCase(lineSpacingMode)) {
            return lineSpacingPt * 20;
        }
        return (int) Math.round(240 * lineSpacing);
    }

    public String resolvedTableLineSpacingRule() {
        return "exact".equalsIgnoreCase(tableLineSpacingMode) ? "exact" : "auto";
    }

    public int resolvedTableLineTwips() {
        if ("exact".equalsIgnoreCase(tableLineSpacingMode)) {
            return tableLineSpacingPt * 20;
        }
        return (int) Math.round(240 * tableLineSpacing);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chineseFont", chineseFont);
        result.put("latinFont", latinFont);
        result.put("numberFont", numberFont);
        result.put("bodyFontSize", bodyFontSize);
        result.put("bodyAlignment", bodyAlignment);
        result.put("heading1FontSize", heading1FontSize);
        result.put("heading2FontSize", heading2FontSize);
        result.put("heading3FontSize", heading3FontSize);
        if (heading4FontSize > 0) {
            result.put("heading4FontSize", heading4FontSize);
        }
        if (heading5FontSize > 0) {
            result.put("heading5FontSize", heading5FontSize);
        }
        putOptional(result, "heading1ChineseFont", heading1ChineseFont);
        putOptional(result, "heading1LatinFont", heading1LatinFont);
        putOptional(result, "heading1NumberFont", heading1NumberFont);
        putOptional(result, "heading2ChineseFont", heading2ChineseFont);
        putOptional(result, "heading2LatinFont", heading2LatinFont);
        putOptional(result, "heading2NumberFont", heading2NumberFont);
        putOptional(result, "heading3ChineseFont", heading3ChineseFont);
        putOptional(result, "heading3LatinFont", heading3LatinFont);
        putOptional(result, "heading3NumberFont", heading3NumberFont);
        putOptional(result, "heading4ChineseFont", heading4ChineseFont);
        putOptional(result, "heading4LatinFont", heading4LatinFont);
        putOptional(result, "heading4NumberFont", heading4NumberFont);
        putOptional(result, "heading5ChineseFont", heading5ChineseFont);
        putOptional(result, "heading5LatinFont", heading5LatinFont);
        putOptional(result, "heading5NumberFont", heading5NumberFont);
        result.put("heading1Bold", heading1Bold);
        result.put("heading2Bold", heading2Bold);
        result.put("heading3Bold", heading3Bold);
        result.put("heading4Bold", heading4Bold);
        result.put("heading5Bold", heading5Bold);
        putOptional(result, "tableChineseFont", tableChineseFont);
        putOptional(result, "tableLatinFont", tableLatinFont);
        putOptional(result, "tableNumberFont", tableNumberFont);
        if (tableFontSize > 0) {
            result.put("tableFontSize", tableFontSize);
        }
        result.put("tableCellAlignment", tableCellAlignment);
        result.put("tableCellVerticalAlignment", tableCellVerticalAlignment);
        result.put("tableCellPaddingVerticalPt", tableCellPaddingVerticalPt);
        result.put("tableHeaderBold", tableHeaderBold);
        result.put("tableBorders", tableBorders);
        result.put("tableBorderWidthPt", tableBorderWidthPt);
        result.put("tableLineSpacingMode", tableLineSpacingMode);
        result.put("tableLineSpacing", tableLineSpacing);
        if ("exact".equalsIgnoreCase(tableLineSpacingMode)) {
            result.put("tableLineSpacingPt", tableLineSpacingPt);
        }
        result.put("lineSpacingMode", lineSpacingMode);
        result.put("lineSpacing", lineSpacing);
        if ("exact".equalsIgnoreCase(lineSpacingMode)) {
            result.put("lineSpacingPt", lineSpacingPt);
        }
        result.put("firstLineIndentMode", firstLineIndentMode);
        result.put("firstLineIndentChars", firstLineIndentChars);
        result.put("firstLineIndentCm", firstLineIndentCm);
        result.put("orderedListIndentMode", orderedListIndentMode);
        result.put("orderedListLeftIndentChars", orderedListLeftIndentChars);
        result.put("orderedListHangingIndentChars", orderedListHangingIndentChars);
        result.put("unorderedListIndentMode", unorderedListIndentMode);
        result.put("unorderedListLeftIndentChars", unorderedListLeftIndentChars);
        result.put("unorderedListHangingIndentChars", unorderedListHangingIndentChars);
        result.put("paragraphSpacingUnit", paragraphSpacingUnit);
        result.put("paragraphSpacingBefore", paragraphSpacingBefore);
        result.put("paragraphSpacingAfter", paragraphSpacingAfter);
        result.put("marginTopCm", marginTopCm);
        result.put("marginBottomCm", marginBottomCm);
        result.put("marginLeftCm", marginLeftCm);
        result.put("marginRightCm", marginRightCm);
        result.put("titleCentered", titleCentered);
        result.put("headingFirstLineIndent", headingFirstLineIndent);
        if (!paragraphRules.isEmpty()) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (ParagraphRule rule : paragraphRules) {
                Map<String, Object> ruleMap = new LinkedHashMap<>();
                ruleMap.put("targetType", rule.targetType());
                ruleMap.put("targetIndex", rule.targetIndex());
                putOptional(ruleMap, "alignment", rule.alignment());
                putOptional(ruleMap, "firstLineIndentMode", rule.firstLineIndentMode());
                ruleMap.put("firstLineIndentChars", rule.firstLineIndentChars());
                ruleMap.put("firstLineIndentCm", rule.firstLineIndentCm());
                putOptional(ruleMap, "chineseFont", rule.chineseFont());
                putOptional(ruleMap, "latinFont", rule.latinFont());
                putOptional(ruleMap, "numberFont", rule.numberFont());
                if (rule.fontSize() > 0) {
                    ruleMap.put("fontSize", rule.fontSize());
                }
                putOptional(ruleMap, "spacingUnit", rule.spacingUnit());
                ruleMap.put("spacingBefore", rule.spacingBefore());
                ruleMap.put("spacingAfter", rule.spacingAfter());
                ruleMap.put("blankLinesBefore", rule.blankLinesBefore());
                ruleMap.put("blankLinesAfter", rule.blankLinesAfter());
                rules.add(ruleMap);
            }
            result.put("paragraphRules", rules);
        }
        return result;
    }

    private static String readSpacingMode(Map<String, Object> source, String key, String fallback) {
        String text = readOptionalString(source, key);
        if (text.isBlank()) {
            return fallback;
        }
        return "exact".equalsIgnoreCase(text) ? "exact" : "multiple";
    }

    private static String readSpacingUnit(Map<String, Object> source, String key, String fallback) {
        String text = readOptionalString(source, key);
        if (text.isBlank()) {
            return fallback;
        }
        String normalized = text.toLowerCase();
        return SPACING_UNITS.contains(normalized) ? normalized : fallback;
    }

    private static String readFirstLineIndentMode(Map<String, Object> source, String fallback) {
        String text = readOptionalString(source, "firstLineIndentMode");
        if (text.isBlank()) {
            return fallback;
        }
        return "cm".equalsIgnoreCase(text) ? "cm" : "chars";
    }

    private static String readListIndentMode(Map<String, Object> source, String key, String fallback) {
        String text = readOptionalString(source, key).toLowerCase();
        return LIST_INDENT_MODES.contains(text) ? text : fallback;
    }

    private static void putOptional(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String readOptionalString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private static String readAlignment(Map<String, Object> source, String key, Set<String> allowed, String fallback) {
        String text = readOptionalString(source, key);
        if (text.isBlank()) {
            return fallback;
        }
        String normalized = text.toLowerCase();
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String readString(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static int readInt(Map<String, Object> source, String key, int fallback, int min, int max) {
        Object value = source.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = value instanceof Number number ? number.intValue() : parseFontSize(value.toString().trim());
            return Math.min(max, Math.max(min, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int parseFontSize(String value) {
        return switch (value) {
            case "初号" -> 42;
            case "小初" -> 36;
            case "一号" -> 26;
            case "小一" -> 24;
            case "二号" -> 22;
            case "小二" -> 18;
            case "三号" -> 16;
            case "小三" -> 15;
            case "四号" -> 14;
            case "小四" -> 12;
            case "五号" -> 11;
            case "小五" -> 9;
            case "六号" -> 8;
            default -> Integer.parseInt(value);
        };
    }

    private static double readDouble(Map<String, Object> source, String key, double fallback, double min, double max) {
        Object value = source.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString().trim());
            return Math.min(max, Math.max(min, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean readBoolean(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        if (text.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(text) || "是".equals(text) || "开启".equals(text) || "1".equals(text);
    }
}
