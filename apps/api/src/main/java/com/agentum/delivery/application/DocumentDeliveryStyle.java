package com.agentum.delivery.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Word 文档交付的样式快照。
 *
 * <p>这些值会随交付节点配置进入发布版本，运行时按快照生成文件，避免系统默认样式变化影响已发布流程。</p>
 */
public record DocumentDeliveryStyle(
    String chineseFont,
    String latinFont,
    int bodyFontSize,
    int heading1FontSize,
    int heading2FontSize,
    int heading3FontSize,
    String heading1ChineseFont,
    String heading1LatinFont,
    String heading2ChineseFont,
    String heading2LatinFont,
    String heading3ChineseFont,
    String heading3LatinFont,
    String tableChineseFont,
    String tableLatinFont,
    int tableFontSize,
    String tableCellAlignment,
    String lineSpacingMode,
    double lineSpacing,
    int lineSpacingPt,
    String firstLineIndentMode,
    double firstLineIndentChars,
    double firstLineIndentCm,
    int paragraphSpacingBefore,
    int paragraphSpacingAfter,
    double marginTopCm,
    double marginBottomCm,
    double marginLeftCm,
    double marginRightCm,
    boolean titleCentered,
    boolean headingFirstLineIndent
) {

    private static final Set<String> TABLE_ALIGNMENTS = Set.of("left", "center", "right", "both");

    public static DocumentDeliveryStyle defaults() {
        return new DocumentDeliveryStyle(
            "宋体",
            "Times New Roman",
            12,
            16,
            14,
            13,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            0,
            "left",
            "multiple",
            1.5,
            18,
            "chars",
            2,
            0.75,
            0,
            6,
            2.54,
            2.54,
            3.18,
            3.18,
            false,
            false
        );
    }

    public static DocumentDeliveryStyle from(Map<String, Object> raw) {
        DocumentDeliveryStyle defaults = defaults();
        Map<String, Object> source = raw == null ? Map.of() : raw;
        return new DocumentDeliveryStyle(
            readString(source, "chineseFont", defaults.chineseFont()),
            readString(source, "latinFont", defaults.latinFont()),
            readInt(source, "bodyFontSize", defaults.bodyFontSize(), 8, 48),
            readInt(source, "heading1FontSize", defaults.heading1FontSize(), 8, 72),
            readInt(source, "heading2FontSize", defaults.heading2FontSize(), 8, 72),
            readInt(source, "heading3FontSize", defaults.heading3FontSize(), 8, 72),
            readOptionalString(source, "heading1ChineseFont"),
            readOptionalString(source, "heading1LatinFont"),
            readOptionalString(source, "heading2ChineseFont"),
            readOptionalString(source, "heading2LatinFont"),
            readOptionalString(source, "heading3ChineseFont"),
            readOptionalString(source, "heading3LatinFont"),
            readOptionalString(source, "tableChineseFont"),
            readOptionalString(source, "tableLatinFont"),
            readInt(source, "tableFontSize", defaults.tableFontSize(), 0, 72),
            readAlignment(source, "tableCellAlignment", defaults.tableCellAlignment()),
            readLineSpacingMode(source, defaults.lineSpacingMode()),
            readDouble(source, "lineSpacing", defaults.lineSpacing(), 1.0, 3.0),
            readInt(source, "lineSpacingPt", defaults.lineSpacingPt(), 6, 72),
            readFirstLineIndentMode(source, defaults.firstLineIndentMode()),
            readDouble(source, "firstLineIndentChars", defaults.firstLineIndentChars(), 0.0, 6.0),
            readDouble(source, "firstLineIndentCm", defaults.firstLineIndentCm(), 0.0, 10.0),
            readInt(source, "paragraphSpacingBefore", defaults.paragraphSpacingBefore(), 0, 72),
            readInt(source, "paragraphSpacingAfter", defaults.paragraphSpacingAfter(), 0, 72),
            readDouble(source, "marginTopCm", defaults.marginTopCm(), 0.5, 6.0),
            readDouble(source, "marginBottomCm", defaults.marginBottomCm(), 0.5, 6.0),
            readDouble(source, "marginLeftCm", defaults.marginLeftCm(), 0.5, 6.0),
            readDouble(source, "marginRightCm", defaults.marginRightCm(), 0.5, 6.0),
            readBoolean(source, "titleCentered", defaults.titleCentered()),
            readBoolean(source, "headingFirstLineIndent", defaults.headingFirstLineIndent())
        );
    }

    public String headingChineseFont(int level) {
        String configured = switch (Math.max(1, Math.min(3, level))) {
            case 1 -> heading1ChineseFont;
            case 2 -> heading2ChineseFont;
            default -> heading3ChineseFont;
        };
        return configured == null || configured.isBlank() ? chineseFont : configured;
    }

    public String headingLatinFont(int level) {
        String configured = switch (Math.max(1, Math.min(3, level))) {
            case 1 -> heading1LatinFont;
            case 2 -> heading2LatinFont;
            default -> heading3LatinFont;
        };
        return configured == null || configured.isBlank() ? latinFont : configured;
    }

    public String tableResolvedChineseFont() {
        return tableChineseFont == null || tableChineseFont.isBlank() ? chineseFont : tableChineseFont;
    }

    public String tableResolvedLatinFont() {
        return tableLatinFont == null || tableLatinFont.isBlank() ? latinFont : tableLatinFont;
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

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chineseFont", chineseFont);
        result.put("latinFont", latinFont);
        result.put("bodyFontSize", bodyFontSize);
        result.put("heading1FontSize", heading1FontSize);
        result.put("heading2FontSize", heading2FontSize);
        result.put("heading3FontSize", heading3FontSize);
        putOptional(result, "heading1ChineseFont", heading1ChineseFont);
        putOptional(result, "heading1LatinFont", heading1LatinFont);
        putOptional(result, "heading2ChineseFont", heading2ChineseFont);
        putOptional(result, "heading2LatinFont", heading2LatinFont);
        putOptional(result, "heading3ChineseFont", heading3ChineseFont);
        putOptional(result, "heading3LatinFont", heading3LatinFont);
        putOptional(result, "tableChineseFont", tableChineseFont);
        putOptional(result, "tableLatinFont", tableLatinFont);
        if (tableFontSize > 0) {
            result.put("tableFontSize", tableFontSize);
        }
        result.put("tableCellAlignment", tableCellAlignment);
        result.put("lineSpacingMode", lineSpacingMode);
        result.put("lineSpacing", lineSpacing);
        if ("exact".equalsIgnoreCase(lineSpacingMode)) {
            result.put("lineSpacingPt", lineSpacingPt);
        }
        result.put("firstLineIndentMode", firstLineIndentMode);
        result.put("firstLineIndentChars", firstLineIndentChars);
        result.put("firstLineIndentCm", firstLineIndentCm);
        result.put("paragraphSpacingBefore", paragraphSpacingBefore);
        result.put("paragraphSpacingAfter", paragraphSpacingAfter);
        result.put("marginTopCm", marginTopCm);
        result.put("marginBottomCm", marginBottomCm);
        result.put("marginLeftCm", marginLeftCm);
        result.put("marginRightCm", marginRightCm);
        result.put("titleCentered", titleCentered);
        result.put("headingFirstLineIndent", headingFirstLineIndent);
        return result;
    }

    private static String readLineSpacingMode(Map<String, Object> source, String fallback) {
        String text = readOptionalString(source, "lineSpacingMode");
        if (text.isBlank()) {
            return fallback;
        }
        return "exact".equalsIgnoreCase(text) ? "exact" : "multiple";
    }

    private static String readFirstLineIndentMode(Map<String, Object> source, String fallback) {
        String text = readOptionalString(source, "firstLineIndentMode");
        if (text.isBlank()) {
            return fallback;
        }
        return "cm".equalsIgnoreCase(text) ? "cm" : "chars";
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

    private static String readAlignment(Map<String, Object> source, String key, String fallback) {
        String text = readOptionalString(source, key);
        if (text.isBlank()) {
            return fallback;
        }
        String normalized = text.toLowerCase();
        return TABLE_ALIGNMENTS.contains(normalized) ? normalized : fallback;
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
