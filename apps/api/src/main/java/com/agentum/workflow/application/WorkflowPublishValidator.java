package com.agentum.workflow.application;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WorkflowPublishValidator {

    private static final String ERROR_LEVEL = "error";

    public WorkflowDraftApi.WorkflowPublishValidationResult validate(
        List<WorkflowDraftApi.WorkflowNodeRow> nodes,
        List<WorkflowDraftApi.WorkflowEdgeRow> edges
    ) {
        List<WorkflowDraftApi.WorkflowValidationIssue> issues = new ArrayList<>();
        Map<String, WorkflowDraftApi.WorkflowNodeRow> nodesById = indexNodes(nodes);
        Map<String, List<String>> outgoing = buildOutgoing(nodesById.keySet(), edges);
        Map<String, List<String>> incoming = buildIncoming(nodesById.keySet(), edges);

        if (nodes.isEmpty()) {
            issues.add(issue("WORKFLOW_VALIDATION_GRAPH_EMPTY", "工作流至少需要一个节点", null));
            return new WorkflowDraftApi.WorkflowPublishValidationResult(false, 0, edges.size(), issues);
        }

        List<WorkflowDraftApi.WorkflowNodeRow> triggers = nodes.stream().filter(node -> "trigger".equals(node.nodeType())).toList();
        if (triggers.isEmpty()) {
            issues.add(issue("WORKFLOW_VALIDATION_TRIGGER_REQUIRED", "工作流必须包含一个触发节点", null));
        } else if (triggers.size() > 1) {
            issues.add(issue("WORKFLOW_VALIDATION_TRIGGER_DUPLICATED", "工作流只能包含一个触发节点", null));
        }

        if (nodes.stream().noneMatch(node -> "delivery".equals(node.nodeType()))) {
            issues.add(issue("WORKFLOW_VALIDATION_DELIVERY_REQUIRED", "工作流至少需要一个交付节点", null));
        }

        List<WorkflowDraftApi.WorkflowNodeRow> deliveryNodes = nodes.stream()
            .filter(node -> "delivery".equals(node.nodeType()))
            .toList();
        if (deliveryNodes.size() > 1) {
            issues.add(issue(
                "WORKFLOW_VALIDATION_DELIVERY_DUPLICATED",
                "工作流只能包含一个交付节点",
                deliveryNodes.get(1)
            ));
        }
        if (!deliveryNodes.isEmpty()) {
            WorkflowDraftApi.WorkflowNodeRow lastNode = nodes.get(nodes.size() - 1);
            if (!"delivery".equals(lastNode.nodeType())) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_DELIVERY_MUST_BE_LAST",
                    "交付节点必须放在流程最后一步",
                    deliveryNodes.get(0)
                ));
            }
            for (WorkflowDraftApi.WorkflowNodeRow deliveryNode : deliveryNodes) {
                if (!outgoing.getOrDefault(deliveryNode.nodeId(), List.of()).isEmpty()) {
                    issues.add(issue(
                        "WORKFLOW_VALIDATION_DELIVERY_MUST_BE_TERMINAL",
                        "交付节点后不能再连接其他节点",
                        deliveryNode
                    ));
                }
            }
        }

        for (WorkflowDraftApi.WorkflowNodeRow node : nodes) {
            if (!"trigger".equals(node.nodeType()) && incoming.getOrDefault(node.nodeId(), List.of()).isEmpty()) {
                issues.add(issue("WORKFLOW_VALIDATION_NODE_INCOMING_REQUIRED", "除触发节点外，每个节点都必须有上游连线", node));
            }
            if (!"delivery".equals(node.nodeType()) && outgoing.getOrDefault(node.nodeId(), List.of()).isEmpty()) {
                issues.add(issue("WORKFLOW_VALIDATION_NODE_OUTGOING_REQUIRED", "除交付节点外，每个节点都必须有下游连线", node));
            }
        }

        List<String> topologicalOrder = topologicalSort(nodesById.keySet(), incoming, outgoing);
        if (topologicalOrder.size() != nodes.size()) {
            issues.add(issue("WORKFLOW_VALIDATION_GRAPH_CYCLE", "工作流不能包含循环连线", null));
        }
        validateLinearExecutionOrder(nodes, edges, issues);

        if (triggers.size() == 1) {
            Set<String> reachable = collectReachableNodes(triggers.get(0).nodeId(), outgoing);
            for (WorkflowDraftApi.WorkflowNodeRow node : nodes) {
                if (!reachable.contains(node.nodeId())) {
                    issues.add(issue("WORKFLOW_VALIDATION_NODE_UNREACHABLE", "节点无法从触发节点到达", node));
                }
            }
        }

        validateVariables(nodesById, incoming, issues);

        // 对外展示的节点数排除系统触发节点，与流程设计器「积木」计数和草稿列表 nodeCount 口径一致。
        int brickCount = (int) nodes.stream()
            .filter(node -> !"trigger".equals(node.nodeType()))
            .count();

        return new WorkflowDraftApi.WorkflowPublishValidationResult(
            issues.stream().noneMatch(issue -> ERROR_LEVEL.equals(issue.level())),
            brickCount,
            edges.size(),
            List.copyOf(issues)
        );
    }

    private void validateVariables(
        Map<String, WorkflowDraftApi.WorkflowNodeRow> nodesById,
        Map<String, List<String>> incoming,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        Map<String, WorkflowDraftApi.WorkflowNodeRow> outputOwners = new LinkedHashMap<>();

        for (WorkflowDraftApi.WorkflowNodeRow node : nodesById.values()) {
            Set<String> availableVariables = collectUpstreamVariables(node.nodeId(), nodesById, incoming);

            // 发布校验只接受由上游节点显式产出的变量，防止设计态在保存顺序上“看似可用、实际运行时拿不到值”。
            for (String inputVariable : normalizeVariables(node.inputVariables())) {
                if (!availableVariables.contains(inputVariable)) {
                    issues.add(issue(
                        "WORKFLOW_VALIDATION_INPUT_VARIABLE_UNRESOLVED",
                        "输入变量 `" + inputVariable + "` 不是当前节点可用的上游输出",
                        node
                    ));
                }
            }

            for (String outputVariable : normalizeVariables(node.outputVariables())) {
                WorkflowDraftApi.WorkflowNodeRow previousOwner = outputOwners.putIfAbsent(outputVariable, node);
                if (previousOwner != null) {
                    issues.add(issue(
                        "WORKFLOW_VALIDATION_OUTPUT_VARIABLE_DUPLICATED",
                        "输出变量 `" + outputVariable + "` 已由节点“" + previousOwner.name() + "”声明",
                        node
                    ));
                }
            }
        }
    }

    private void validateLinearExecutionOrder(
        List<WorkflowDraftApi.WorkflowNodeRow> nodes,
        List<WorkflowDraftApi.WorkflowEdgeRow> edges,
        List<WorkflowDraftApi.WorkflowValidationIssue> issues
    ) {
        if (nodes.size() <= 1) {
            return;
        }
        Set<String> expectedPairs = new LinkedHashSet<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            expectedPairs.add(edgePair(nodes.get(index).nodeId(), nodes.get(index + 1).nodeId()));
        }

        Set<String> actualPairs = new LinkedHashSet<>();
        for (WorkflowDraftApi.WorkflowEdgeRow edge : edges) {
            String pair = edgePair(edge.sourceNodeId(), edge.targetNodeId());
            if (!actualPairs.add(pair) || !expectedPairs.contains(pair)) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_LINEAR_EDGE_INVALID",
                    "当前阶段流程必须按左侧积木顺序单线串联，不能存在额外、重复或跨序连线",
                    null
                ));
                break;
            }
        }
        for (String expectedPair : expectedPairs) {
            if (!actualPairs.contains(expectedPair)) {
                issues.add(issue(
                    "WORKFLOW_VALIDATION_LINEAR_EDGE_REQUIRED",
                    "当前阶段流程必须按左侧积木顺序单线串联，请补齐相邻积木之间的连线",
                    null
                ));
                break;
            }
        }
    }

    private static String edgePair(String sourceNodeId, String targetNodeId) {
        return sourceNodeId + "->" + targetNodeId;
    }

    private static Set<String> collectUpstreamVariables(
        String nodeId,
        Map<String, WorkflowDraftApi.WorkflowNodeRow> nodesById,
        Map<String, List<String>> incoming
    ) {
        Set<String> variables = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(incoming.getOrDefault(nodeId, List.of()));

        // 即使图里暂时存在环，也要把能识别出的上游变量尽量算清楚，给设计者返回完整修复提示。
        while (!stack.isEmpty()) {
            String upstreamNodeId = stack.pop();
            if (!visited.add(upstreamNodeId)) {
                continue;
            }
            WorkflowDraftApi.WorkflowNodeRow upstreamNode = nodesById.get(upstreamNodeId);
            if (upstreamNode == null) {
                continue;
            }
            variables.addAll(normalizeVariables(upstreamNode.outputVariables()));
            incoming.getOrDefault(upstreamNodeId, List.of()).forEach(stack::push);
        }
        return variables;
    }

    private static Map<String, WorkflowDraftApi.WorkflowNodeRow> indexNodes(List<WorkflowDraftApi.WorkflowNodeRow> nodes) {
        Map<String, WorkflowDraftApi.WorkflowNodeRow> indexed = new LinkedHashMap<>();
        for (WorkflowDraftApi.WorkflowNodeRow node : nodes) {
            indexed.put(node.nodeId(), node);
        }
        return indexed;
    }

    private static Map<String, List<String>> buildOutgoing(Collection<String> nodeIds, List<WorkflowDraftApi.WorkflowEdgeRow> edges) {
        Map<String, List<String>> outgoing = emptyAdjacency(nodeIds);
        for (WorkflowDraftApi.WorkflowEdgeRow edge : edges) {
            outgoing.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>()).add(edge.targetNodeId());
        }
        return outgoing;
    }

    private static Map<String, List<String>> buildIncoming(Collection<String> nodeIds, List<WorkflowDraftApi.WorkflowEdgeRow> edges) {
        Map<String, List<String>> incoming = emptyAdjacency(nodeIds);
        for (WorkflowDraftApi.WorkflowEdgeRow edge : edges) {
            incoming.computeIfAbsent(edge.targetNodeId(), ignored -> new ArrayList<>()).add(edge.sourceNodeId());
        }
        return incoming;
    }

    private static Map<String, List<String>> emptyAdjacency(Collection<String> nodeIds) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            adjacency.put(nodeId, new ArrayList<>());
        }
        return adjacency;
    }

    private static List<String> topologicalSort(
        Collection<String> nodeIds,
        Map<String, List<String>> incoming,
        Map<String, List<String>> outgoing
    ) {
        Map<String, Integer> indegrees = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            indegrees.put(nodeId, incoming.getOrDefault(nodeId, List.of()).size());
        }

        Queue<String> queue = new ArrayDeque<>();
        indegrees.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey)
            .forEach(queue::add);

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.remove();
            order.add(nodeId);
            for (String targetNodeId : outgoing.getOrDefault(nodeId, List.of())) {
                int nextIndegree = indegrees.computeIfPresent(targetNodeId, (ignored, current) -> current - 1);
                if (nextIndegree == 0) {
                    queue.add(targetNodeId);
                }
            }
        }
        return order;
    }

    private static Set<String> collectReachableNodes(String triggerNodeId, Map<String, List<String>> outgoing) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(triggerNodeId);

        while (!stack.isEmpty()) {
            String nodeId = stack.pop();
            if (!visited.add(nodeId)) {
                continue;
            }
            for (String targetNodeId : outgoing.getOrDefault(nodeId, List.of())) {
                stack.push(targetNodeId);
            }
        }
        return visited;
    }

    private static Set<String> normalizeVariables(List<String> variables) {
        Set<String> normalized = new LinkedHashSet<>();
        if (variables == null) {
            return normalized;
        }
        variables.stream()
            .map(variable -> variable == null ? "" : variable.trim())
            .filter(variable -> !variable.isBlank())
            .forEach(normalized::add);
        return normalized;
    }

    private static WorkflowDraftApi.WorkflowValidationIssue issue(
        String code,
        String message,
        WorkflowDraftApi.WorkflowNodeRow node
    ) {
        return new WorkflowDraftApi.WorkflowValidationIssue(
            code,
            ERROR_LEVEL,
            message,
            node == null ? "" : node.nodeId(),
            node == null ? "" : node.name()
        );
    }
}
