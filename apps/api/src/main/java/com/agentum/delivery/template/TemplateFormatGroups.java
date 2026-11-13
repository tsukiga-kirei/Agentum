package com.agentum.delivery.template;

import java.util.*;

/** 方案按完整字段标识绑定，跨 Sheet/重复位置共用；禁止依靠列表顺序决定格式覆盖。 */
final class TemplateFormatGroups {
    private TemplateFormatGroups() {}

    static void validate(TemplateModels.Spec spec) {
        if (spec.formatGroups().size() > 100) throw StructuredContent.error("格式方案不能超过 100 个");
        Map<String, String> types = new LinkedHashMap<>();
        for (var field : spec.fields()) {
            if ("table".equals(field.type())) {
                for (var column : field.columns()) types.put(field.key() + "." + column.key(), column.type());
            } else types.put(field.key(), field.type());
        }
        Set<String> ids = new HashSet<>(), assigned = new HashSet<>();
        for (var group : spec.formatGroups()) {
            if (group.id() == null || !group.id().matches("[A-Za-z0-9_-]{1,80}") || !ids.add(group.id()))
                throw StructuredContent.error("格式方案标识无效或重复");
            if (group.name() == null || group.name().isBlank() || group.name().length() > 100)
                throw StructuredContent.error("格式方案名称应为 1～100 个字符");
            String type = switch (Objects.toString(group.preset(), "")) {
                case "amount", "rate", "number" -> "number";
                case "text" -> "text";
                case "date" -> "date";
                case "boolean" -> "boolean";
                case "custom" -> null;
                default -> throw StructuredContent.error("格式方案预设无效");
            };
            if (group.fields() == null || group.fields().size() > 10000 || group.style() == null)
                throw StructuredContent.error("格式方案的字段或样式配置无效");
            for (String field : group.fields()) {
                if (!types.containsKey(field)) throw StructuredContent.error("格式方案引用的字段不存在或是明细容器：" + field);
                if (!assigned.add(field)) throw StructuredContent.error("同一字段不能重复归属多个格式方案：" + field);
                if (type != null && !type.equals(types.get(field)))
                    throw StructuredContent.error("格式方案与字段类型不一致，请重新应用预设：" + field);
            }
        }
    }
}
