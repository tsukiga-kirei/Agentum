package com.agentum.workbench.application;

import com.agentum.agent.application.AgentRuntimeRequest;
import com.agentum.agent.application.AgentRuntimeService;
import com.agentum.delivery.application.DeliveryRuntimeRequest;
import com.agentum.delivery.application.DeliveryRuntimeService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 默认节点执行器。
 *
 * <p>这里是运行态能力的汇聚入口：通用触发、规则节点直接在本地完成；智能体、MCP 和交付节点
 * 继续委派给对应运行服务。当前先提供保守兜底，后续具体能力会在本类内按节点类型分派。</p>
 */
@Service
public class DefaultWorkflowRuntimeExecutor implements WorkflowRuntimeExecutor {

    private final AgentRuntimeService agentRuntimeService;
    private final DeliveryRuntimeService deliveryRuntimeService;

    public DefaultWorkflowRuntimeExecutor(
        AgentRuntimeService agentRuntimeService,
        DeliveryRuntimeService deliveryRuntimeService
    ) {
        this.agentRuntimeService = agentRuntimeService;
        this.deliveryRuntimeService = deliveryRuntimeService;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        return switch (request.nodeRun().getNodeType()) {
            case "trigger" -> new ExecutionResult(triggerOutput(request));
            case "agent" -> new ExecutionResult(executeAgentNode(request));
            case "parallel_group" -> new ExecutionResult(executeParallelGroup(request));
            case "delivery" -> new ExecutionResult(deliveryRuntimeService.execute(new DeliveryRuntimeRequest(
                request.run(),
                request.nodeRun(),
                request.nodeRun().getConfigSnapshot(),
                request.variables(),
                request.operatorUserId()
            )).outputs());
            case "condition" -> new ExecutionResult(conditionOutput(request));
            case "merge" -> new ExecutionResult(mergeOutput(request));
            default -> new ExecutionResult(defaultOutput(request));
        };
    }

    private Map<String, Object> triggerOutput(ExecutionRequest request) {
        Map<String, Object> output = new HashMap<>();
        output.put("trigger", "手动发起");
        output.put("summary", "手动触发节点已完成。");
        return output;
    }

    private Map<String, Object> executeAgentNode(ExecutionRequest request) {
        // Agent 模式下 MCP/Skill 不再由执行器预先调用，而是作为工具交给模型自主选择。
        // 这样工具选择、观察结果和最终回答都能形成同一条可审计推理链。
        return agentRuntimeService.execute(new AgentRuntimeRequest(
            request.run(),
            request.nodeRun(),
            request.nodeRun().getConfigSnapshot(),
            request.variables(),
            Map.of(),
            request.operatorUserId()
        )).outputs();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeParallelGroup(ExecutionRequest request) {
        Object rawAgents = request.nodeRun().getConfigSnapshot().get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agents.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>(request.variables());
            output.put("summary", "智能体集群未配置子智能体，已透传上游变量。");
            return output;
        }
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object rawAgent : agents) {
            if (!(rawAgent instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> agentConfig = new LinkedHashMap<>((Map<String, Object>) rawMap);
            Map<String, Object> agentOutput = agentRuntimeService.execute(new AgentRuntimeRequest(
                request.run(),
                request.nodeRun(),
                agentConfig,
                variables,
                Map.of(),
                request.operatorUserId()
            )).outputs();
            variables.putAll(agentOutput);
            summaries.add(Map.of(
                "name", stringValue(agentConfig.get("name"), "子智能体"),
                "summary", stringValue(agentOutput.get("summary"), "已完成")
            ));
        }
        variables.put("clusterAgents", summaries);
        variables.put("summary", "智能体集群已完成 " + summaries.size() + " 个子智能体。");
        return variables;
    }

    private Map<String, Object> conditionOutput(ExecutionRequest request) {
        Map<String, Object> output = new LinkedHashMap<>(request.variables());
        String expression = stringValue(request.nodeRun().getConfigSnapshot().get("conditionExpression"), "");
        output.put("conditionMatched", expression.isBlank() ? "default" : expression);
        output.put("summary", expression.isBlank() ? "条件节点未配置表达式，按默认路径继续。" : "条件节点已记录表达式：" + expression);
        return output;
    }

    private Map<String, Object> mergeOutput(ExecutionRequest request) {
        Map<String, Object> output = new LinkedHashMap<>(request.variables());
        output.put("summary", "汇聚节点已合并上游变量。");
        return output;
    }

    private Map<String, Object> defaultOutput(ExecutionRequest request) {
        Map<String, Object> output = new HashMap<>();
        output.put("summary", request.nodeRun().getName() + "已由运行执行器完成。");
        return output;
    }

    private static String stringValue(Object value, String fallback) {
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }
}
