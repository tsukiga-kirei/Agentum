package com.agentum.shared.pagination;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排序字段白名单把前端字段映射为实体属性，避免任意 sort 字段穿透到 JPA 查询。
 */
public final class SortWhitelist {

    private final String defaultField;
    private final Map<String, String> allowedFields;

    private SortWhitelist(String defaultField, Map<String, String> allowedFields) {
        this.defaultField = defaultField;
        this.allowedFields = allowedFields;
    }

    public static SortWhitelist of(String defaultField, String... allowedFields) {
        Map<String, String> fieldMap = new LinkedHashMap<>();
        Arrays.stream(allowedFields).forEach(field -> fieldMap.put(field, field));
        fieldMap.putIfAbsent(defaultField, defaultField);
        return new SortWhitelist(defaultField, fieldMap);
    }

    public static SortWhitelist mapped(String defaultField, Map<String, String> allowedFields) {
        Map<String, String> fieldMap = new LinkedHashMap<>(allowedFields);
        fieldMap.putIfAbsent(defaultField, defaultField);
        return new SortWhitelist(defaultField, fieldMap);
    }

    String resolve(String requestedField) {
        return allowedFields.getOrDefault(requestedField, defaultField);
    }
}
