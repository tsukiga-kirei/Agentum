package com.agentum.shared.util;

import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * 能力编码生成器：根据名称生成符合平台规范的唯一编码。
 * 编码规则与前端展示一致：小写字母开头，仅含小写字母、数字、下划线和短横线。
 */
public final class CapabilityCodeGenerator {

    private static final Pattern VALID_CODE = Pattern.compile("[a-z][a-z0-9_\\-]{1,99}");

    private CapabilityCodeGenerator() {
    }

    /**
     * 根据能力名称生成基础编码；纯中文或无法 slug 化时退化为 cap_{hash}，保证始终可落库。
     */
    public static String slugFromName(String name) {
        if (name == null || name.isBlank()) {
            return "cap";
        }
        String normalized = name.trim().toLowerCase();
        String slug = normalized
            .replaceAll("[\\s\\-]+", "_")
            .replaceAll("[^a-z0-9_]", "")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (slug.length() >= 2 && VALID_CODE.matcher(slug).matches()) {
            return truncate(slug, 100);
        }
        String hashBase = "cap_" + Integer.toHexString(Math.abs(normalized.hashCode()));
        return truncate(hashBase, 100);
    }

    /**
     * 在 code + version 维度上确保唯一；冲突时追加 _2、_3 后缀。
     */
    public static String resolveUniqueCode(String name, String version, BiPredicate<String, String> existsChecker) {
        String base = slugFromName(name);
        String candidate = base;
        int suffix = 2;
        while (existsChecker.test(candidate, version)) {
            candidate = truncate(base + "_" + suffix++, 100);
        }
        return candidate;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
