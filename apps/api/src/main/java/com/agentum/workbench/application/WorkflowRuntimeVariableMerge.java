package com.agentum.workbench.application;

import com.agentum.workflow.application.WorkflowRuntimeSystemVariables;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 运行态变量合并规则：按节点完成顺序叠加输出，并避免后序节点用空值覆盖前序有效输入。
 *
 * <p>定时任务会连续自动推进多个节点，若中间智能体输出里带有同名但为空的字段，
 * 可能把输入节点已写入的 {@code report_month} 等值冲掉；手工执行因输入先落库、
 * 用户确认后再推进，不易触发该竞态。</p>
 */
final class WorkflowRuntimeVariableMerge {

    private static final Set<String> RUNTIME_META_KEYS = Set.of(
        "trigger",
        "summary",
        "errorCode",
        "errorMessage",
        "final_answer",
        "final_answer_source",
        "agent_response",
        "model_content",
        "modelName",
        "agentMode",
        "toolCalls",
        "modelCallLogIds",
        "modelCallLogId",
        "tokenUsage",
        "reasoning_content",
        "chatMessages",
        "clusterAgents",
        "intentRouting"
    );

    private WorkflowRuntimeVariableMerge() {
    }

    static void mergeOutputs(Map<String, Object> target, Map<String, Object> outputs) {
        if (target == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || RUNTIME_META_KEYS.contains(key)) {
                continue;
            }
            Object incoming = entry.getValue();
            Object existing = target.get(key);
            if (isBlankValue(incoming) && !isBlankValue(existing)) {
                continue;
            }
            target.put(key, incoming);
        }
    }

    static boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    static boolean isBusinessVariableKey(String key) {
        if (key == null || key.isBlank() || RUNTIME_META_KEYS.contains(key)) {
            return false;
        }
        return !WorkflowRuntimeSystemVariables.descriptions().containsKey(key);
    }

    static String summarizeVariableValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= 240) {
            return text;
        }
        return text.substring(0, 240) + "...";
    }
}
