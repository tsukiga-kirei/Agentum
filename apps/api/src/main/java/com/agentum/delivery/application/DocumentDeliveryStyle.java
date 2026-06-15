package com.agentum.delivery.application;

import java.util.LinkedHashMap;
import java.util.Map;

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
    double lineSpacing,
    double firstLineIndentChars,
    int paragraphSpacingAfter,
    double marginTopCm,
    double marginBottomCm,
    double marginLeftCm,
    double marginRightCm,
    boolean titleCentered
) {

    public static DocumentDeliveryStyle defaults() {
        return new DocumentDeliveryStyle(
            "宋体",
            "Times New Roman",
            12,
            16,
            14,
            13,
            1.5,
            2,
            6,
            2.54,
            2.54,
            3.18,
            3.18,
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
            readDouble(source, "lineSpacing", defaults.lineSpacing(), 1.0, 3.0),
            readDouble(source, "firstLineIndentChars", defaults.firstLineIndentChars(), 0.0, 6.0),
            readInt(source, "paragraphSpacingAfter", defaults.paragraphSpacingAfter(), 0, 72),
            readDouble(source, "marginTopCm", defaults.marginTopCm(), 0.5, 6.0),
            readDouble(source, "marginBottomCm", defaults.marginBottomCm(), 0.5, 6.0),
            readDouble(source, "marginLeftCm", defaults.marginLeftCm(), 0.5, 6.0),
            readDouble(source, "marginRightCm", defaults.marginRightCm(), 0.5, 6.0),
            readBoolean(source, "titleCentered", defaults.titleCentered())
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chineseFont", chineseFont);
        result.put("latinFont", latinFont);
        result.put("bodyFontSize", bodyFontSize);
        result.put("heading1FontSize", heading1FontSize);
        result.put("heading2FontSize", heading2FontSize);
        result.put("heading3FontSize", heading3FontSize);
        result.put("lineSpacing", lineSpacing);
        result.put("firstLineIndentChars", firstLineIndentChars);
        result.put("paragraphSpacingAfter", paragraphSpacingAfter);
        result.put("marginTopCm", marginTopCm);
        result.put("marginBottomCm", marginBottomCm);
        result.put("marginLeftCm", marginLeftCm);
        result.put("marginRightCm", marginRightCm);
        result.put("titleCentered", titleCentered);
        return result;
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
