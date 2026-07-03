package com.agentum.workbench.application;

import com.agentum.agent.application.AgentRuntimeRequest;
import com.agentum.agent.application.AgentRuntimeService;
import com.agentum.delivery.application.DeliveryRuntimeRequest;
import com.agentum.delivery.application.DeliveryRuntimeService;
import com.agentum.shared.api.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
        List<Map<String, Object>> agentConfigs = new ArrayList<>();
        for (Object rawAgent : agents) {
            if (rawAgent instanceof Map<?, ?> rawMap) {
                agentConfigs.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
            }
        }
        String executionMode = ClusterIntentRoutingSupport.normalizeExecutionMode(request.nodeRun().getConfigSnapshot().get("executionMode"));
        if (ClusterIntentRoutingSupport.MODE_INTENT.equals(executionMode)) {
            return executeIntentGroup(request, agentConfigs);
        }
        if (ClusterIntentRoutingSupport.MODE_RELAY.equals(executionMode)) {
            return executeRelayGroup(request, agentConfigs);
        }
        return executeCollaborativeGroup(request, agentConfigs);
    }

    private Map<String, Object> executeRelayGroup(ExecutionRequest request, List<Map<String, Object>> agentConfigs) {
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> agentConfig : agentConfigs) {
            Map<String, Object> agentOutput = executeClusterAgent(request, agentConfig, variables);
            variables.putAll(agentOutput);
            summaries.add(clusterSummary(agentConfig, agentOutput));
        }
        return clusterResult(request.nodeRun().getConfigSnapshot(), variables, summaries);
    }

    private Map<String, Object> executeCollaborativeGroup(ExecutionRequest request, List<Map<String, Object>> agentConfigs) {
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> agentConfig : agentConfigs) {
            Map<String, Object> agentOutput = executeClusterAgent(request, agentConfig, request.variables());
            variables.putAll(agentOutput);
            summaries.add(clusterSummary(agentConfig, agentOutput));
        }
        return clusterResult(request.nodeRun().getConfigSnapshot(), variables, summaries);
    }

    private Map<String, Object> executeIntentGroup(ExecutionRequest request, List<Map<String, Object>> agentConfigs) {
        Map<String, Object> nodeConfig = request.nodeRun().getConfigSnapshot();
        List<ClusterIntentRoutingSupport.IntentRoute> routes = ClusterIntentRoutingSupport.intentRoutes(nodeConfig, agentConfigs);
        Map<String, Object> classifierOutput = agentRuntimeService.execute(new AgentRuntimeRequest(
            request.run(),
            request.nodeRun(),
            ClusterIntentRoutingSupport.classifierConfig(nodeConfig, agentConfigs, routes),
            request.variables(),
            Map.of(),
            request.operatorUserId()
        )).outputs();
        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            nodeConfig,
            routes,
            agentConfigs,
            classifierOutput
        );
        if (decision.selectedAgentIndexes().isEmpty()) {
            if (!decision.fixedReply().isBlank()) {
                return fixedIntentReplyResult(request.variables(), nodeConfig, decision);
            }
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "CLUSTER_INTENT_NO_MATCH",
                "意图分派未命中任何可执行子智能体，请检查意图配置或其他情况处理策略"
            );
        }
        Map<String, Object> variables = new LinkedHashMap<>(request.variables());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int index : decision.selectedAgentIndexes()) {
            Map<String, Object> agentConfig = agentConfigs.get(index);
            Map<String, Object> agentOutput = executeClusterAgent(request, agentConfig, request.variables());
            variables.putAll(agentOutput);
            summaries.add(clusterSummary(agentConfig, agentOutput));
        }
        Map<String, Object> result = clusterResult(nodeConfig, variables, summaries);
        result.put("intentRouting", intentRoutingSummary(decision));
        return result;
    }

    private Map<String, Object> executeClusterAgent(ExecutionRequest request, Map<String, Object> agentConfig, Map<String, Object> variables) {
        return agentRuntimeService.execute(new AgentRuntimeRequest(
            request.run(),
            request.nodeRun(),
            agentConfig,
            variables,
            Map.of(),
            request.operatorUserId()
        )).outputs();
    }

    private Map<String, Object> clusterSummary(Map<String, Object> agentConfig, Map<String, Object> agentOutput) {
        String displayText = firstNonBlank(
            stringValue(agentOutput.get("final_answer"), ""),
            stringValue(agentOutput.get("summary"), "已完成")
        );
        return Map.of(
            "name", stringValue(agentConfig.get("name"), "子智能体"),
            "outputVariable", stringValue(agentConfig.get("output"), ""),
            "status", "completed",
            "final_answer", displayText,
            "summary", summarizeText(displayText)
        );
    }

    private Map<String, Object> clusterResult(Map<String, Object> nodeConfig, Map<String, Object> variables, List<Map<String, Object>> summaries) {
        variables.put("clusterAgents", summaries);
        String finalAnswer = ClusterOutputSupport.finalAnswer(nodeConfig, variables, summaries);
        variables.put("final_answer", finalAnswer);
        variables.put("agent_response", finalAnswer);
        variables.put(ClusterOutputSupport.outputVariable(nodeConfig), finalAnswer);
        variables.put(ClusterIntentRoutingSupport.DEFAULT_INTENT_OUTPUT_VARIABLE, finalAnswer);
        variables.put("summary", "智能体集群已完成 " + summaries.size() + " 个子智能体。");
        return variables;
    }

    private Map<String, Object> intentRoutingSummary(ClusterIntentRoutingSupport.IntentDecision decision) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestedCodes", decision.requestedCodes());
        summary.put("selectedCodes", decision.selectedCodes());
        summary.put("selectedAgentIndexes", decision.selectedAgentIndexes());
        summary.put("reason", decision.reason());
        summary.put("slots", decision.slots());
        summary.put("usedFallback", decision.usedFallback());
        summary.put("fallbackMode", decision.fallbackMode());
        return summary;
    }

    private Map<String, Object> fixedIntentReplyResult(Map<String, Object> variables, Map<String, Object> nodeConfig, ClusterIntentRoutingSupport.IntentDecision decision) {
        Map<String, Object> result = new LinkedHashMap<>(variables);
        result.put("final_answer", decision.fixedReply());
        result.put("agent_response", decision.fixedReply());
        result.put(ClusterOutputSupport.outputVariable(nodeConfig), decision.fixedReply());
        result.put(ClusterIntentRoutingSupport.DEFAULT_INTENT_OUTPUT_VARIABLE, decision.fixedReply());
        result.put("intentRouting", intentRoutingSummary(decision));
        result.put("summary", "意图分派已按其他情况返回固定话术。");
        return result;
    }

    private static String summarizeText(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "智能体已完成模型调用。";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
