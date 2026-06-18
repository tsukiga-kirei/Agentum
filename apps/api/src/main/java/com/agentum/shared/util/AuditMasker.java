package com.agentum.shared.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计日志敏感数据脱敏工具类。
 * 用于对 MCP 调用参数、模型 prompt 历史、以及交付 payload 等日志中包含的隐私和敏感数据进行掩码或替换，
 * 同时防止明文凭证（API Key 等）记录在审计日志中。
 */
public class AuditMasker {

    private static final ObjectMapper mapper = new ObjectMapper();

    // 匹配大模型 API Key 和 HTTP 认证 Header 凭证的正则，防止在 Prompt 快照或日志中记录明文凭证
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(sk-[a-zA-Z0-9_-]{8,})"
            + "|((Bearer|Basic)\\s+[a-zA-Z0-9-_=\\.]+)"
            + "|((password|api[_-]?key|token|secret|authorization|cookie|credential)"
            + "\\s*[:=]\\s*(?:(Bearer|Basic)\\s+[a-zA-Z0-9-_=\\.]+|[\\\"']?[^\\s,;\\\"'}]+[\\\"']?))",
        Pattern.CASE_INSENSITIVE
    );

    private AuditMasker() {
        // 工具类私有构造
    }

    /**
     * 对结构化 JSON 字符串进行敏感字段和敏感值脱敏。
     *
     * @param jsonStr        原始 JSON 字符串
     * @param sensitiveKeys  敏感变量名（JSON Key）集合
     * @param sensitiveValues 敏感变量明文值集合
     * @return 脱敏后的 JSON 字符串
     */
    public static String maskJson(String jsonStr, Set<String> sensitiveKeys, Set<String> sensitiveValues) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return "{}";
        }
        try {
            JsonNode root = mapper.readTree(jsonStr);
            maskJsonNode(root, sensitiveKeys, sensitiveValues);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // 如果 JSON 解析异常，为保障安全性，直接返回包含错误提示的安全 JSON
            return "{\"error\":\"[Masking Failed due to invalid JSON]\"}";
        }
    }

    /**
     * 递归遍历 JSON 节点树并进行替换。
     */
    private static void maskJsonNode(JsonNode node, Set<String> sensitiveKeys, Set<String> sensitiveValues) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode valNode = field.getValue();

                if (isSensitiveKey(key, sensitiveKeys)) {
                    objectNode.put(key, "******");
                } else if (valNode.isTextual() && isSensitiveValue(valNode.asText(), sensitiveValues)) {
                    objectNode.put(key, "******");
                } else {
                    maskJsonNode(valNode, sensitiveKeys, sensitiveValues);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                if (child.isTextual() && isSensitiveValue(child.asText(), sensitiveValues)) {
                    arrayNode.set(i, arrayNode.textNode("******"));
                } else {
                    maskJsonNode(child, sensitiveKeys, sensitiveValues);
                }
            }
        }
    }

    /**
     * 判断 Key 是否属于敏感字段名。
     */
    private static boolean isSensitiveKey(String key, Set<String> sensitiveKeys) {
        if (sensitiveKeys != null && sensitiveKeys.contains(key)) {
            return true;
        }
        // 内置模糊匹配：包含密码、秘钥等常见敏感关键词，作为降级安全防线
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("password") 
            || lowerKey.contains("apikey") 
            || lowerKey.contains("token") 
            || lowerKey.contains("secret")
            || lowerKey.contains("credential")
            || lowerKey.contains("authorization");
    }

    /**
     * 判断字符串值是否匹配敏感值（仅当值长度大于3时才进行精确替换，防止误伤 1, 0, true 等无意义短字）。
     */
    private static boolean isSensitiveValue(String value, Set<String> sensitiveValues) {
        if (value == null || value.length() <= 3) {
            return false;
        }
        return sensitiveValues != null && sensitiveValues.contains(value);
    }

    /**
     * 对非结构化的自然语言文本（例如提示词模版渲染后的 Prompt 历史）进行敏感值和敏感凭证脱敏。
     *
     * @param text                 原始文本
     * @param sensitivePlainValues 敏感变量明文值集合
     * @return 脱敏后的文本
     */
    public static String maskText(String text, Set<String> sensitivePlainValues) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 1. 拦截大模型 API Key 及 HTTP 头部明文凭证
        Matcher matcher = CREDENTIAL_PATTERN.matcher(text);
        String masked = matcher.replaceAll("******");

        // 2. 精确替换流程上下文中已知的敏感变量值
        if (sensitivePlainValues != null) {
            for (String plainValue : sensitivePlainValues) {
                if (plainValue != null && plainValue.length() > 3) {
                    masked = masked.replace(plainValue, "******");
                }
            }
        }

        return masked;
    }
}
